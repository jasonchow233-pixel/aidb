package io.agenticdb.transfer;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
        Dialect dialect = session.dialect();
        List<String> errors = new ArrayList<>();
        int inserted = 0;
        int skipped = 0;
        int totalRead = 0;
        int rowNumber = 0;

        String insertSql = insertSql(dialect, table, columns);
        PushbackReader reader = new PushbackReader(in, 8);

        // 跳过开头几行（文件前面有说明行时用）
        for (int i = 0; i < options.skipRows(); i++) {
            if (CsvCodec.readRow(reader, options.format().delimiter()) == null) {
                return new ImportReport(0, 0, errors, 0);
            }
        }

        // 表头
        List<String> header = null;
        if (options.hasHeader()) {
            header = CsvCodec.readRow(reader, options.format().delimiter());
            if (header == null) {
                return new ImportReport(0, 0, errors, 0);
            }
        }

        List<int[]> mapping = resolveMapping(options, header, columns);
        if (mapping == null) {
            errors.add("表头与目标列无法匹配：" + String.join(", ", header));
            return new ImportReport(0, 0, errors, 0);
        }

        boolean ownTransaction = !session.inTransaction();
        try {
            if (ownTransaction) {
                session.begin();
            }
            List<List<String>> batch = new ArrayList<>();
            List<Integer> batchRows = new ArrayList<>();

            List<String> row;
            while ((row = CsvCodec.readRow(reader, options.format().delimiter())) != null) {
                rowNumber++;
                totalRead++;
                if (row.isEmpty() || (row.size() == 1 && row.getFirst().isEmpty())) {
                    // 空行：跳过但不计入坏行
                    continue;
                }
                batch.add(row);
                batchRows.add(rowNumber);
                if (batch.size() >= options.batchSize()) {
                    Outcome outcome = flush(session, insertSql, batch, batchRows, mapping,
                            columns.size(), errors);
                    inserted += outcome.inserted();
                    skipped += outcome.skipped();
                    batch.clear();
                    batchRows.clear();
                    commitBatch(session, ownTransaction);
                }
            }
            if (!batch.isEmpty()) {
                Outcome outcome = flush(session, insertSql, batch, batchRows, mapping,
                        columns.size(), errors);
                inserted += outcome.inserted();
                skipped += outcome.skipped();
            }
            if (ownTransaction) {
                session.commit();
            }
        } catch (SQLException | RuntimeException e) {
            if (ownTransaction) {
                session.rollback();
            }
            throw e;
        }
        return new ImportReport(inserted, skipped, errors, totalRead);
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
