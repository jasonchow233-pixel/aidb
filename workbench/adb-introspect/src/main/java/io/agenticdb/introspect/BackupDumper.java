package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.TableExporter;

import java.io.IOException;
import java.io.Writer;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ⭐ P2 Backup（纯 JDBC 转储）：把一组表与视图导出成单个可重放的 {@code .sql} 文件。
 *
 * <p>每表一段：{@code CREATE TABLE}（来自 {@link StructureSqlBuilder}）+ 数据
 * {@code INSERT}（流式，来自 {@link TableExporter} 的 SQL 模式）。两段都由同一方言
 * 负责引号与类型，所以生成的文件可以由 {@link io.agenticdb.transfer.SqlCodec}
 * 逐语句读回 —— 导出侧与恢复侧互逆。
 *
 * <p><b>视图排在全部基表之后</b>：视图定义可能引用任意一张表，先建表才有可重放的依赖顺序。
 * 视图没有数据行，只写库里保存的 DDL 原文（{@link SchemaService#structure} 的
 * {@code ddl()}，由方言的 {@code readDdl(view=true)} 提供）。⚠️ 这里必须校验 DDL 的
 * <b>形态</b>：某些方言拿不到视图原文时 {@code ddl()} 会退化成
 * {@code StructureSqlBuilder.createTableSql} —— 那是一句 {@code CREATE TABLE}，
 * 原样写进 dump 会把视图重建成一张空表，比不写更糟。认不出视图 DDL 时写一条
 * 明确的跳过注释，不假装备份过它。
 *
 * <p>恢复侧在 {@link BackupRestorer}：本类只负责「把库当前状态落盘」，重导由
 * 恢复器按顶层分号切分后逐条执行。两件事分开，落盘路径才能被单测完全覆盖、不碰真实写库。
 */
public final class BackupDumper {

    /**
     * 认「这是一句 CREATE VIEW」而不是「字符串里出现了 VIEW」。
     * MySQL 的 {@code CREATE ALGORITHM=... DEFINER=... SQL SECURITY ... VIEW}、
     * PostgreSQL 的 {@code CREATE OR REPLACE VIEW} / {@code CREATE MATERIALIZED VIEW}
     * 都要认得；{@code CREATE TABLE v (... view ...)} 必须拒绝。
     */
    private static final Pattern VIEW_DDL = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?"
                    + "(?:ALGORITHM\\s*=\\s*\\w+\\s+)?(?:DEFINER\\s*=\\s*\\S+\\s+)?"
                    + "(?:SQL\\s+SECURITY\\s+\\w+\\s+)?VIEW\\b");

    private BackupDumper() {
    }

    /** 转储整个 schema：所有基表 + 所有视图（视图恒排在最后）。 */
    public static long dump(DbSession session, Writer out) throws SQLException, IOException {
        SchemaService schema = new SchemaService();
        List<String> tables = new ArrayList<>();
        List<String> views = new ArrayList<>();
        for (SchemaService.SchemaObject object : schema.listObjects(session)) {
            if (object.kind() == SchemaService.ObjectKind.TABLE) {
                tables.add(object.name());
            } else if (object.kind() == SchemaService.ObjectKind.VIEW) {
                views.add(object.name());
            }
        }
        return dump(session, tables, views, out);
    }

    /**
     * 转储指定的对象（顺序即文件中的顺序，视图仍会统一挪到基表之后）。
     *
     * <p>传进来的名字里混了视图是合法的：按内省结果分流，不需要调用方先判类型。
     *
     * @param tables 表名/视图名列表；空列表 = 只写文件头
     * @return 实际写出的数据行数（只数基表，视图没有数据行）
     */
    public static long dump(DbSession session, List<String> tables, Writer out)
            throws SQLException, IOException {
        SchemaService schema = new SchemaService();
        Set<String> knownViews = schema.listObjects(session).stream()
                .filter(object -> object.kind() == SchemaService.ObjectKind.VIEW)
                .map(SchemaService.SchemaObject::name)
                .collect(Collectors.toSet());
        List<String> baseTables = new ArrayList<>();
        List<String> selectedViews = new ArrayList<>();
        for (String name : tables) {
            if (knownViews.contains(name)) {
                selectedViews.add(name);
            } else {
                baseTables.add(name);
            }
        }
        return dump(session, baseTables, selectedViews, out);
    }

    private static long dump(DbSession session, List<String> tables, List<String> views,
                             Writer out) throws SQLException, IOException {
        SchemaService schema = new SchemaService();

        out.write("-- AIDB backup\n");
        out.write("-- dialect: " + session.dialect().displayName() + "\n");
        out.write("-- generated: "
                + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC))
                + "\n\n");

        long total = 0;
        for (String table : tables) {
            TableStructure structure = schema.structure(session, table);
            out.write("-- table: " + table + "\n");
            // ⭐ createTableSql 的第 5 参是 List<IndexDef>，不是外键 —— 要带 CHECK
            //    约束就得走 WithChecks 变体，把 indexes / foreignKeys / checks 都给全。
            out.write(StructureSqlBuilder.createTableSqlWithChecks(session, table,
                    structure.columns(), structure.primaryKeys(), structure.indexes(),
                    structure.foreignKeys(), structure.checks()));
            out.write(";\n\n");

            long rows = TableExporter.export(session, table, null,
                    ExportOptions.of(ExportFormat.SQL), out);
            total += rows;
            out.write("\n");
        }

        // 视图没有 INSERT，只有一句 DDL；放在最后，依赖的表与数据都已就位。
        for (String view : views) {
            writeView(schema, session, view, out);
        }
        out.flush();
        return total;
    }

    private static void writeView(SchemaService schema, DbSession session, String view,
                                  Writer out) throws IOException {
        out.write("-- view: " + view + "\n");
        String ddl = "";
        try {
            ddl = schema.structure(session, view).ddl();
        } catch (SQLException | RuntimeException ignored) {
            // 一颗视图读不出来不该毁掉整份备份：下面写明确的跳过注释，基表照常落盘。
            ddl = "";
        }
        if (!looksLikeViewDdl(ddl)) {
            out.write("-- AIDB: view DDL unavailable for this dialect; skipped.\n");
            out.write("--        recreate it from its defining query before restoring data.\n\n");
            return;
        }
        String statement = ddl.strip();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).stripTrailing();
        }
        out.write(statement);
        out.write(";\n\n");
    }

    /** 包级可见，供门禁直接验证「不会把 CREATE TABLE 当视图 DDL」。 */
    static boolean looksLikeViewDdl(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return false;
        }
        // 先去掉注释：库返回的 DDL 有时带一段说明，里面提到 CREATE VIEW 不算语句。
        String code = Pattern.compile("(?m)^\\s*--.*$").matcher(ddl).replaceAll("");
        code = Pattern.compile("(?s)/\\*.*?\\*/").matcher(code).replaceAll("");
        return VIEW_DDL.matcher(code).find();
    }
}
