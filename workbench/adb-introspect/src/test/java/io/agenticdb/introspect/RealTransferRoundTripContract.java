package io.agenticdb.introspect;

import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExternalDatabase;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.transfer.CsvCodec;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.ImportFormat;
import io.agenticdb.transfer.ImportOptions;
import io.agenticdb.transfer.TableExporter;
import io.agenticdb.transfer.TableImporter;

import java.io.BufferedWriter;
import java.io.PushbackReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R55 · 真库契约第 12 条：<b>导出一个文件、再把它导回来，五家各落成什么</b>。
 *
 * <p>这条路是「换库 / 备份一份 / 给业务方发数据」用得最多的一条，也是产品侧只有两个入口的
 * 一条：{@link TableExporter#export} 与 {@link TableImporter#importTypedRows}
 * （界面 {@code ExportWizard} / {@code ImportWizard} 就是调这两个）。
 * <b>本契约按界面同一顺序调同两个方法</b>，中间那个文件是真的落在磁盘上的文件 ——
 * 不是内存里的字符串，因为「写出时的编码」与「读回时的编码」只有过一遍磁盘才咬得上。
 *
 * <p>⚠️ 为什么此前它是「零真库证据」：{@code adb-transfer} 的六个测试类全部跑在
 * SQLite 内存库上。而导出这一侧的形状是<b>驱动</b>决定的：
 * {@code TableExporter} 写格子用的是 {@code String.valueOf(rs.getObject(i))}，
 * 于是同一句 {@code SELECT} 在五家交出来的 Java 类型不同 ——
 * MySQL 的 {@code TINYINT(1)} 回 {@code Boolean}（文件里是 {@code true}）、
 * Oracle 的 {@code NUMBER(1)} 回 {@code BigDecimal}（文件里是 {@code 1}）、
 * 小数那格 MySQL/PG/SQL Server 写 {@code 10.50}、Oracle 写 {@code 10.5}。
 * 导入那一侧要按<b>目录读回来的列类型</b>把这些文本再折回服务端吃得下的形状
 * （{@code TableImporter.coerce}）。SQLite 恰好两头都顺着我们，所以单测全绿。
 *
 * <p>⚠️ 这条路上最贵的失败不是报错，是 {@code skipped}：导入器遇到坏行的策略是
 * 「整批失败 → 回滚这批 → 逐行重试 → 坏行跳过并记进报告」（{@code stopOnError=false}
 * 是界面默认）。所以「三行只进来两行」在界面上是一条报告，不是一道红 ——
 * 五家全绿的单测抓不到它，只有逐行比对能抓到。本契约把
 * {@code inserted == 3 && skipped == 0 && errors 为空} 三条都钉住。
 *
 * <p>⚠️ 记一条 D-38 的教训（同 {@code RealEditWriteBackContract}）：契约不许写
 * 「产品那条路失败了我就自己补一遍」的兜底，也不许<b>猜</b>界面交出来的形状。
 * 所以这里：文件里的每一格按<b>驱动交回来的那一格的文本</b>断言（{@code String.valueOf(读回来的值)}），
 * 而不是按契约手写的一个数字；目标表的列定义从 {@link SchemaService} 内省拿
 * （界面 {@code ImportWizard} 用的就是它），不在契约里再手写一张类型名单。
 *
 * <p>⚠️ 为什么这条契约<b>住在 {@code adb-introspect} 而不是 {@code adb-transfer}</b>：
 * 它必须同时看到两侧的产品口 —— 列定义要从 {@link SchemaService} 内省拿（界面
 * {@code ImportWizard} 用的就是它），读写要经 {@code TableExporter} / {@code TableImporter}。
 * 依赖方向是 {@code adb-introspect(test) → adb-transfer(main)}，反过来加一条就成环
 * （Maven 直接拒绝构建）。被测的是 transfer 那条路，只是它得从能同时看见两边的模块里跑。
 *
 * <p>范围说明（不是遗漏，是划界）：xlsx 那条（{@code exportXlsx} / {@code importXlsx}）
 * 本轮<b>没有</b>进真库矩阵。二进制格式里「哪一格是什么类型」由 POI 决定，与对面是哪台
 * 服务器无关；真库相关的那一半（值文本形状、编码、NULL 与空串）CSV 这条已经量到。
 */
final class RealTransferRoundTripContract {

    private RealTransferRoundTripContract() {
    }

    /** 契约体允许抛异常。 */
    @FunctionalInterface
    interface Body {
        void run(ExternalDatabase target) throws Exception;
    }

    /** 种子行里那格「什么都占全了」的备注：逗号、引号、& 与换行同时出现。 */
    private static final String NOTE_WITH_QUOTES = "含,逗号和\"引号\" & 符号";

    /** 同一格里再放一个换行 —— CSV 必须把它引起来，否则行数都对不上。 */
    private static final String NOTE_WITH_NEWLINE = "第一行\n第二行";

    private static final int SEED_ROWS = 3;

    // ─────────────────── 契约 12：导出 → 导回 → 逐行比对 ───────────────────

    static void csvRoundTripKeepsWhatTheServerGaveUs(ExternalDatabase target) throws Exception {
        Path file = Files.createTempFile("adb-transfer-contract-", ".csv");
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String src = "adb_contract_tf_" + System.nanoTime();
            String dst = src + "_2";
            String slim = src + "_3";
            try {
                createTransferTable(session, dialect, target.kind(), src);
                createTransferTable(session, dialect, target.kind(), dst);
                seed(session, dialect, target.kind(), src);

                // ① 目录先把这张表读回来 —— 后面全部按目录里的列名取值，
                //    哪一家把带引号的小写标识符读成大写，这里就当场露出来（D-22 的前科）。
                TableSchema schema = new SchemaService().describe(session, src);
                expectCatalogNames(schema, dialect);

                // ② 整表导出（列传空 = 全列，界面「全部列」就是这个形状）。
                long written;
                try (Writer out = new BufferedWriter(
                        Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                    written = TableExporter.export(session, src, List.of(),
                            ExportOptions.of(ExportFormat.CSV), out);
                }
                assertThat(written)
                        .as("%s：整表导出要写出三行（导出侧的行数由结果集说了算，少了就是流式循环漏了）",
                                dialect.displayName())
                        .isEqualTo(SEED_ROWS);

                List<List<String>> cells = readCsv(file);
                Map<String, List<String>> byId = new LinkedHashMap<>();
                for (int i = 1; i < cells.size(); i++) {
                    byId.put(cells.get(i).get(0), cells.get(i));
                }
                assertThat(byId)
                        .as("%s：文件里恰好三行数据（导出没有 ORDER BY，按主键归行而不是按行号）",
                                dialect.displayName())
                        .hasSize(SEED_ROWS);

                // ③ 表头 = 目录里那套列名，顺序一致。用户拿这份文件去另一台库导回时，
                //    列名对不上就是一张废文件；各家 ResultSetMetaData 的 label 形状不同，
                //    这一格以前从没在真库上量过。
                assertThat(cells.get(0))
                        .as("%s：导出的表头要就是目录里的列名", dialect.displayName())
                        .containsExactlyElementsOf(names(schema));

                Map<String, Object> firstRow = readRow(session, dialect, src, 1L);
                List<String> first = byId.get("1");
                assertThat(first.get(1))
                        .as("%s：中文要按 UTF-8 写进文件（编码或列的字符集不对会变 ?? / ???）",
                                dialect.displayName())
                        .isEqualTo("张三");
                assertThat(first.get(2))
                        .as("%s：小数那一格写的就是驱动交回来的那个形状（Oracle 的 NUMBER 少一位标度是它的形状，"
                                + "契约不猜）", dialect.displayName())
                        .isEqualTo(String.valueOf(firstRow.get("amount")));
                assertThat(first.get(3))
                        .as("%s：布尔那一格同理 —— 四家交 Boolean、Oracle 交 NUMBER，文件里就写各自那个文本",
                                dialect.displayName())
                        .isEqualTo(String.valueOf(firstRow.get("active")));
                assertThat(first.get(4))
                        .as("%s：逗号/引号/& 过一遍 RFC 4180 要能原样读回来", dialect.displayName())
                        .isEqualTo(NOTE_WITH_QUOTES);
                assertThat(byId.get("3").get(4))
                        .as("%s：格子里带换行时，引起来写出还要能读回来（读散了行数就错）",
                                dialect.displayName())
                        .isEqualTo(NOTE_WITH_NEWLINE);
                assertThat(Files.readString(file, StandardCharsets.UTF_8))
                        .as("%s：含分隔符的字段必须<b>带引号</b>写出（引号内的引号翻倍）—— "
                                + "这里比的是文件原始字节，不是自家编解码器的自证",
                                dialect.displayName())
                        .contains("\"含,逗号和\"\"引号\"\" & 符号\"");

                // ④ 导回：目标表的列定义从<b>内省</b>拿（界面 ImportWizard 就是这么来的）。
                TableImporter.ImportReport report = TableImporter.importTypedRows(session, dst,
                        new SchemaService().describe(session, dst).columns(),
                        ImportOptions.of(ImportFormat.CSV).withHeaderRow(1), file);
                assertThat(report.inserted())
                        .as("%s：三行都要插进去（实际 %d 行，坏行明细：%s）",
                                dialect.displayName(), report.inserted(), report.errors())
                        .isEqualTo(SEED_ROWS);
                assertThat(report.skipped())
                        .as("%s：一行都不许被跳过 —— 跳过在界面上只是一条报告，"
                                + "而文件是我们自己导出的", dialect.displayName())
                        .isZero();
                assertThat(report.errors())
                        .as("%s：坏行明细应为空", dialect.displayName())
                        .isEmpty();

                // ⑤ 逐行比对：源表与目标表都经 JdbcSession 的映射口读回来。
                for (long id : List.of(1L, 2L, 3L)) {
                    Map<String, Object> from = readRow(session, dialect, src, id);
                    Map<String, Object> into = readRow(session, dialect, dst, id);
                    assertThat(asText(into.get("name")))
                            .as("%s：第 %d 行的文本列绕了一圈磁盘要还是同一个值",
                                    dialect.displayName(), id)
                            .isEqualTo(asText(from.get("name")));
                    assertThat(asDecimal(into.get("amount")))
                            .as("%s：第 %d 行的小数导回要还是同一个数（标度被吃掉会变 10 或 11）",
                                    dialect.displayName(), id)
                            .isEqualByComparingTo(asDecimal(from.get("amount")));
                    assertThat(asBool(into.get("active")))
                            .as("%s：第 %d 行的布尔绕回来还是同一个真值（Oracle 那格中间是 1/0 文本）",
                                    dialect.displayName(), id)
                            .isEqualTo(asBool(from.get("active")));
                    if (id != 2L) {
                        assertThat(asText(into.get("note")))
                                .as("%s：第 %d 行的备注要原样回来（含引号/换行那两格）",
                                        dialect.displayName(), id)
                                .isEqualTo(asText(from.get("note")));
                    }
                }

                // ⑥ 那一格 NULL：CSV 表达不出「NULL」与「空串」的差别，导回落成什么是<b>服务器</b>说了算。
                assertThat(readRow(session, dialect, src, 2L).get("note"))
                        .as("前提：种子第 2 行的备注本来就是 NULL")
                        .isNull();
                Object backFromNull = readRow(session, dialect, dst, 2L).get("note");
                if (target.kind() == DialectKind.ORACLE) {
                    assertThat(backFromNull)
                            .as("Oracle 把 '' 存成 NULL（它自己的形状）：这一格导回后仍是 NULL")
                            .isNull();
                } else {
                    assertThat(backFromNull)
                            .as("%s：NULL 经 CSV 变成空串 —— 这是 CSV 的固有歧义，"
                                    + "不是导入器漏了（Navicat 同）", dialect.displayName())
                            .isEqualTo("");
                }

                // ⑦ 列选择：只导 id + name 两列，导回一张两列的表。
                //    这一条量的是「SELECT 只列选中的列」在真库上引号形状对不对，
                //    以及少列的文件按位置映射进目标表时，类型转换吃不吃得下。
                Path slimFile = Files.createTempFile("adb-transfer-contract-slim-", ".csv");
                try (Writer out = new BufferedWriter(
                        Files.newBufferedWriter(slimFile, StandardCharsets.UTF_8))) {
                    assertThat(TableExporter.export(session, src, List.of("id", "name"),
                            ExportOptions.of(ExportFormat.CSV), out))
                            .as("%s：两列导出也要写出三行", dialect.displayName())
                            .isEqualTo(SEED_ROWS);
                }
                createTwoColumnTable(session, dialect, target.kind(), slim);
                List<String> slimHeader = readCsv(slimFile).get(0);
                assertThat(slimHeader)
                        .as("%s：没选的那三列不能出现在表头里", dialect.displayName())
                        .containsExactly("id", "name");
                TableImporter.ImportReport slimReport = TableImporter.importTypedRows(session,
                        slim, new SchemaService().describe(session, slim).columns(),
                        ImportOptions.of(ImportFormat.CSV).withHeaderRow(1), slimFile);
                assertThat(slimReport.inserted())
                        .as("%s：两列文件要能灌进两列表（坏行明细：%s）",
                                dialect.displayName(), slimReport.errors())
                        .isEqualTo(SEED_ROWS);
                assertThat(session.query("SELECT " + dialect.quote("name") + " FROM "
                                + dialect.quote(slim) + " ORDER BY " + dialect.quote("id"), 10)
                        .rows())
                        .as("%s：列子集绕一圈，名字列一行不差（按 id 升序；第 2 行是 ASCII 名，"
                                + "只有种子如此，不是哪一家把它折了）", dialect.displayName())
                        .extracting(row -> asText(row.get("name")))
                        .containsExactly("张三", "Al Smith", "王五");
                Files.deleteIfExists(slimFile);
            } finally {
                dropQuietly(session, dialect, src);
                dropQuietly(session, dialect, dst);
                dropQuietly(session, dialect, slim);
                Files.deleteIfExists(file);
            }
        }
    }

    // ─────────────────────────────── 夹具与读数 ───────────────────────────────

    /**
     * 建一张覆盖界面那四档的表：整数主键 / 文本 / 小数 / 布尔 / 可空文本。
     *
     * <p>⚠️ 类型名按方言给（这是<b>夹具</b>，不是被测路径 —— 被测的是导出与导入）：
     * SQL Server 的文本必须 {@code NVARCHAR}，否则中文按单字节代码页解释折成问号，
     * 那是测试自己造出来的假阳性；Oracle 没有可用的布尔建表类型，
     * {@code active} 落成 {@code NUMBER(1)}，界面按数字档处理它。
     */
    private static void createTransferTable(JdbcSession session, Dialect dialect,
                                            DialectKind kind, String table) throws Exception {
        String sql = "CREATE TABLE " + dialect.quote(table) + " ("
                + dialect.quote("id") + " " + idType(kind) + " NOT NULL PRIMARY KEY, "
                + dialect.quote("name") + " " + textType(kind, 50) + " NOT NULL, "
                + dialect.quote("amount") + " " + decimalType(kind) + " NULL, "
                + dialect.quote("active") + " " + boolType(kind) + " NULL, "
                + dialect.quote("note") + " " + textType(kind, 200) + " NULL)";
        String options = dialect.createTableOptions();
        if (options != null && !options.isBlank()) {
            sql += " " + options;
        }
        session.execute(sql);
    }

    /** 列选择那一段用的两列表。 */
    private static void createTwoColumnTable(JdbcSession session, Dialect dialect,
                                             DialectKind kind, String table) throws Exception {
        String sql = "CREATE TABLE " + dialect.quote(table) + " ("
                + dialect.quote("id") + " " + idType(kind) + " NOT NULL PRIMARY KEY, "
                + dialect.quote("name") + " " + textType(kind, 50) + " NOT NULL)";
        String options = dialect.createTableOptions();
        if (options != null && !options.isBlank()) {
            sql += " " + options;
        }
        session.execute(sql);
    }

    /**
     * 种子三行：走 {@code JdbcSession.update} 的<b>绑参</b>通道，与界面保存时打到库上的
     * 那条同一个原语（{@code EditSqlBuilder} 最终也是 {@code PreparedStatement.setObject}）。
     *
     * <p>用绑参而不是拼字面量：SQL Server 的中文要 {@code N'...'} 前缀、Oracle 的
     * {@code ''} 是 NULL、各家日期字面量各一套 —— 那些是<b>夹具</b>的形状问题，
     * 拼字面量会把「导出编码器有没有把中文写对」这件事糊成「夹具自己没拼对」。
     *
     * <p>第 2 行故意留一个 NULL 备注、第 3 行故意在备注里放换行。
     */
    private static void seed(JdbcSession session, Dialect dialect, DialectKind kind, String table)
            throws Exception {
        String sql = "INSERT INTO " + dialect.quote(table) + " ("
                + dialect.quote("id") + ", " + dialect.quote("name") + ", "
                + dialect.quote("amount") + ", " + dialect.quote("active") + ", "
                + dialect.quote("note") + ") VALUES (?, ?, ?, ?, ?)";
        // ⚠ 用 Arrays.asList 而不是 List.of：第 2 行要交一个真的 null，
        //    而 List.of 见到 null 直接 NPE —— 那会让「NULL 那一格」在夹具里就丢掉。
        assertThat(session.update(sql, Arrays.asList(1L, "张三", new BigDecimal("10.50"),
                boolSeed(kind, true), NOTE_WITH_QUOTES)))
                .as("%s：种子第 1 行要落库", dialect.displayName()).isEqualTo(1);
        assertThat(session.update(sql, Arrays.asList(2L, "Al Smith", new BigDecimal("20.25"),
                boolSeed(kind, false), null)))
                .as("%s：种子第 2 行（备注为 NULL）要落库", dialect.displayName()).isEqualTo(1);
        assertThat(session.update(sql, Arrays.asList(3L, "王五", new BigDecimal("0.99"),
                boolSeed(kind, true), NOTE_WITH_NEWLINE)))
                .as("%s：种子第 3 行（备注含换行）要落库", dialect.displayName()).isEqualTo(1);
    }

    /**
     * 布尔种子值的 Java 形状按<b>列的真实类型</b>给：Oracle 那格是 {@code NUMBER(1)}，
     * 递 {@code Boolean} 进去是驱动的事、不是界面的事 —— 界面在 Oracle 上把这格当数字编辑。
     */
    private static Object boolSeed(DialectKind kind, boolean value) {
        return kind == DialectKind.ORACLE ? (value ? 1L : 0L) : value;
    }

    /** 用<b>产品自己的</b> CSV 解码器把文件读成行（契约不重写一遍解析器）。 */
    private static List<List<String>> readCsv(Path file) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (PushbackReader in = new PushbackReader(
                Files.newBufferedReader(file, StandardCharsets.UTF_8), 8)) {
            List<String> row;
            while ((row = CsvCodec.readRow(in, ',')) != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 读回一行：走界面渲染格子用的同一个 {@code JdbcSession} 映射口，不自己建 ResultSet。 */
    private static Map<String, Object> readRow(JdbcSession session, Dialect dialect,
                                               String table, long id) throws Exception {
        List<Map<String, Object>> rows = session.query(
                "SELECT * FROM " + dialect.quote(table) + " WHERE "
                        + dialect.quote("id") + " = ?", 10, List.of(id)).rows();
        assertThat(rows)
                .as("按主键要恰好读到一行（读到 0 行 = 上一步的写根本没落库）")
                .hasSize(1);
        return rows.getFirst();
    }

    private static void expectCatalogNames(TableSchema schema, Dialect dialect) {
        assertThat(schema.columns())
                .as("%s：建表时带引号的小写列名要原样读回来（后面取值全按这个名字）",
                        dialect.displayName())
                .extracting(ColumnDef::name)
                .containsExactly("id", "name", "amount", "active", "note");
    }

    private static List<String> names(TableSchema schema) {
        return schema.columns().stream().map(ColumnDef::name).toList();
    }

    private static String idType(DialectKind kind) {
        return kind == DialectKind.ORACLE ? "NUMBER(19)" : "BIGINT";
    }

    private static String decimalType(DialectKind kind) {
        return kind == DialectKind.ORACLE ? "NUMBER(14,2)" : "DECIMAL(12,2)";
    }

    private static String boolType(DialectKind kind) {
        return switch (kind) {
            case MYSQL, MARIADB -> "TINYINT(1)";
            case POSTGRESQL -> "BOOLEAN";
            case SQLSERVER -> "BIT";
            case ORACLE, SQLITE -> "NUMBER(1)";
        };
    }

    private static String textType(DialectKind kind, int length) {
        return switch (kind) {
            case MYSQL, MARIADB, POSTGRESQL -> "VARCHAR(" + length + ")";
            // SQL Server 必须 NVARCHAR：VARCHAR 走库默认排序规则，中文折成问号。
            case SQLSERVER -> "NVARCHAR(" + length + ")";
            case ORACLE -> "VARCHAR2(" + length + ")";
            case SQLITE -> "TEXT";
        };
    }

    // ───────────────────────── 值形状归一（各家驱动不同，不是缺陷） ─────────────────────────

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(String.valueOf(value));
        }
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * 布尔：各家驱动把同一档 SQL 类型映射成不同 Java 类型（见 {@code boolType}），
     * 这里只把形状折平 —— 值本身仍然来自 {@code JdbcSession} 的映射口。
     */
    private static Boolean asBool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "t", "1", "yes", "y", "on" -> Boolean.TRUE;
            case "false", "f", "0", "no", "n", "off" -> Boolean.FALSE;
            default -> throw new AssertionError("读回来的布尔形状不认识：" + text
                    + "（" + value.getClass().getName() + "）—— 契约要显式认识这一档，别静默折成假");
        };
    }

    private static void dropQuietly(JdbcSession session, Dialect dialect, String table) {
        try {
            session.execute("DROP TABLE " + dialect.quote(table));
        } catch (Exception ignored) {
            // 夹具清理失败不影响结论（不刷假警报，也不吞断言）。
        }
    }
}
