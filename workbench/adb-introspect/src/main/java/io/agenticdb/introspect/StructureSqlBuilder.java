package io.agenticdb.introspect;

import io.agenticdb.jdbc.ColumnTypes;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.Danger;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 结构变更 SQL 构造器。
 *
 * <p>UI 只负责收集用户输入，危险判断、方言差异、SQLite 表重建都集中在这里。
 * 所有方法返回完整的 {@link StructurePlan}，调用方必须先展示再执行。
 */
public final class StructureSqlBuilder {

    /** 表名 / 列名的合法形状。建表、改列、重命名共用一份规则。 */
    private static final Pattern VALID_COLUMN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private StructureSqlBuilder() {
    }

    public static StructurePlan addColumn(DbSession session, TableStructure table,
                                          ColumnSpec column) {
        validateColumn(column);
        Dialect dialect = session.dialect();
        if (dialect.requiresDefaultForNotNullColumn() && !column.nullable()
                && column.defaultValue() == null && !column.primaryKey()) {
            throw new IllegalArgumentException(dialect.displayName()
                    + " 新增 NOT NULL 列必须提供默认值");
        }
        List<String> statements = new ArrayList<>();
        statements.add(dialect.addColumnSql(table.name(), column));
        dialect.appendColumnComment(table.name(), column, statements);
        return new StructurePlan("新增列 " + column.name(), statements,
                Danger.DESTRUCTIVE, false, false);
    }

    public static StructurePlan renameColumn(DbSession session, TableStructure table,
                                             String oldName, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        if (oldName == null || oldName.isBlank()) {
            throw new IllegalArgumentException("请选择要重命名的列");
        }
        // ⭐ 把旧列的定义传下去 —— MySQL 5.7 只能用 CHANGE COLUMN（要带定义）。
        ColumnDef old = table.column(oldName);
        String sql = session.dialect().renameColumnSql(table.name(), oldName,
                newName.trim(), old);
        return new StructurePlan("重命名列 " + oldName + " → " + newName.trim(),
                List.of(sql), Danger.DESTRUCTIVE, false, false);
    }

    public static StructurePlan alterColumn(DbSession session, TableStructure table,
                                            String oldName, ColumnSpec next) {
        validateColumn(next);
        ColumnDef old = table.column(oldName);
        if (old == null) {
            throw new IllegalArgumentException("列不存在: " + oldName);
        }

        Dialect dialect = session.dialect();
        if (dialect.rebuildsTableOnColumnChange()) {
            // SQLite 的 ALTER TABLE 改不了列 —— 整表重建。
            ColumnSpec preserved = new ColumnSpec(next.name(), next.rawType(), next.nullable(),
                    next.defaultValue(), next.comment(), old.unique(), old.primaryKey());
            return rebuildPlan(session, table, oldName, preserved);
        }

        // ⭐ 改列语句由方言生成 —— MySQL 要一条 MODIFY 带完整定义，
        //    PostgreSQL 要拆成 TYPE / NOT NULL / DEFAULT 三条。
        List<String> statements = new ArrayList<>(
                dialect.alterColumnStatements(table.name(), oldName, next, old.rawType()));
        if (next.comment() != null) {
            dialect.appendColumnComment(table.name(), next, statements);
        } else if (old.comment() != null) {
            // ⭐ 注释被清空了。MySQL 的 MODIFY COLUMN 不带 COMMENT 就自动清掉，
            //   但 PostgreSQL 的注释独立于列定义，必须显式 IS NULL —— 否则「清空」无效。
            //   只在原来有注释时下发，避免每次改列都多一条空操作。
            dialect.appendColumnCommentReset(table.name(), next.name(), statements);
        }
        return new StructurePlan("修改列 " + oldName, statements, Danger.DESTRUCTIVE,
                true, false);
    }

    public static StructurePlan dropColumn(DbSession session, TableStructure table,
                                           String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("请选择要删除的列");
        }
        if (table.primaryKeys().stream().anyMatch(key -> key.equalsIgnoreCase(columnName))) {
            throw new IllegalArgumentException("主键列不能直接删除；请先调整主键");
        }
        String sql = session.dialect().dropColumnSql(table.name(), columnName);
        return new StructurePlan("删除列 " + columnName, List.of(sql), Danger.DESTRUCTIVE,
                true, false);
    }

    /**
     * SQLite 修改可空/默认值/类型必须重建表。重建期间关闭外键，复制数据后恢复
     * 索引与触发器；整批语句由 UI 放在同一事务里执行。
     */
    private static StructurePlan rebuildPlan(DbSession session, TableStructure table,
                                              String oldName, ColumnSpec next) {
        List<ColumnDef> columns = new ArrayList<>();
        for (ColumnDef column : table.columns()) {
            if (column.name().equalsIgnoreCase(oldName)) {
                columns.add(toColumnDef(next));
            } else {
                columns.add(column);
            }
        }
        return rebuildWith(session, table, columns, table.primaryKeys(),
                table.foreignKeys(), table.checks(), "重建表以修改列 " + oldName);
    }

    /**
     * ⭐ 整表重建（SQLite 专用路径）。
     *
     * <p>「重建」= 建临时表 → 拷数据 → 删原表 → 改名 → 重建索引/触发器/视图。
     * 凡是**表级对象**变了（列、主键、外键、CHECK）都要走这里 ——
     * 少搬一样，那个东西就会静默消失（D3 就是 CHECK 没搬）。
     */
    private static StructurePlan rebuildWith(DbSession session, TableStructure table,
                                             List<ColumnDef> columns, List<String> primaryKeys,
                                             List<ForeignKeyDef> foreignKeys,
                                             List<CheckDef> checks,
                                             String description) {
        String temporary = "__adb_rebuild_" + table.name() + "_" + System.nanoTime();
        String quote = session.dialect().quote(table.name());
        String temporaryQuote = session.dialect().quote(temporary);

        List<String> statements = new ArrayList<>();
        List<String> views = dependentViews(session, table.name());
        for (String view : views) {
            statements.add("DROP VIEW " + session.dialect().quote(view));
        }
        // ⭐ 重建时**搬运 CHECK 约束** —— 以前这里只带列/主键/UNIQUE/DEFAULT/外键，
        //    于是 SQLite 改一次列，该表所有 CHECK 约束就静默消失了（D3）。
        statements.add(createTableSqlWithChecks(session, temporary, columns,
                primaryKeys, table.indexes(), foreignKeys, checks));
        String columnList = columns.stream()
                .map(column -> session.dialect().quote(column.name()))
                .reduce((a, b) -> a + ", " + b).orElse("");
        statements.add("INSERT INTO " + temporaryQuote + " (" + columnList + ") SELECT "
                + columnList + " FROM " + quote);
        statements.add("DROP TABLE " + quote);
        statements.add("ALTER TABLE " + temporaryQuote + " RENAME TO " + quote);

        Set<String> names = new HashSet<>();
        for (IndexDef index : table.indexes()) {
            if (index.autoCreated() || index.columns().isEmpty() || !names.add(index.name())) {
                continue;
            }
            statements.add(createIndexSql(session, table.name(), index));
        }
        for (TriggerDef trigger : table.triggers()) {
            if (trigger.statement() != null && !trigger.statement().isBlank()) {
                statements.add(trigger.statement().strip().replaceAll(";\\s*$", ""));
            }
        }
        for (String view : views) {
            String sql = viewDdl(session, view);
            if (sql != null && !sql.isBlank()) {
                statements.add(sql.strip().replaceAll(";\\s*$", ""));
            }
        }
        return new StructurePlan(description, statements,
                Danger.DESTRUCTIVE, true, true);
    }

    /** 引用了这张表的视图 —— 只有整表重建需要拆它们。 */
    private static List<String> dependentViews(DbSession session, String table) {
        try {
            return session.dialect().dependentViews(session.connection(), table);
        } catch (java.sql.SQLException | RuntimeException ignored) {
            // 读不到就当作没有依赖视图，让重建走到数据库自己的错误。
            // （RuntimeException 也要兜：假会话的 connection() 是 null。）
            return List.of();
        }
    }

    /** 视图的 DDL。 */
    private static String viewDdl(DbSession session, String view) {
        try {
            return session.dialect().readDdl(session.connection(), view, true);
        } catch (java.sql.SQLException | RuntimeException ignored) {
            return null;
        }
    }

    // ──────────────────────── 表级生命周期（T1–T4） ────────────────────────

    /**
     * 新建表。
     *
     * <p>与列级变更不同：这是一次 <b>纯新增</b>，没有旧数据可丢，所以
     * {@code approvalRequired} 为假 —— 但 SQL 仍然要先给用户看（由调用方的确认流程保证）。
     *
     * @param existingTables 已有的表名，用于拒绝重名（重名会让数据库抛一个看不懂的错）
     * @param tableComment   表注释；方言不支持时静默忽略（UI 会提前禁用输入框）
     */
    public static StructurePlan createTable(DbSession session, String name,
                                            List<ColumnSpec> columns,
                                            String tableComment,
                                            List<String> existingTables) {
        return createTableWith(session, name, columns, tableComment, existingTables,
                List.of(), List.of(), List.of());
    }

    /**
     * ⭐ R30-D：新建表 + <b>随表一起建</b>的索引 / 外键 / 触发器。
     *
     * <p>表设计器的 SQL 预览与真正执行的语句必须出自同一处 —— 设计器里填的索引、
     * 外键、触发器如果建完表再各弹一次确认，用户看到的「一条建表计划」就和实际执行
     * 的 N 次动作对不上了。这里把它们合并成<b>一个</b> {@link StructurePlan}：
     * 一个事务、一次确认、失败整体回滚。
     *
     * <p><b>外键写进 CREATE TABLE</b>，不跟 ALTER TABLE：SQLite 不支持
     * {@code ALTER TABLE … ADD CONSTRAINT}，只能整表重建 —— 对一张还没建出来的表，
     * 「重建」没有意义。三条方言的 CREATE TABLE 都接受表级 {@code FOREIGN KEY} 子句。
     *
     * @param foreignKeys 表级外键约束（可空）
     * @param indexes     建表后追加的索引（可空）
     * @param triggers    建表后追加的触发器（可空）
     */
    public static StructurePlan createTableWith(DbSession session, String name,
                                                List<ColumnSpec> columns,
                                                String tableComment,
                                                List<String> existingTables,
                                                List<ForeignKeyDef> foreignKeys,
                                                List<IndexDef> indexes,
                                                List<TriggerDef> triggers) {
        String problem = tableNameProblem(name, existingTables);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        String tableName = name.trim();
        problem = columnListProblem(columns);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        problem = columnOptionProblem(session.dialect(), columns);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }

        List<String> primaryKeys = columns.stream()
                .filter(ColumnSpec::primaryKey)
                .map(ColumnSpec::name)
                .toList();
        boolean singlePrimary = primaryKeys.size() == 1;

        List<String> lines = new ArrayList<>();
        for (ColumnSpec column : columns) {
            lines.add(session.dialect().createTableColumnDefinition(column,
                    singlePrimary && primaryKeys.getFirst().equalsIgnoreCase(column.name())));
        }
        if (primaryKeys.size() > 1) {
            lines.add("PRIMARY KEY (" + primaryKeys.stream()
                    .map(key -> quoteIdentifier(session, key))
                    .reduce((a, b) -> a + ", " + b).orElse("") + ")");
        }
        appendForeignKeyLines(session, columns, foreignKeys, lines);

        List<String> statements = new ArrayList<>();
        // ⭐ 表级选项（MySQL 的 DEFAULT CHARSET=utf8mb4）—— 不带的话，
        //    MySQL 8.0 之前的服务器默认 latin1，建出来的表存不了中文
        //    （多版本矩阵在 5.7 上实测抓出，见 Dialect#createTableOptions）。
        String options = session.dialect().createTableOptions();
        statements.add("CREATE TABLE " + quoteIdentifier(session, tableName) + " (\n    "
                + String.join(",\n    ", lines) + "\n)"
                + (options == null || options.isBlank() ? "" : " " + options));
        // ⭐ 列注释：MySQL 已经在列定义里、PostgreSQL 要独立的 COMMENT ON ——
        //    由方言决定追加什么（MySQL / SQLite 追加空）。
        for (ColumnSpec column : columns) {
            if (column.comment() != null) {
                session.dialect().appendColumnComment(tableName, column, statements);
            }
        }
        Dialect dialect = session.dialect();
        String comment = tableComment == null || tableComment.isBlank()
                ? null : tableComment.trim();
        if (comment != null && dialect.supportsTableComment()) {
            statements.add(dialect.tableCommentSql(tableName, comment));
        }
        appendIndexStatements(session, tableName, columns, indexes, statements);
        appendTriggerStatements(session, tableName, triggers, statements);
        return new StructurePlan("新建表 " + tableName, statements, Danger.DESTRUCTIVE,
                false, false);
    }

    /** 出错的设计器页签（外键 / 索引 / 触发器）。 */
    public enum DesignStep { FOREIGN_KEY, INDEX, TRIGGER }

    /**
     * 建表计划里「对象」部分的校验失败 —— 异常自带出错的页签，UI 按类型路由。
     *
     * <p>新建表弹窗的规则是「报错落在哪一页，就把用户带到哪一页」。按文案
     * {@code contains("索引")} 猜页签，文案一改（模型层消息迟早双语化）路由就静默
     * 失效 —— 所以把步骤做成类型。表名与列的问题落在「字段」页，用普通
     * {@link IllegalArgumentException} 即可，不走这个类型。
     */
    public static final class DesignStepException extends IllegalArgumentException {

        private final DesignStep step;

        public DesignStepException(DesignStep step, String message) {
            super(message);
            this.step = step;
        }

        public DesignStep step() {
            return step;
        }
    }

    /**
     * 表级外键子句（写进 CREATE TABLE 的那一行）。
     *
     * <p>公开是为了让「外键」弹窗在<b>设计器里</b>能预览真正会执行的那一段 ——
     * 复用 {@code addForeignKey} 会得到 SQLite 的整表重建脚本，对一个还不存在的表
     * 是误导（那时候没有「表」可重建）。
     */
    public static String foreignKeyClause(DbSession session, ForeignKeyDef foreignKey) {
        if (foreignKey == null) {
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "请指定外键");
        }
        if (foreignKey.columns().isEmpty() || foreignKey.referencedColumns().isEmpty()) {
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "外键至少要有一列");
        }
        if (foreignKey.referencedTable().isBlank()) {
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "请选择被引用的表");
        }
        if (foreignKey.columns().size() != foreignKey.referencedColumns().size()) {
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "外键的列数与被引用列的列数必须一致");
        }
        String name = foreignKey.name().isBlank() ? ""
                : "CONSTRAINT " + quoteIdentifier(session, foreignKey.name()) + " ";
        return name + "FOREIGN KEY (" + quoteList(session, foreignKey.columns())
                + ") REFERENCES " + quoteIdentifier(session, foreignKey.referencedTable())
                + " (" + quoteList(session, foreignKey.referencedColumns()) + ")"
                + " ON UPDATE " + foreignKey.onUpdate()
                + " ON DELETE " + foreignKey.onDelete();
    }

    private static void appendForeignKeyLines(DbSession session, List<ColumnSpec> columns,
                                              List<ForeignKeyDef> foreignKeys,
                                              List<String> lines) {
        if (foreignKeys == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (ForeignKeyDef key : foreignKeys) {
            for (String column : key.columns()) {
                if (columns.stream().noneMatch(candidate ->
                        candidate.name().equalsIgnoreCase(column))) {
                    throw new DesignStepException(DesignStep.FOREIGN_KEY, "表里没有这一列: " + column);
                }
            }
            if (!key.name().isBlank() && !names.add(key.name().toLowerCase(Locale.ROOT))) {
                throw new DesignStepException(DesignStep.FOREIGN_KEY,
                        "已经有一个叫「" + key.name() + "」的外键了");
            }
            lines.add(foreignKeyClause(session, key));
        }
    }

    private static void appendIndexStatements(DbSession session, String tableName,
                                              List<ColumnSpec> columns,
                                              List<IndexDef> indexes,
                                              List<String> statements) {
        if (indexes == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (IndexDef index : indexes) {
            requireIndexName(index.name());
            if (index.columns().isEmpty()) {
                throw new DesignStepException(DesignStep.INDEX, "索引「" + index.name() + "」请至少选择一列");
            }
            for (String column : index.columns()) {
                if (columns.stream().noneMatch(candidate ->
                        candidate.name().equalsIgnoreCase(column))) {
                    throw new DesignStepException(DesignStep.INDEX, "索引「" + index.name()
                            + "」引用了表里没有的列: " + column);
                }
            }
            if (!names.add(index.name().toLowerCase(Locale.ROOT))) {
                throw new DesignStepException(DesignStep.INDEX,
                        "已经有一个叫「" + index.name() + "」的索引了");
            }
            statements.add(createIndexSql(session, tableName, index));
        }
    }

    /**
     * 触发器语句（建表后追加 / 结构页新建都走这一份校验）。
     *
     * {@code body} 是 {@link TriggerDef#statement()}：BEFORE/AFTER + INSERT/UPDATE/DELETE + 体。
     */
    private static void appendTriggerStatements(DbSession session, String tableName,
                                                List<TriggerDef> triggers,
                                                List<String> statements) {
        if (triggers == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (TriggerDef trigger : triggers) {
            String triggerName = trigger.name().trim();
            if (triggerName.isEmpty()) {
                throw new DesignStepException(DesignStep.TRIGGER, "触发器名不能为空");
            }
            if (trigger.statement().isBlank()) {
                throw new DesignStepException(DesignStep.TRIGGER, "触发器体不能为空");
            }
            String when = trigger.timing().trim().toUpperCase(Locale.ROOT);
            String what = trigger.event().trim().toUpperCase(Locale.ROOT);
            if (!List.of("BEFORE", "AFTER").contains(when)) {
                throw new DesignStepException(DesignStep.TRIGGER, "触发时机只能是 BEFORE 或 AFTER");
            }
            if (!List.of("INSERT", "UPDATE", "DELETE").contains(what)) {
                throw new DesignStepException(DesignStep.TRIGGER, "触发事件只能是 INSERT / UPDATE / DELETE");
            }
            if (!names.add(triggerName.toLowerCase(Locale.ROOT))) {
                throw new DesignStepException(DesignStep.TRIGGER,
                        "已经有一个叫「" + triggerName + "」的触发器了");
            }
            session.dialect().appendCreateTriggerSql(tableName, triggerName, when, what,
                    trigger.statement().trim(), statements);
        }
    }

    private static String quoteList(DbSession session, List<String> values) {
        return String.join(", ", values.stream()
                .map(value -> quoteIdentifier(session, value)).toList());
    }

    /** 清空表：保留结构，删掉全部行。 */
    public static StructurePlan truncateTable(DbSession session, String name) {
        requireTableName(name);
        String sql = session.dialect().truncateTableSql(name.trim());
        return new StructurePlan("清空表 " + name.trim(), List.of(sql), Danger.DESTRUCTIVE,
                true, false);
    }

    /** 重命名表。 */
    public static StructurePlan renameTable(DbSession session, String oldName,
                                            String newName, List<String> existingTables) {
        requireTableName(oldName);
        String problem = tableNameProblem(newName, existingTables);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        String from = oldName.trim();
        String to = newName.trim();
        if (from.equalsIgnoreCase(to)) {
            throw new IllegalArgumentException("新表名与旧表名相同");
        }
        return new StructurePlan("重命名表 " + from + " → " + to,
                List.of(session.dialect().renameTableSql(from, to)),
                Danger.DESTRUCTIVE, true, false);
    }

    /** 删除表。 */
    public static StructurePlan dropTable(DbSession session, String name) {
        requireTableName(name);
        return new StructurePlan("删除表 " + name.trim(),
                List.of(session.dialect().dropTableSql(name.trim())),
                Danger.DESTRUCTIVE, true, false);
    }

    /**
     * ⭐ N3：批量删除表。
     *
     * <p>确认页要一次看到<b>所有</b>将执行的 DROP，而不是弹 N 次单表确认。
     * 实际执行由 {@code TableDdl} 逐表进行并汇总失败行（SQLite 没有
     * {@code DROP TABLE IF EXISTS ... CASCADE} 那种一把梭的语义）。
     */
    public static StructurePlan dropTables(DbSession session, List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一张表");
        }
        List<String> statements = new ArrayList<>();
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            requireTableName(name);
            String trimmed = name.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                cleaned.add(trimmed);
                statements.add(session.dialect().dropTableSql(trimmed));
            }
        }
        return new StructurePlan("删除 " + cleaned.size() + " 张表", statements,
                Danger.DESTRUCTIVE, true, false);
    }

    /**
     * 表名的校验。返回 {@code null} 表示通过，否则返回给人看的原因。
     *
     * <p>包级可见 + 静态 + 不碰 UI，是为了让单测能直接断言规则。
     * 建表对话框和重命名对话框共用它，避免两处规则慢慢跑偏。
     */
    static String tableNameProblem(String raw, List<String> existingTables) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return "表名不能为空";
        }
        if (!VALID_COLUMN_NAME.matcher(name).matches()) {
            return "表名只能以字母或下划线开头，且只能包含字母、数字、下划线和 $";
        }
        for (String existing : existingTables == null ? List.<String>of() : existingTables) {
            if (existing != null && existing.equalsIgnoreCase(name)) {
                return "表「" + existing + "」已经存在";
            }
        }
        return null;
    }

    /**
     * 自增与列级字符集的校验（R31）。
     *
     * <p>为什么单列一条：自增是**跨列**属性（全表只能一列），
     * 而「这一列能不能自增」只有方言知道 —— 通用校验放这里，方言细则问 {@link Dialect}。
     */
    /**
     * 列级选项的校验：自增、字符集、排序规则。
     *
     * <p>「能不能设」和「设了怎么报错」都交给方言 —— 结构页、建表弹窗、
     * 将来的导入建表走的是同一条判定。
     */
    private static String columnOptionProblem(Dialect dialect, List<ColumnSpec> columns) {
        ColumnSpec auto = null;
        int primaryKeys = 0;
        for (ColumnSpec column : columns) {
            if (column == null) {
                continue;
            }
            if (column.primaryKey()) {
                primaryKeys++;
            }
            if (column.autoIncrement()) {
                if (auto != null) {
                    return "只能有一列自增：已选「" + auto.name() + "」，又勾了「"
                            + column.name() + "」";
                }
                auto = column;
            }
            if (column.charset() != null && !dialect.supportsColumnCharset()) {
                return dialect.displayName() + " 没有列级字符集（编码是建库时定的）："
                        + column.name();
            }
            if (column.collation() != null && !dialect.supportsColumnCollation()) {
                return dialect.displayName() + " 不支持列级排序规则：" + column.name();
            }
            if ((column.charset() != null || column.collation() != null)
                    && !ColumnTypes.isTextual(column.baseType())) {
                // 整数列写 COLLATE 在 PostgreSQL 上直接报错，MySQL 收下也没意义。
                return "字符集 / 排序规则只对文本类型有效：" + column.name() + " 是 "
                        + column.effectiveType();
            }
            String problem = identifierProblem(column.charset(), "字符集", column.name());
            if (problem == null) {
                problem = identifierProblem(column.collation(), "排序规则", column.name());
            }
            if (problem != null) {
                return problem;
            }
        }
        if (auto == null) {
            return null;
        }
        if (!auto.primaryKey()) {
            return "自增列必须是主键：" + auto.name();
        }
        if (dialect.autoIncrementNeedsSinglePrimaryKey() && primaryKeys > 1) {
            return dialect.displayName() + " 的自增列必须是唯一主键：" + auto.name()
                    + "（现在表里有 " + primaryKeys + " 列主键）";
        }
        return dialect.autoIncrementProblem(auto);
    }

    /** 字符集/排序规则是标识符，直接拼进 DDL —— 只许字母数字下划线。 */
    private static String identifierProblem(String value, String label, String columnName) {
        if (value == null || value.matches("[A-Za-z0-9_]+")) {
            return null;
        }
        return label + "只能用字母、数字、下划线：" + columnName + " → " + value;
    }

    /** 建表时整份列定义的校验。 */
    static String columnListProblem(List<ColumnSpec> columns) {
        if (columns == null || columns.isEmpty()) {
            return "至少要有一列";
        }
        Set<String> seen = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null) {
                continue;
            }
            String problem = validateColumnMessage(column);
            if (problem != null) {
                return problem;
            }
            if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                return "列名「" + column.name() + "」重复";
            }
            // ⭐ 三种数据库里主键列都不允许 NULL —— 提前说清楚，
            //    好过让数据库抛一个「Column 'id' cannot be null」。
            if (column.primaryKey() && column.nullable()) {
                return "主键列「" + column.name() + "」不能允许 NULL";
            }
        }
        return null;
    }

    private static void requireTableName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请先在对象树里选中一张表");
        }
    }

    private static String validateColumnMessage(ColumnSpec column) {
        try {
            validateColumn(column);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public static String createTableSql(DbSession session, String name, List<ColumnDef> columns,
                                        List<String> primaryKeys, List<IndexDef> indexes,
                                        List<ForeignKeyDef> foreignKeys) {
        return buildCreateTableSql(session, name, columns, primaryKeys, foreignKeys, List.of());
    }

    /**
     * ⭐ 带 CHECK 约束的建表语句。
     *
     * <p>SQLite 改列要整表重建，重建的 {@code CREATE TABLE} 就是这个方法生成的 ——
     * <b>不把 CHECK 带过去，约束就静默消失了</b>（D3：用户没改错任何东西，
     * 约束却没了，而且没有撤销路径）。
     */
    public static String createTableSqlWithChecks(DbSession session, String name,
                                                  List<ColumnDef> columns,
                                                  List<String> primaryKeys,
                                                  List<IndexDef> indexes,
                                                  List<ForeignKeyDef> foreignKeys,
                                                  List<CheckDef> checks) {
        return buildCreateTableSql(session, name, columns, primaryKeys, foreignKeys, checks);
    }

    private static String buildCreateTableSql(DbSession session, String name,
                                              List<ColumnDef> columns,
                                              List<String> primaryKeys,
                                              List<ForeignKeyDef> foreignKeys,
                                              List<CheckDef> checks) {
        Objects.requireNonNull(name, "name");
        List<String> lines = new ArrayList<>();
        boolean singlePrimary = primaryKeys.size() == 1;
        for (ColumnDef column : columns) {
            String definition = quoteIdentifier(session, column.name()) + " "
                    + defaultRawType(column);
            if (singlePrimary && primaryKeys.getFirst().equalsIgnoreCase(column.name())) {
                definition += " PRIMARY KEY";
            }
            if (!column.nullable()) {
                definition += " NOT NULL";
            }
            if (column.unique() && !column.primaryKey()) {
                definition += " UNIQUE";
            }
            if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
                definition += " DEFAULT " + column.defaultValue();
            }
            lines.add(definition);
        }
        if (primaryKeys.size() > 1) {
            lines.add("PRIMARY KEY (" + primaryKeys.stream()
                    .map(key -> quoteIdentifier(session, key))
                    .reduce((a, b) -> a + ", " + b).orElse("") + ")");
        }
        for (ForeignKeyDef foreignKey : foreignKeys) {
            if (foreignKey.columns().isEmpty() || foreignKey.referencedColumns().isEmpty()) {
                continue;
            }
            String namePart = foreignKey.name().isBlank() ? ""
                    : "CONSTRAINT " + quoteIdentifier(session, foreignKey.name()) + " ";
            lines.add(namePart + "FOREIGN KEY (" + foreignKey.columns().stream()
                    .map(column -> quoteIdentifier(session, column))
                    .reduce((a, b) -> a + ", " + b).orElse("") + ") REFERENCES "
                    + quoteIdentifier(session, foreignKey.referencedTable()) + " ("
                    + foreignKey.referencedColumns().stream()
                    .map(column -> quoteIdentifier(session, column))
                    .reduce((a, b) -> a + ", " + b).orElse("") + ")"
                    + " ON UPDATE " + foreignKey.onUpdate()
                    + " ON DELETE " + foreignKey.onDelete());
        }
        // ⭐ CHECK 约束：整表重建时必须搬运（D3）。
        for (CheckDef check : checks == null ? List.<CheckDef>of() : checks) {
            String expression = check.expression() == null ? "" : check.expression().trim();
            if (expression.isEmpty()) {
                continue;
            }
            String namePart = check.named()
                    ? "CONSTRAINT " + quoteIdentifier(session, check.name()) + " " : "";
            lines.add(namePart + "CHECK (" + expression + ")");
        }
        // ⭐ 表级选项（MySQL 的 DEFAULT CHARSET=utf8mb4）—— 不带的话，
        //    MySQL 8.0 之前的服务器默认 latin1，建出来的表存不了中文
        //    （多版本矩阵在 5.7 上实测抓出，见 Dialect#createTableOptions）。
        String options = session.dialect().createTableOptions();
        return "CREATE TABLE " + quoteIdentifier(session, name) + " (\n    "
                + String.join(",\n    ", lines) + "\n)"
                + (options == null || options.isBlank() ? "" : " " + options);
    }

    // ─────────────────── 外键 / 触发器（B5-S2 / S3）───────────────────

    /**
     * 新增外键。
     *
     * <p>两条路：
     * <ul>
     *   <li><b>PostgreSQL / MySQL</b>：一条 {@code ALTER TABLE … ADD CONSTRAINT}；</li>
     *   <li><b>SQLite</b>：外键是 {@code CREATE TABLE} 的一部分，<b>只能整表重建</b>。</li>
     * </ul>
     */
    public static StructurePlan addForeignKey(DbSession session, TableStructure table,
                                              ForeignKeyDef foreignKey) {
        if (table == null || foreignKey == null) {
            throw new IllegalArgumentException("请指定表和外键");
        }
        if (foreignKey.columns().isEmpty() || foreignKey.referencedColumns().isEmpty()) {
            throw new IllegalArgumentException("外键至少要有一列");
        }
        if (foreignKey.referencedTable().isBlank()) {
            throw new IllegalArgumentException("请选择被引用的表");
        }
        if (foreignKey.columns().size() != foreignKey.referencedColumns().size()) {
            throw new IllegalArgumentException("外键的列数与被引用列的列数必须一致");
        }
        for (String column : foreignKey.columns()) {
            if (table.column(column) == null) {
                throw new IllegalArgumentException("表里没有这一列: " + column);
            }
        }
        for (ForeignKeyDef existing : table.foreignKeys()) {
            if (!existing.name().isBlank() && existing.name().equalsIgnoreCase(foreignKey.name())) {
                throw new IllegalArgumentException("已经有一个叫「" + foreignKey.name() + "」的外键了");
            }
        }
        Dialect dialect = session.dialect();
        if (dialect.rebuildsTableOnForeignKeyChange()) {
            List<ForeignKeyDef> keys = new ArrayList<>(table.foreignKeys());
            keys.add(foreignKey);
            return rebuildWith(session, table, table.columns(), table.primaryKeys(),
                    keys, table.checks(), "重建表以新增外键 " + foreignKey.displayName());
        }
        String sql = dialect.addForeignKeySql(table.name(), foreignKey.name(),
                foreignKey.columns(), foreignKey.referencedTable(),
                foreignKey.referencedColumns(), foreignKey.onUpdate(), foreignKey.onDelete());
        if (sql == null) {
            throw new IllegalArgumentException(dialect.displayName() + " 暂不支持新增外键");
        }
        return new StructurePlan("新增外键 " + foreignKey.displayName(), List.of(sql),
                Danger.DESTRUCTIVE, true, false);
    }

    /**
     * 删除外键。
     *
     * <p>⚠️ SQLite 的外键没有名字（内联 {@code REFERENCES}）时，
     * 只能按「列 + 被引用表」来认；这时 UI 会把显示名给用户看。
     */
    public static StructurePlan dropForeignKey(DbSession session, TableStructure table,
                                               ForeignKeyDef foreignKey) {
        if (table == null || foreignKey == null) {
            throw new IllegalArgumentException("请指定要删除的外键");
        }
        Dialect dialect = session.dialect();
        if (dialect.rebuildsTableOnForeignKeyChange()) {
            List<ForeignKeyDef> keys = new ArrayList<>();
            for (ForeignKeyDef existing : table.foreignKeys()) {
                boolean same = existing == foreignKey
                        || (!existing.name().isBlank() && !foreignKey.name().isBlank()
                        && existing.name().equalsIgnoreCase(foreignKey.name()))
                        || (existing.name().isBlank() && foreignKey.name().isBlank()
                        && existing.columns().equals(foreignKey.columns())
                        && existing.referencedTable().equalsIgnoreCase(foreignKey.referencedTable()));
                if (!same) {
                    keys.add(existing);
                }
            }
            return rebuildWith(session, table, table.columns(), table.primaryKeys(),
                    keys, table.checks(), "重建表以删除外键 " + foreignKey.displayName());
        }
        String sql = dialect.dropForeignKeySql(table.name(), foreignKey.name());
        if (sql == null) {
            throw new IllegalArgumentException(dialect.displayName() + " 暂不支持删除外键");
        }
        return new StructurePlan("删除外键 " + foreignKey.displayName(), List.of(sql),
                Danger.DESTRUCTIVE, true, false);
    }

    /**
     * 新建触发器。
     *
     * @param timing 触发时机（BEFORE / AFTER）
     * @param event  事件（INSERT / UPDATE / DELETE）
     * @param body   触发器体（{@code BEGIN … END} 里的内容）
     */
    public static StructurePlan createTrigger(DbSession session, TableStructure table,
                                               String name, String timing, String event,
                                               String body) {
        if (table == null) {
            throw new IllegalArgumentException("请先选中一张表");
        }
        String triggerName = name == null ? "" : name.trim();
        if (triggerName.isEmpty()) {
            throw new IllegalArgumentException("触发器名不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("触发器体不能为空");
        }
        String when = timing == null ? "BEFORE" : timing.trim().toUpperCase(Locale.ROOT);
        String what = event == null ? "INSERT" : event.trim().toUpperCase(Locale.ROOT);
        if (!List.of("BEFORE", "AFTER").contains(when)) {
            throw new IllegalArgumentException("触发时机只能是 BEFORE 或 AFTER");
        }
        if (!List.of("INSERT", "UPDATE", "DELETE").contains(what)) {
            throw new IllegalArgumentException("触发事件只能是 INSERT / UPDATE / DELETE");
        }
        for (TriggerDef existing : table.triggers()) {
            if (existing.name().equalsIgnoreCase(triggerName)) {
                throw new IllegalArgumentException("已经有一个叫「" + triggerName + "」的触发器了");
            }
        }
        List<String> statements = new ArrayList<>();
        // 形状校验与「建表时带的触发器」共用 appendTriggerStatements —— 两处各写一份
        // 迟早会走出不同规则（例如将来加 INSTEAD OF 时机，只改一处就成了静默漏洞）。
        appendTriggerStatements(session, table.name(),
                List.of(new TriggerDef(triggerName, when, what, body.trim())), statements);
        return new StructurePlan("新建触发器 " + triggerName, statements,
                Danger.DESTRUCTIVE, true, false);
    }

    /** 删除触发器。 */
    public static StructurePlan dropTrigger(DbSession session, TableStructure table,
                                             TriggerDef trigger) {
        if (table == null || trigger == null) {
            throw new IllegalArgumentException("请指定要删除的触发器");
        }
        String sql = session.dialect().dropTriggerSql(table.name(), trigger.name());
        return new StructurePlan("删除触发器 " + trigger.name(), List.of(sql),
                Danger.DESTRUCTIVE, true, false);
    }

    /**
     * ⭐ 设置表注释（B5-S4）。
     *
     * <p>清空时走方言的 {@code clearTableCommentSql} ——
     * PostgreSQL 是 {@code IS NULL}，MySQL 是空串，写错了等于没清。
     */
    public static StructurePlan setTableComment(DbSession session, TableStructure table,
                                                String comment) {
        if (table == null) {
            throw new IllegalArgumentException("请先选中一张表");
        }
        Dialect dialect = session.dialect();
        if (!dialect.supportsTableComment()) {
            throw new IllegalArgumentException(dialect.displayName() + " 不支持表注释");
        }
        String text = comment == null ? "" : comment.trim();
        String sql = text.isEmpty()
                ? dialect.clearTableCommentSql(table.name())
                : dialect.tableCommentSql(table.name(), text);
        return new StructurePlan(text.isEmpty()
                ? "清空表注释 " + table.name()
                : "修改表注释 " + table.name(),
                List.of(sql), Danger.DESTRUCTIVE, false, false);
    }

    /**
     * S1 新建索引。
     *
     * <p>建索引不改数据、也不丢数据，但它会锁表（MySQL 5.6 前尤甚）—— 所以仍然
     * 走「先看 SQL 再执行」的同一条路，只是不需要二次高风险确认。
     */
    public static StructurePlan createIndex(DbSession session, TableStructure table,
                                            IndexDef index) {
        if (table == null || index == null) {
            throw new IllegalArgumentException("请指定表和索引");
        }
        requireIndexName(index.name());
        List<String> columns = index.columns() == null ? List.of() : index.columns();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一列");
        }
        for (String column : columns) {
            if (table.column(column) == null) {
                throw new IllegalArgumentException("表里没有这一列: " + column);
            }
        }
        for (IndexDef existing : table.indexes()) {
            if (existing.name().equalsIgnoreCase(index.name())) {
                throw new IllegalArgumentException("已经有一个叫「" + index.name() + "」的索引了");
            }
        }
        return new StructurePlan("新建索引 " + index.name(),
                List.of(createIndexSql(session, table.name(), index)),
                Danger.DESTRUCTIVE, false, false);
    }

    /**
     * S1 删除索引。
     *
     * <p>⭐ 数据库自动建的索引不能删：SQLite 的 {@code sqlite_autoindex_*} 是主键/
     * 唯一约束的物理实现，MySQL 的 {@code PRIMARY} 同理 —— 真要去掉得改约束本身，
     * 而不是删索引（删了也会在下一次结构变更时被重建，用户会以为工具坏了）。
     */
    public static StructurePlan dropIndex(DbSession session, TableStructure table,
                                          String indexName) {
        if (table == null || indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("请选择要删除的索引");
        }
        IndexDef index = table.indexes().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(indexName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("索引不存在: " + indexName));
        if (index.autoCreated() || index.name().equalsIgnoreCase(
                session.dialect().primaryIndexName())) {
            throw new IllegalArgumentException("「" + index.name()
                    + "」是数据库为约束自动创建的索引 —— 要去掉它请改对应的约束，"
                    + "直接删索引会在下一次结构变更时被重建");
        }
        return new StructurePlan("删除索引 " + index.name(),
                List.of(session.dialect().dropIndexSql(table.name(), index.name())),
                Danger.DESTRUCTIVE, false, false);
    }

    private static void requireIndexName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("索引名不能为空");
        }
        if (!VALID_COLUMN_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("索引名只能包含字母、数字、下划线或 $，且不能以数字开头");
        }
    }

    /**
     * 建索引的 SQL。
     *
     * <p>⭐ 公开是为了让弹窗的「实时预览」和真正执行的语句<b>出自同一处</b> ——
     * 各写一份的话，预览会慢慢变成谎言。
     *
     * <p>类型子句（{@code USING btree}）与列的 {@code DESC} 都来自方言能力：
     * 没选类型 / 方言不支持降序时，这两处都是空串或空操作，
     * 生成的语句与改造前<b>逐字节一致</b>（{@code IndexDdlTest} 钉住了那些字符串）。
     */
    public static String createIndexSql(DbSession session, String tableName, IndexDef index) {
        boolean directionSupported = session.dialect().supportsIndexColumnDirection();
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX "
                + quoteIdentifier(session, index.name()) + " ON "
                + quoteIdentifier(session, tableName)
                + session.dialect().indexTypeClause(index.type()) + " ("
                + index.columns().stream()
                .map(column -> quoteIdentifier(session, column)
                        + (directionSupported && index.isDescending(column) ? " DESC" : ""))
                .reduce((a, b) -> a + ", " + b).orElse("") + ")";
    }

    private static void validateColumn(ColumnSpec column) {
        if (column.name() == null || column.name().isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        if (!VALID_COLUMN_NAME.matcher(column.name()).matches()) {
            throw new IllegalArgumentException("列名只能包含字母、数字、下划线或 $，且不能以数字开头");
        }
    }

    private static String defaultRawType(ColumnDef column) {
        // ⭐ ddlType() 会把长度 / 精度补上（MySQL 的 JDBC 类型名不带长度）。
        return column.ddlType();
    }

    private static ColumnDef toColumnDef(ColumnSpec column) {
        return new ColumnDef(column.name(), ColumnType.OTHER, column.nullable(),
                column.primaryKey(), column.defaultValue(), column.comment(), null,
                null, null, null, column.unique(), column.effectiveType());
    }

    private static String quoteIdentifier(DbSession session, String value) {
        return session.dialect().quote(value);
    }
}
