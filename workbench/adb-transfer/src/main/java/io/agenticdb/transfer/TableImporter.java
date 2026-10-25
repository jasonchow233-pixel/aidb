package io.agenticdb.transfer;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表数据导入。
 *
 * <p>⭐ <b>流式 + 批量提交</b>：读一批、写一批、提交一批 ——
 * 导入一千万行不会把内存吃光；后面某一批失败也不会把前面几百万行一起回滚。
 *
 * <p><b>坏行怎么找出来</b>：类型不匹配这类错误是在<b>执行批次时</b>才爆出来的
 * （绑参数时 JDBC 不知道类型对不对），所以「逐行 try/catch」抓不到。
 * 这里的做法是：整批执行失败 → 回滚这一批 → <b>逐行重试</b>，
 * 找出具体哪几行坏了，坏的跳过，好的留下。
 * 正常路径仍然是批量的，代价只在出错时付。
 */
public final class TableImporter {

    private TableImporter() {
    }

    /**
     * 导入结果。
     *
     * @param inserted  成功插入的行数
     * @param skipped   跳过的坏行数
     * @param errors    坏行明细（最多 {@link #MAX_REPORTED} 条）
     * @param totalRead 读到的总行数
     */
    public record ImportReport(int inserted, int skipped, List<String> errors, int totalRead) {

        public ImportReport {
            errors = List.copyOf(errors == null ? List.of() : errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** 最多报告多少条坏行 —— 再多就只报数量。 */
    public static final int MAX_REPORTED = 50;

    private static final String SAVEPOINT = "adb_import_row";

    /**
     * 导入到一个已有的表。
     *
     * @param session 会话
     * @param table   目标表
     * @param columns 目标表的列（按顺序）
     * @param options 导入参数
     * @param in      输入（调用方负责关闭）
     */
    public static ImportReport importRows(DbSession session, String table, List<String> columns,
                                          ImportOptions options, Reader in)
            throws SQLException, IOException {
        return importRows(session, table, columns, null, options, in);
    }

    /**
     * ⭐ P6-1：带目标列类型导入 —— 文本先按列类型转换，坏值在<b>绑定前</b>就被拦下。
     *
     * <p>只给列名时所有值都按字符串绑定，SQLite 这类弱类型库会把 {@code "abc"}
     * 原样塞进 INTEGER 列，坏数据要等用户查询时才暴露；明确类型后，
     * {@code "abc"} 会作为坏行进入报告，{@code "1,234"} / {@code "yes"} /
     * {@code "2026-3-4"} 则会转成目标类型再写入。
     */
    public static ImportReport importTypedRows(DbSession session, String table,
                                               List<ColumnDef> columns, ImportOptions options,
                                               Reader in) throws SQLException, IOException {
        List<String> names = new ArrayList<>();
        List<ColumnType> types = new ArrayList<>();
        for (ColumnDef column : columns) {
            names.add(column.name());
            types.add(column.type());
        }
        return importRows(session, table, names, types, options, in);
    }

    /**
     * ⭐ P6-3：从 xlsx 导入（SAX 流式）。列映射 / 类型转换 / 坏行报告与 CSV 完全一致。
     */
    public static ImportReport importXlsx(DbSession session, String table,
                                          List<ColumnDef> columns, ImportOptions options,
                                          Path file) throws SQLException, IOException {
        List<String> names = new ArrayList<>();
        List<ColumnType> types = new ArrayList<>();
        for (ColumnDef column : columns) {
            names.add(column.name());
            types.add(column.type());
        }
        ImportOptions effective = options.format() == ImportFormat.XLSX ? options
                : new ImportOptions(ImportFormat.XLSX, options.hasHeader(), options.batchSize(),
                        options.columnMap(), options.skipRows(), options.stopOnError());
        return importFrom(session, table, names, types, effective, sink ->
                XlsxCodec.read(file, cells -> accept(cells, sink)));
    }

    /** xlsx 回调只能抛 IOException；把数据库异常包进去，顶层仍能报出原因。 */
    private static boolean accept(List<String> cells, RowSink sink) throws IOException {
        try {
            return sink.accept(cells);
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static ImportReport importRows(DbSession session, String table,
                                           List<String> columns, List<ColumnType> types,
                                           ImportOptions options, Reader in)
            throws SQLException, IOException {
        PushbackReader reader = new PushbackReader(in, 8);
        return importFrom(session, table, columns, types, options, sink -> {
            List<String> row;
            while ((row = CsvCodec.readRow(reader, options.format().delimiter())) != null) {
                if (!sink.accept(row)) {
                    return;
                }
            }
        });
    }

    /** 逐行喂数据；CSV 与 xlsx 共用同一条解析 / 批量 / 提交路径。 */
    @FunctionalInterface
    private interface RowFeed {
        void feed(RowSink sink) throws IOException, SQLException;
    }

    @FunctionalInterface
    private interface RowSink {
        /** @return {@code false} 表示无法继续（例如表头与目标列对不上） */
        boolean accept(List<String> row) throws SQLException;
    }

    private static ImportReport importFrom(DbSession session, String table,
                                           List<String> columns, List<ColumnType> types,
                                           ImportOptions options, RowFeed feed)
            throws SQLException, IOException {
        Ingest ingest = new Ingest(session, session.dialect(), table, columns, types, options,
                !session.inTransaction());
        boolean ownTransaction = !session.inTransaction();
        try {
            if (ownTransaction) {
                session.begin();
            }
            feed.feed(ingest);
            ingest.finish();
            if (ownTransaction) {
                session.commit();
            }
        } catch (SQLException | IOException | RuntimeException e) {
            if (ownTransaction) {
                session.rollback();
            }
            throw e;
        }
        return ingest.report();
    }

    /**
     * 导入状态机：跳过说明行 → 表头映射 → 逐行入批 → 满批提交。
     *
     * <p>CSV 的「拉」与 xlsx 的「推」都只是喂行方式不同，行的语义完全一致。
     */
    private static final class Ingest implements RowSink {

        private final DbSession session;
        private final String insertSql;
        private final List<String> columns;
        private final List<ColumnType> types;
        private final ImportOptions options;
        private final boolean ownTransaction;
        private final List<String> errors = new ArrayList<>();
        private final List<List<String>> batch = new ArrayList<>();
        private final List<Integer> batchRows = new ArrayList<>();

        private int skippedLeadingRows;
        private boolean headerPending;
        private boolean stopped;
        private List<int[]> mapping;
        private int rowNumber;
        private int inserted;
        private int skipped;
        private int totalRead;

        Ingest(DbSession session, Dialect dialect, String table, List<String> columns,
               List<ColumnType> types, ImportOptions options, boolean ownTransaction) {
            this.session = session;
            this.insertSql = insertSql(dialect, table, columns);
            this.columns = columns;
            this.types = types;
            this.options = options;
            this.ownTransaction = ownTransaction;
            this.headerPending = options.hasHeader();
        }

        @Override
        public boolean accept(List<String> row) throws SQLException {
            if (stopped) {
                return false;
            }
            if (skippedLeadingRows < options.skipRows()) {
                skippedLeadingRows++;
                return true;
            }
            if (headerPending) {
                headerPending = false;
                mapping = resolveMapping(options, row, columns);
                if (mapping == null) {
                    errors.add("表头与目标列无法匹配：" + String.join(", ", row));
                    stopped = true;
                    return false;
                }
                return true;
            }
            if (mapping == null) {
                // 没有表头 → 按顺序一一对应（空映射 = 顺序）
                mapping = resolveMapping(options, null, columns);
            }
            rowNumber++;
            totalRead++;
            if (row.isEmpty() || (row.size() == 1 && row.getFirst().isEmpty())) {
                // 空行：跳过但不计入坏行
                return true;
            }
            batch.add(row);
            batchRows.add(rowNumber);
            if (batch.size() >= options.batchSize()) {
                drain();
                commitBatch(session, ownTransaction);
            }
            return true;
        }

        void finish() throws SQLException {
            if (!batch.isEmpty()) {
                drain();
            }
        }

        ImportReport report() {
            return new ImportReport(inserted, skipped, errors, totalRead);
        }

        private void drain() throws SQLException {
            Outcome outcome = flush(session, insertSql, batch, batchRows, mapping,
                    columns, types, errors);
            inserted += outcome.inserted();
            skipped += outcome.skipped();
            batch.clear();
            batchRows.clear();
        }
    }

    /**
     * 每批提交一次。
     *
     * <p>这样「后面某批坏了」不会把前面几百万行一起回滚 ——
     * 导入的语义本来就是「能进多少进多少，坏的报告出来」。
     */
    private static void commitBatch(DbSession session, boolean ownTransaction)
            throws SQLException {
        if (!ownTransaction) {
            return;
        }
        session.commit();
        session.begin();
    }

    private record Outcome(int inserted, int skipped) {
    }

    /**
     * 执行一批。
     *
     * <p>正常路径：整批 {@code executeBatch()}。
     * 出错时：<b>回滚这一批</b>，再用一条<b>全新的语句</b>逐行重试，把坏的挑出来。
     *
     * <p>为什么必须换新语句：批次执行失败后，原来的 {@code PreparedStatement}
     * 已经不可用了（驱动报「statement is not executing」），
     * 在它上面重试只会把好行也一起判死。
     */
    private static Outcome flush(DbSession session, String insertSql,
                                 List<List<String>> batch, List<Integer> batchRows,
                                 List<int[]> mapping, List<String> columns,
                                 List<ColumnType> types, List<String> errors)
            throws SQLException {
        if (types == null) {
            return flushRaw(session, insertSql, batch, batchRows, mapping, columns.size(), errors);
        }
        return flushTyped(session, insertSql, batch, batchRows, mapping, columns, types, errors);
    }

    private static Outcome flushRaw(DbSession session, String insertSql,
                                    List<List<String>> batch, List<Integer> batchRows,
                                    List<int[]> mapping, int columnCount,
                                    List<String> errors) throws SQLException {
        try (PreparedStatement statement =
                     session.connection().prepareStatement(insertSql)) {
            for (List<String> row : batch) {
                bindRow(statement, row, mapping, columnCount);
                statement.addBatch();
            }
            statement.executeBatch();
            return new Outcome(batch.size(), 0);
        } catch (SQLException batchError) {
            // 整批失败 → 回滚这一批，再逐行找出坏行。
            session.rollback();
            session.begin();
            int inserted = 0;
            int skipped = 0;
            for (int i = 0; i < batch.size(); i++) {
                List<String> row = batch.get(i);
                int rowNumber = batchRows.get(i);
                // ⚠️ 每行都用**全新的语句**：一次执行失败后，
                //    原来的 PreparedStatement 会被驱动判为「statement is not executing」，
                //    在它上面继续跑，后面的好行会跟着一起被判死。
                try (PreparedStatement one =
                             session.connection().prepareStatement(insertSql)) {
                    bindRow(one, row, mapping, columnCount);
                    one.executeUpdate();
                    inserted++;
                } catch (SQLException | RuntimeException e) {
                    skipped++;
                    if (errors.size() < MAX_REPORTED) {
                        errors.add("第 " + rowNumber + " 行：" + e.getMessage());
                    }
                }
            }
            return new Outcome(inserted, skipped);
        }
    }

    /**
     * ⭐ P6-1：先按目标列类型转换，再批量执行。
     *
     * <p>转换失败的行在<b>绑定前</b>就被剔除 —— 于是 SQLite 这类「什么值都收」
     * 的库也能把 {@code "abc" -> INTEGER} 报成坏行，而不是悄悄写进表里。
     * 正常行仍走 {@code executeBatch()}；数据库侧失败（唯一键 / 外键…）
     * 仍按「整批回滚 + 逐行重试」找出坏行。
     */
    private static Outcome flushTyped(DbSession session, String insertSql,
                                      List<List<String>> batch, List<Integer> batchRows,
                                      List<int[]> mapping, List<String> columns,
                                      List<ColumnType> types, List<String> errors)
            throws SQLException {
        List<Object[]> values = new ArrayList<>();
        List<Integer> acceptedRows = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < batch.size(); i++) {
            int rowNumber = batchRows.get(i);
            try {
                values.add(coerceRow(batch.get(i), mapping, columns, types));
                acceptedRows.add(rowNumber);
            } catch (IllegalArgumentException bad) {
                skipped++;
                if (errors.size() < MAX_REPORTED) {
                    errors.add("第 " + rowNumber + " 行：" + bad.getMessage());
                }
            }
        }
        if (values.isEmpty()) {
            return new Outcome(0, skipped);
        }
        try (PreparedStatement statement =
                     session.connection().prepareStatement(insertSql)) {
            for (Object[] row : values) {
                bindValues(statement, row);
                statement.addBatch();
            }
            statement.executeBatch();
            return new Outcome(values.size(), skipped);
        } catch (SQLException batchError) {
            session.rollback();
            session.begin();
            int inserted = 0;
            for (int i = 0; i < values.size(); i++) {
                try (PreparedStatement one =
                             session.connection().prepareStatement(insertSql)) {
                    bindValues(one, values.get(i));
                    one.executeUpdate();
                    inserted++;
                } catch (SQLException | RuntimeException e) {
                    skipped++;
                    if (errors.size() < MAX_REPORTED) {
                        errors.add("第 " + acceptedRows.get(i) + " 行：" + e.getMessage());
                    }
                }
            }
            return new Outcome(inserted, skipped);
        }
    }

    /**
     * 按列类型转换一行。映射与 {@link #bindRow} 同一套规则：
     * 空映射 = 顺序，显式映射 = (文件列, 目标列)。
     */
    static Object[] coerceRow(List<String> row, List<int[]> mapping, List<String> columns,
                              List<ColumnType> types) {
        Object[] values = new Object[types.size()];
        for (int target = 0; target < types.size(); target++) {
            int fileColumn = target;
            if (!mapping.isEmpty()) {
                fileColumn = -1;
                for (int[] pair : mapping) {
                    if (pair[1] == target) {
                        fileColumn = pair[0];
                        break;
                    }
                }
                if (fileColumn < 0) {
                    continue;
                }
            }
            if (fileColumn >= row.size()) {
                continue;
            }
            String raw = row.get(fileColumn);
            try {
                values[target] = coerce(raw, types.get(target));
            } catch (IllegalArgumentException invalid) {
                String column = target < columns.size() ? columns.get(target) : ("#" + (target + 1));
                throw new IllegalArgumentException("列 " + column + "：" + invalid.getMessage());
            }
        }
        return values;
    }

    /**
     * 按目标类型转换单个文本值。
     *
     * <p>文本类列<b>原样保留</b>（{@code "00123"} 不能被转成 123）；
     * 数值 / 布尔 / 日期类列的空串视为 NULL —— 目标列没值时，
     * 写 NULL 比写一个类型不对的空串更接近用户的意图。
     *
     * @throws IllegalArgumentException 值无法转换（调用方会把它记成坏行）
     */
    static Object coerce(String raw, ColumnType type) {
        if (raw == null || type == null) {
            return raw;
        }
        String text = raw.strip();
        switch (type) {
            case NUMBER, BOOLEAN, DATE, TIME, TIMESTAMP -> {
                if (text.isEmpty()) {
                    return null;
                }
            }
            default -> {
                return raw;
            }
        }
        return switch (type) {
            case NUMBER -> number(text);
            case BOOLEAN -> booleanValue(text);
            case DATE -> dateValue(text);
            case TIME -> timeValue(text);
            case TIMESTAMP -> timestampValue(text);
            default -> raw;
        };
    }

    private static Object number(String text) {
        String normalized = text.replace(",", "").replace("_", "");
        try {
            if (normalized.matches("[+-]?\\d+")) {
                return Long.parseLong(normalized);
            }
            return new BigDecimal(normalized);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("无法把「" + text + "」转换为数字");
        }
    }

    private static Boolean booleanValue(String text) {
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "t", "yes", "y", "1", "是", "真" -> Boolean.TRUE;
            case "false", "f", "no", "n", "0", "否", "假" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "无法把「" + text + "」转换为布尔值（可用 true/false、yes/no、1/0）");
        };
    }

    private static java.sql.Date dateValue(String text) {
        String normalized = text.replace('/', '-').replace('.', '-');
        try {
            return java.sql.Date.valueOf(
                    LocalDate.parse(normalized, DateTimeFormatter.ofPattern("yyyy-M-d")));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("无法把「" + text + "」转换为日期（应形如 2026-03-04）");
        }
    }

    private static java.sql.Time timeValue(String text) {
        for (String pattern : new String[]{"H:m:s.S", "H:m:s", "H:m"}) {
            try {
                return java.sql.Time.valueOf(
                        LocalTime.parse(text, DateTimeFormatter.ofPattern(pattern)));
            } catch (DateTimeParseException ignored) {
                // 试下一种
            }
        }
        throw new IllegalArgumentException("无法把「" + text + "」转换为时间（应形如 12:30:00）");
    }

    private static java.sql.Timestamp timestampValue(String text) {
        String normalized = text.replace('T', ' ').replaceAll("\\s+", " ").strip();
        for (String pattern : new String[]{"yyyy-M-d H:m:s.S", "yyyy-M-d H:m:s",
                "yyyy-M-d H:m", "yyyy-M-d"}) {
            try {
                LocalDateTime moment = pattern.equals("yyyy-M-d")
                        ? LocalDate.parse(normalized,
                                DateTimeFormatter.ofPattern(pattern)).atStartOfDay()
                        : LocalDateTime.parse(normalized,
                                DateTimeFormatter.ofPattern(pattern));
                return java.sql.Timestamp.valueOf(moment);
            } catch (DateTimeParseException ignored) {
                // 试下一种
            }
        }
        throw new IllegalArgumentException(
                "无法把「" + text + "」转换为时间戳（应形如 2026-03-04 12:30:00）");
    }

    private static void bindValues(PreparedStatement statement, Object[] values)
            throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private static void savepoint(DbSession session, boolean create) throws SQLException {
        if (create) {
            session.connection().createStatement().execute("SAVEPOINT " + SAVEPOINT);
        } else {
            session.connection().createStatement().execute("RELEASE SAVEPOINT " + SAVEPOINT);
        }
    }

    /**
     * 解析列映射。
     *
     * <p>优先级：显式映射 &gt; 按表头名字匹配 &gt; 按顺序一一对应。
     *
     * @return 映射；{@code null} 表示无法匹配（表头与目标列对不上）
     */
    static List<int[]> resolveMapping(ImportOptions options, List<String> header,
                                      List<String> targetColumns) {
        if (!options.columnMap().isEmpty()) {
            return options.columnMap();
        }
        if (header == null) {
            // 没有表头 → 按顺序一一对应（空映射 = 顺序）
            return List.of();
        }
        List<int[]> mapping = new ArrayList<>();
        for (int t = 0; t < targetColumns.size(); t++) {
            String target = targetColumns.get(t);
            for (int f = 0; f < header.size(); f++) {
                if (target.equalsIgnoreCase(header.get(f).trim())) {
                    mapping.add(new int[]{f, t});
                    break;
                }
            }
        }
        if (!mapping.isEmpty()) {
            return mapping;
        }
        // 表头一个都没对上：列数一致就按顺序来，不一致就没法导。
        return header.size() == targetColumns.size() ? List.of() : null;
    }

    private static String insertSql(Dialect dialect, String table, List<String> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(dialect.quote(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(dialect.quote(columns.get(i)));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
        return sql.append(')').toString();
    }

    /**
     * 按映射把一行绑到语句上。
     *
     * <p>空映射 = 顺序一一对应（文件第 i 列 → 目标第 i 列）。
     */
    private static void bindRow(PreparedStatement statement, List<String> row,
                                List<int[]> mapping, int targetColumnCount) throws SQLException {
        String[] values = new String[targetColumnCount];
        if (mapping.isEmpty()) {
            for (int i = 0; i < targetColumnCount && i < row.size(); i++) {
                values[i] = row.get(i);
            }
        } else {
            for (int[] pair : mapping) {
                int fileColumn = pair[0];
                int targetColumn = pair[1];
                if (fileColumn < 0 || fileColumn >= row.size() || targetColumn < 0
                        || targetColumn >= targetColumnCount) {
                    continue;
                }
                values[targetColumn] = row.get(fileColumn);
            }
        }
        for (int i = 0; i < targetColumnCount; i++) {
            if (values[i] == null) {
                statement.setNull(i + 1, java.sql.Types.VARCHAR);
            } else {
                statement.setString(i + 1, values[i]);
            }
        }
    }
}
