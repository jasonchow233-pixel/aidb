package io.agenticdb.introspect;

import io.agenticdb.jdbc.ColumnProblem;
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
            throw new ColumnProblem("structure.error.needDefaultForNotNull",
                    dialect.displayName());
        }
        // ⭐ D-94：主键是表级约束，一张表只能有一颗。以前这里对 primaryKey() 视而不见，
        //    生成的 ADD 语句把主键悄悄丢掉，界面照样报「已保存」—— 比丢约束更坏的是
        //    用户以为约束已经在了。
        if (column.primaryKey() && !table.primaryKeys().isEmpty()) {
            throw new ColumnProblem("column.error.primaryKeyExists", table.name());
        }
        // ⭐ D-94：SQLite 的 ADD COLUMN 本身不接受主键列 —— 整表重建是唯一诚实的
        //    落地方式。自增主键列不在此重建：自增的校验与拒绝由方言负责（落空后
        //    一定会走到 addColumnSql 的现有拒绝）。
        if (column.primaryKey() && dialect.rebuildsTableOnColumnChange()
                && !column.autoIncrement()) {
            return addColumnByRebuild(session, table, column);
        }
        List<String> statements = new ArrayList<>();
        statements.add(dialect.addColumnSql(table.name(), column));
        dialect.appendColumnComment(table.name(), column, statements);
        return new StructurePlan("plan.title.addColumn", List.of(column.name()), statements,
                Danger.DESTRUCTIVE, false, false);
    }

    /**
     * ⭐ D-94：SQLite 加主键列 = 整表重建（ADD COLUMN 拒绝主键列，没有第三条路）。
     *
     * <p>拷数据的 INSERT 只带<b>旧列</b> —— 新列的值交给它的默认值 / NULL 填；
     * 把新列名带进 SELECT 会直接报「没有这个列」。空表随便加；非空表 +
     * 「无默认值的主键」会在拷数据时被 NOT NULL 拦下，那是库自己的诚实语义。
     */
    private static StructurePlan addColumnByRebuild(DbSession session, TableStructure table,
                                                    ColumnSpec column) {
        List<ColumnDef> columns = new ArrayList<>(table.columns());
        columns.add(toColumnDef(column));
        List<String> primaryKeys = new ArrayList<>(table.primaryKeys());
        primaryKeys.add(column.name());
        return rebuildWith(session, table, columns, primaryKeys, table.foreignKeys(),
                table.checks(), "plan.title.rebuildForAddPrimaryKey",
                List.copyOf(table.columns()), column.name());
    }

    public static StructurePlan renameColumn(DbSession session, TableStructure table,
                                             String oldName, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new ColumnProblem("column.error.nameEmpty");
        }
        if (oldName == null || oldName.isBlank()) {
            throw new ColumnProblem("column.error.pickToRename");
        }
        // ⭐ 把旧列的定义传下去 —— MySQL 5.7 只能用 CHANGE COLUMN（要带定义）。
        ColumnDef old = table.column(oldName);
        String sql = session.dialect().renameColumnSql(table.name(), oldName,
                newName.trim(), old);
        return new StructurePlan("plan.title.renameColumn",
                List.of(oldName, newName.trim()),
                List.of(sql), Danger.DESTRUCTIVE, false, false);
    }

    public static StructurePlan alterColumn(DbSession session, TableStructure table,
                                            String oldName, ColumnSpec next) {
        validateColumn(next);
        ColumnDef old = table.column(oldName);
        if (old == null) {
            throw new ColumnProblem("column.error.notFound", oldName);
        }

        Dialect dialect = session.dialect();
        // ⭐ D-94：把已有列提升为主键同理 —— 表里已有主键时提前说人话，
        //    别让计划里躺着一条必炸的 ADD PRIMARY KEY。
        if (next.primaryKey() && !old.primaryKey() && !table.primaryKeys().isEmpty()) {
            throw new ColumnProblem("column.error.primaryKeyExists", table.name());
        }
        if (dialect.rebuildsTableOnColumnChange()) {
            // SQLite 的 ALTER TABLE 改不了列 —— 整表重建。
            //
            // ⭐ D-71：键按**用户这次选的**走，不拿 old 的。重建语句本来就会重写整张表的
            // 列定义，所以「把 email 改成唯一」在这里是能落地的；以前这里固定回填
            // old.unique() / old.primaryKey()，等于结构页的 Key 格子点了等于没点 ——
            // 弹窗照样报「已保存」，库里一个约束都没变。
            ColumnSpec preserved = new ColumnSpec(next.name(), next.rawType(), next.nullable(),
                    next.defaultValue(), next.comment(), next.unique(), next.primaryKey());
            return rebuildPlan(session, table, oldName, preserved);
        }

        // ⭐ 改列语句由方言生成 —— MySQL 要一条 MODIFY 带完整定义，
        //    PostgreSQL 要拆成 TYPE / NOT NULL / DEFAULT 三条。
        List<String> statements = new ArrayList<>(
                dialect.alterColumnStatements(table.name(), oldName, next, old.rawType()));
        // ⭐ D-94：主键是表级约束 —— Key 新选了主键时补一条表级 ADD PRIMARY KEY。
        //    SQLite 走不到这里（上面的重建分支把主键写进 CREATE TABLE）。
        if (next.primaryKey() && !old.primaryKey()) {
            statements.add(dialect.addPrimaryKeySql(table.name(), next.name()));
        }
        if (next.comment() != null) {
            dialect.appendColumnComment(table.name(), next, statements);
        } else if (old.comment() != null) {
            // ⭐ 注释被清空了。MySQL 的 MODIFY COLUMN 不带 COMMENT 就自动清掉，
            //   但 PostgreSQL 的注释独立于列定义，必须显式 IS NULL —— 否则「清空」无效。
            //   只在原来有注释时下发，避免每次改列都多一条空操作。
            dialect.appendColumnCommentReset(table.name(), next.name(), statements);
        }
        return new StructurePlan("plan.title.alterColumn", List.of(oldName), statements,
                Danger.DESTRUCTIVE, true, false);
    }

    public static StructurePlan dropColumn(DbSession session, TableStructure table,
                                           String columnName) {
        if (columnName == null || columnName.isBlank()) {
            throw new ColumnProblem("column.error.pickToDrop");
        }
        if (table.primaryKeys().stream().anyMatch(key -> key.equalsIgnoreCase(columnName))) {
            throw new ColumnProblem("column.error.primaryKeyFirst");
        }
        String sql = session.dialect().dropColumnSql(table.name(), columnName);
        return new StructurePlan("plan.title.dropColumn", List.of(columnName), List.of(sql),
                Danger.DESTRUCTIVE, true, false);
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
                table.foreignKeys(), table.checks(), "plan.title.rebuildForColumn",
                columns, oldName);
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
                                             String titleKey, List<ColumnDef> copyColumns,
                                             Object... titleArgs) {
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
        // ⭐ D-94：INSERT 按 copyColumns 拷数据。改列时 copyColumns == columns
        //    （新列与旧列同名，源表里都在）；加主键列时 copyColumns 是旧列 ——
        //    新列还不在源表里，带进 SELECT 就是「没有这个列」。
        String copyList = copyColumns.stream()
                .map(column -> session.dialect().quote(column.name()))
                .reduce((a, b) -> a + ", " + b).orElse("");
        statements.add("INSERT INTO " + temporaryQuote + " (" + copyList + ") SELECT "
                + copyList + " FROM " + quote);
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
        return new StructurePlan(titleKey, List.of(titleArgs), statements,
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
        ColumnProblem problem = tableNameProblem(name, existingTables);
        if (problem != null) {
            throw problem;
        }
        String tableName = name.trim();
        problem = columnListProblem(columns);
        if (problem != null) {
            throw problem;
        }
        // N29 的新列属性先判：历史实现里它们就在逐列循环的前段，
        // 放到自增跨列校验之后会改变同一份输入的报错顺序（测试与用户都依赖先看到它）。
        ColumnProblem advanced = advancedColumnProblem(session.dialect(), columns);
        if (advanced != null) {
            throw advanced;
        }
        problem = columnOptionProblem(session.dialect(), columns);
        if (problem != null) {
            throw problem;
        }

        List<String> primaryKeys = columns.stream()
                .filter(ColumnSpec::primaryKey)
                .map(ColumnSpec::name)
                .toList();
        // ⭐ N29：有列带「键长」时，主键 / 唯一键不能再写成列内联形式 ——
        //    前缀长度只能写在索引的 key_part 里（`PRIMARY KEY (email(20))`），
        //    内联的 `email VARCHAR(100) PRIMARY KEY` 表达不了。
        boolean prefixedKeys = columns.stream().anyMatch(column -> column.keyLength() != null);
        boolean singlePrimary = primaryKeys.size() == 1 && !prefixedKeys;

        List<String> lines = new ArrayList<>();
        for (ColumnSpec column : columns) {
            ColumnSpec definition = column.keyLength() == null
                    ? column : column.withoutInlineUnique();
            lines.add(session.dialect().createTableColumnDefinition(definition,
                    singlePrimary && primaryKeys.getFirst().equalsIgnoreCase(column.name())));
        }
        if (prefixedKeys && !primaryKeys.isEmpty()) {
            lines.add("PRIMARY KEY (" + keyParts(session, columns, primaryKeys) + ")");
        } else if (primaryKeys.size() > 1) {
            lines.add("PRIMARY KEY (" + primaryKeys.stream()
                    .map(key -> quoteIdentifier(session, key))
                    .reduce((a, b) -> a + ", " + b).orElse("") + ")");
        }
        if (prefixedKeys) {
            for (ColumnSpec column : columns) {
                if (column.unique() && !column.primaryKey() && column.keyLength() != null) {
                    lines.add("UNIQUE (" + quoteIdentifier(session, column.name())
                            + "(" + column.keyLength() + "))");
                }
            }
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
        return new StructurePlan("plan.title.createTable", List.of(tableName), statements,
                Danger.DESTRUCTIVE, false, false);
    }

    /** 出错的设计器页签（外键 / 索引 / 触发器）。 */
    public enum DesignStep { FOREIGN_KEY, INDEX, TRIGGER }

    /**
     * 建表计划里「对象」部分的校验失败 —— 异常自带出错的页签，UI 按类型路由。
     *
     * <p>新建表弹窗的规则是「报错落在哪一页，就把用户带到哪一页」。按文案
     * {@code contains("索引")} 猜页签，文案一改（模型层消息迟早双语化）路由就静默
     * 失效 —— 所以把步骤做成类型。表名与列的问题落在「字段」页，用普通
     * {@link ColumnProblem} 即可，不走这个类型。
     *
     * <p>R45 G3-a：文案换成「键 + 参数」，所以它直接继承 {@link ColumnProblem} ——
     * 页签路由照旧按类型，界面渲染照旧走 {@code I18n.t(key, args)}。
     */
    public static final class DesignStepException extends ColumnProblem {

        private final DesignStep step;

        public DesignStepException(DesignStep step, String key, Object... args) {
            super(key, args);
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
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "fk.error.missing");
        }
        if (foreignKey.columns().isEmpty() || foreignKey.referencedColumns().isEmpty()) {
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "fk.error.needColumn");
        }
        if (foreignKey.referencedTable().isBlank()) {
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "fk.error.needRefTable");
        }
        if (foreignKey.columns().size() != foreignKey.referencedColumns().size()) {
            throw new DesignStepException(DesignStep.FOREIGN_KEY, "fk.error.columnCount");
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
                    throw new DesignStepException(DesignStep.FOREIGN_KEY,
                            "column.error.notInTable", column);
                }
            }
            if (!key.name().isBlank() && !names.add(key.name().toLowerCase(Locale.ROOT))) {
                throw new DesignStepException(DesignStep.FOREIGN_KEY,
                        "fk.error.duplicate", key.name());
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
                throw new DesignStepException(DesignStep.INDEX,
                        "index.error.needColumns", index.name());
            }
            for (String column : index.columns()) {
                if (columns.stream().noneMatch(candidate ->
                        candidate.name().equalsIgnoreCase(column))) {
                    throw new DesignStepException(DesignStep.INDEX,
                            "index.error.columnNotFound", index.name(), column);
                }
            }
            if (!names.add(index.name().toLowerCase(Locale.ROOT))) {
                throw new DesignStepException(DesignStep.INDEX,
                        "index.error.duplicate", index.name());
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
                throw new DesignStepException(DesignStep.TRIGGER, "trigger.error.nameEmpty");
            }
            if (trigger.statement().isBlank()) {
                throw new DesignStepException(DesignStep.TRIGGER, "trigger.error.bodyEmpty");
            }
            String when = trigger.timing().trim().toUpperCase(Locale.ROOT);
            String what = trigger.event().trim().toUpperCase(Locale.ROOT);
            if (!List.of("BEFORE", "AFTER").contains(when)) {
                throw new DesignStepException(DesignStep.TRIGGER, "trigger.error.timing");
            }
            if (!List.of("INSERT", "UPDATE", "DELETE").contains(what)) {
                throw new DesignStepException(DesignStep.TRIGGER, "trigger.error.event");
            }
            if (!names.add(triggerName.toLowerCase(Locale.ROOT))) {
                throw new DesignStepException(DesignStep.TRIGGER,
                        "trigger.error.duplicate", triggerName);
            }
            session.dialect().appendCreateTriggerSql(tableName, triggerName, when, what,
                    trigger.statement().trim(), statements);
        }
    }

    private static String quoteList(DbSession session, List<String> values) {
        return String.join(", ", values.stream()
                .map(value -> quoteIdentifier(session, value)).toList());
    }

    /** ⭐ N29：带前缀长度的主键 key_part 列表（{@code `a`, `b`(20)}）。 */
    private static String keyParts(DbSession session, List<ColumnSpec> columns,
                                   List<String> names) {
        StringBuilder parts = new StringBuilder();
        for (String name : names) {
            ColumnSpec column = columns.stream()
                    .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            String prefix = column == null || column.keyLength() == null
                    ? "" : "(" + column.keyLength() + ")";
            if (!parts.isEmpty()) {
                parts.append(", ");
            }
            parts.append(quoteIdentifier(session, name)).append(prefix);
        }
        return parts.toString();
    }

    /** 清空表：保留结构，删掉全部行。 */
    public static StructurePlan truncateTable(DbSession session, String name) {
        requireTableName(name);
        String sql = session.dialect().truncateTableSql(name.trim());
        return new StructurePlan("plan.title.truncate", List.of(name.trim()), List.of(sql),
                Danger.DESTRUCTIVE, true, false);
    }

    /** 重命名表。 */
    public static StructurePlan renameTable(DbSession session, String oldName,
                                            String newName, List<String> existingTables) {
        requireTableName(oldName);
        ColumnProblem problem = tableNameProblem(newName, existingTables);
        if (problem != null) {
            throw problem;
        }
        String from = oldName.trim();
        String to = newName.trim();
        if (from.equalsIgnoreCase(to)) {
            throw new ColumnProblem("table.error.sameName");
        }
        return new StructurePlan("plan.title.renameTable", List.of(from, to),
                List.of(session.dialect().renameTableSql(from, to)),
                Danger.DESTRUCTIVE, true, false);
    }

    /** 删除表。 */
    public static StructurePlan dropTable(DbSession session, String name) {
        requireTableName(name);
        return new StructurePlan("plan.title.dropTable", List.of(name.trim()),
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
            throw new ColumnProblem("table.error.pickAtLeastOne");
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
        return new StructurePlan("plan.title.dropTables", List.of(cleaned.size()), statements,
                Danger.DESTRUCTIVE, true, false);
    }

    // ─────────────────── ⭐ R42 N28-a 视图生命周期 ───────────────────
    //
    // 与表级那四条同构，但**不返回 StructurePlan**：计划标题是给人看的句子，
    // 写在模型层就会把 I18nAuditTest 的模型层棘轮（现在正好卡在 290，零余量）
    // 再往上顶。所以这里只交 SQL 语句，标题由界面用「键 + 参数」渲染（D-9 的方向）。

    /** 查询体允许的开头：一条 SELECT、一条 WITH…SELECT，或以括号起头的组合查询。 */
    private static final Set<String> VIEW_BODY_HEADS = Set.of("SELECT", "WITH", "VALUES");

    /**
     * 视图名的校验。返回 {@code null} = 通过。
     *
     * <p>形状规则和表名共用一条（{@code VALID_COLUMN_NAME}）：视图名和表名在
     * 六种方言里都在**同一个命名空间**，所以 {@code taken} 要同时传当前库的
     * 表名与视图名 —— 只对照视图会让「和表撞名」到执行时才炸。
     */
    public static ColumnProblem viewNameProblem(String raw, List<String> taken) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return new ColumnProblem("view.error.emptyName");
        }
        if (!VALID_COLUMN_NAME.matcher(name).matches()) {
            return new ColumnProblem("view.error.nameShape");
        }
        for (String existing : taken == null ? List.<String>of() : taken) {
            if (existing != null && existing.equalsIgnoreCase(name)) {
                return new ColumnProblem("view.error.nameTaken", existing);
            }
        }
        return null;
    }

    /**
     * 查询体的校验；返回归一化后的正文（去掉尾分号与首尾空白）。
     *
     * <p>为什么要拦「两句」：视图定义里塞 {@code DROP TABLE} 这种事，
     * 只要 UI 把正文原样拼进 {@code CREATE VIEW … AS <正文>}，
     * 一句 DDL 就变成两句 —— 执行器按整批事务跑，用户以为只建了个视图。
     * 与其信「驱动会拦住」，不如在这儿把形态钉死。
     */
    public static ColumnProblem viewSelectProblem(String raw) {
        try {
            normalizeSelectBody(raw);
            return null;
        } catch (ColumnProblem problem) {
            return problem;
        }
    }

    /**
     * 归一化查询体；形态不对就抛 {@link ColumnProblem}（键 + 参数，由界面渲染）。
     *
     * <p>校验与拼装必须共用这一条：预览里看到的正文和真正执行的正文是同一份文本。
     */
    public static String normalizeSelectBody(String raw) {
        String body = raw == null ? "" : raw.trim();
        // MySQL 的 SHOW CREATE VIEW 会在正文尾巴上挂一个 /*!*/; 结束标记。
        if (body.endsWith("/*!*/;")) {
            body = body.substring(0, body.length() - "/*!*/;".length()).trim();
        }
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }
        if (body.isEmpty()) {
            throw new ColumnProblem("view.error.emptySelect");
        }
        if (body.contains(";")) {
            throw new ColumnProblem("view.error.multiStatement");
        }
        String head = leadingWord(body);
        if ("CREATE".equals(head) || "ALTER".equals(head) || "DROP".equals(head)) {
            // 用户把整条 DDL 粘进来了 —— 这里只收查询体。
            throw new ColumnProblem("view.error.fullDdl");
        }
        if (!VIEW_BODY_HEADS.contains(head) && !body.startsWith("(")) {
            throw new ColumnProblem("view.error.notSelect", head.isEmpty() ? body : head);
        }
        return body;
    }

    /** 正文的第一个词（大写，去掉左括号），用来判「这是不是一条查询」。 */
    private static String leadingWord(String body) {
        String trimmed = body.replaceFirst("^[(\\s]+", "");
        int cursor = 0;
        while (cursor < trimmed.length()
                && (Character.isLetterOrDigit(trimmed.charAt(cursor)) || trimmed.charAt(cursor) == '_')) {
            cursor++;
        }
        return trimmed.substring(0, cursor).toUpperCase(Locale.ROOT);
    }

    /**
     * 建 / 重建视图的语句列表。
     *
     * <p>{@code existing} 为真时走方言的重建写法：MySQL / MariaDB / PostgreSQL / Oracle 一句
     * {@code CREATE OR REPLACE}，SQL Server {@code CREATE OR ALTER}，
     * 只有 SQLite 只能两句「先删再建」（见 {@link io.agenticdb.jdbc.ViewReplace}）。
     * ⚠️ 这组取值以 R52 五引擎容器实测为准；MySQL 曾被当成「没有 OR REPLACE」的
     * 那一侧（D-29），代价是界面在本来单句就够的服务器上多要一道危险确认。
     * 预览与执行都必须调这一个方法 —— 用户在预览里看到几句，库里就跑几句。
     */
    public static List<String> viewStatements(DbSession session, String name, String rawBody,
                                              boolean existing) {
        requireViewName(name);
        String body = normalizeSelectBody(rawBody);
        String trimmed = name.trim();
        if (!existing) {
            return List.of(session.dialect().createViewSql(trimmed, body));
        }
        String single = session.dialect().createOrReplaceViewSql(trimmed, body);
        return single == null
                ? List.of(session.dialect().dropViewSql(trimmed),
                        session.dialect().createViewSql(trimmed, body))
                : List.of(single);
    }

    /** 删视图的语句列表。 */
    public static List<String> dropViewStatements(DbSession session, String name) {
        requireViewName(name);
        return List.of(session.dialect().dropViewSql(name.trim()));
    }

    /**
     * 视图名的形状校验（不查重名）。
     *
     * <p>重名留给调用方判：设计器里「这个名字已经有视图」不是错误，
     * 而是「这次是重建」的开关；只有新建对话框才把它当错误挡住。
     */
    private static void requireViewName(String name) {
        ColumnProblem problem = viewNameProblem(name, List.of());
        if (problem != null) {
            throw problem;
        }
    }

    /**
     * 表名的校验。返回 {@code null} 表示通过，否则返回「键 + 参数」。
     *
     * <p>包级可见 + 静态 + 不碰 UI，是为了让单测能直接断言规则。
     * 建表对话框和重命名对话框共用它，避免两处规则慢慢跑偏。
     */
    static ColumnProblem tableNameProblem(String raw, List<String> existingTables) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return new ColumnProblem("table.error.nameEmpty");
        }
        if (!VALID_COLUMN_NAME.matcher(name).matches()) {
            return new ColumnProblem("table.error.nameShape");
        }
        for (String existing : existingTables == null ? List.<String>of() : existingTables) {
            if (existing != null && existing.equalsIgnoreCase(name)) {
                return new ColumnProblem("table.error.exists", existing);
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
    private static ColumnProblem columnOptionProblem(Dialect dialect, List<ColumnSpec> columns) {
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
                    return new ColumnProblem("column.error.autoIncrementConflict",
                            auto.name(), column.name());
                }
                auto = column;
            }
            if (column.charset() != null && !dialect.supportsColumnCharset()) {
                return new ColumnProblem("column.error.charsetUnsupported",
                        dialect.displayName(), column.name());
            }
            if (column.collation() != null && !dialect.supportsColumnCollation()) {
                return new ColumnProblem("column.error.collationUnsupported",
                        dialect.displayName(), column.name());
            }
            if ((column.charset() != null || column.collation() != null)
                    && !ColumnTypes.isTextual(column.baseType())) {
                // 整数列写 COLLATE 在 PostgreSQL 上直接报错，MySQL 收下也没意义。
                return new ColumnProblem("column.error.charsetTextOnly",
                        column.name(), column.effectiveType());
            }
            ColumnProblem problem = identifierProblem(column.charset(),
                    "column.error.charsetShape", column.name());
            if (problem == null) {
                problem = identifierProblem(column.collation(),
                        "column.error.collationShape", column.name());
            }
            if (problem != null) {
                return problem;
            }
        }
        if (auto == null) {
            return null;
        }
        if (!auto.primaryKey()) {
            return new ColumnProblem("column.error.autoNeedsPrimaryKey", auto.name());
        }
        if (dialect.autoIncrementNeedsSinglePrimaryKey() && primaryKeys > 1) {
            return new ColumnProblem("column.error.autoNeedsSingleKey",
                    dialect.displayName(), auto.name(), primaryKeys);
        }
        return dialect.autoIncrementProblem(auto);
    }

    /**
     * ⭐ N29：索引前缀长度（{@code PRIMARY KEY (email(20))}）。
     *
     * <p>只有 MySQL 认这种东西；而且它只对字符串列有意义，还必须挂在一个键上 ——
     * 三个条件任一不满足都在这里说清，而不是让库抛一句
     * {@code Incorrect prefix key}。
     */
    /**
     * ⭐ N29：新增列属性的校验。
     *
     * <p>返回 {@link ColumnProblem}（键 + 参数）而不是拼好的中文：模型层的可展示文案
     * 由界面层用 {@code I18n.t} 渲染，`I18nAuditTest` 的模型层棘轮钉住这条边界。
     */
    private static ColumnProblem advancedColumnProblem(Dialect dialect, List<ColumnSpec> columns) {
        for (ColumnSpec column : columns) {
            if (column == null) {
                continue;
            }
            ColumnProblem problem = keyLengthProblem(dialect, column);
            if (problem == null) {
                problem = binaryProblem(dialect, column);
            }
            if (problem == null) {
                problem = generatedProblem(dialect, column);
            }
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static ColumnProblem keyLengthProblem(Dialect dialect, ColumnSpec column) {
        String length = column.keyLength();
        if (length == null) {
            return null;
        }
        if (!dialect.supportsIndexPrefixLength()) {
            return new ColumnProblem("newtable.error.keyLengthUnsupported",
                    dialect.displayName(), column.name());
        }
        if (!length.matches("\\d{1,4}")) {
            return new ColumnProblem("newtable.error.keyLengthInvalid",
                    column.name(), length);
        }
        if (!ColumnTypes.isTextual(column.baseType())) {
            return new ColumnProblem("newtable.error.keyLengthTextOnly",
                    column.name(), column.effectiveType());
        }
        if (!column.primaryKey() && !column.unique()) {
            return new ColumnProblem("newtable.error.keyLengthNeedsKey", column.name());
        }
        return null;
    }

    /** ⭐ N29：{@code BINARY} 列属性只有 MySQL 有，且只对文本类型有效。 */
    private static ColumnProblem binaryProblem(Dialect dialect, ColumnSpec column) {
        if (!column.binary()) {
            return null;
        }
        if (!dialect.supportsColumnBinary()) {
            return new ColumnProblem("newtable.error.binaryUnsupported",
                    dialect.displayName(), column.name());
        }
        return ColumnTypes.isTextual(column.baseType()) ? null
                : new ColumnProblem("newtable.error.binaryTextOnly",
                        column.name(), column.effectiveType());
    }

    /**
     * ⭐ N29：生成列（{@code Virtual}）。
     *
     * <p>「支持不支持」问方言；「表达式不能空、不能同时是主键 / 自增 / 带默认值」
     * 是通用规则 —— 这三条在任何库上都会以不同措辞报错，提前说清比让用户猜好。
     */
    private static ColumnProblem generatedProblem(Dialect dialect, ColumnSpec column) {
        if (!column.generated()) {
            return null;
        }
        ColumnProblem problem = dialect.generatedColumnProblem(column);
        if (problem != null) {
            return problem;
        }
        if (column.primaryKey() || column.unique()) {
            return new ColumnProblem("newtable.error.generatedKey", column.name());
        }
        if (column.autoIncrement()) {
            return new ColumnProblem("newtable.error.generatedAutoIncrement", column.name());
        }
        if (column.defaultValue() != null) {
            return new ColumnProblem("newtable.error.generatedDefault", column.name());
        }
        return null;
    }

    /** 字符集/排序规则是标识符，直接拼进 DDL —— 只许字母数字下划线。 */
    private static ColumnProblem identifierProblem(String value, String key, String columnName) {
        if (value == null || value.matches("[A-Za-z0-9_]+")) {
            return null;
        }
        return new ColumnProblem(key, columnName, value);
    }

    /** 建表时整份列定义的校验。 */
    static ColumnProblem columnListProblem(List<ColumnSpec> columns) {
        if (columns == null || columns.isEmpty()) {
            return new ColumnProblem("column.error.listEmpty");
        }
        Set<String> seen = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null) {
                continue;
            }
            ColumnProblem problem = validateColumnProblem(column);
            if (problem != null) {
                return problem;
            }
            if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                return new ColumnProblem("column.error.duplicateName", column.name());
            }
            // ⭐ 三种数据库里主键列都不允许 NULL —— 提前说清楚，
            //    好过让数据库抛一个「Column 'id' cannot be null」。
            if (column.primaryKey() && column.nullable()) {
                return new ColumnProblem("column.error.primaryKeyNullable", column.name());
            }
        }
        return null;
    }

    private static void requireTableName(String name) {
        if (name == null || name.isBlank()) {
            throw new ColumnProblem("table.error.noSelection");
        }
    }

    private static ColumnProblem validateColumnProblem(ColumnSpec column) {
        try {
            validateColumn(column);
            return null;
        } catch (ColumnProblem e) {
            return e;
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
            throw new ColumnProblem("fk.error.pickOne");
        }
        if (foreignKey.columns().isEmpty() || foreignKey.referencedColumns().isEmpty()) {
            throw new ColumnProblem("fk.error.needColumn");
        }
        if (foreignKey.referencedTable().isBlank()) {
            throw new ColumnProblem("fk.error.needRefTable");
        }
        if (foreignKey.columns().size() != foreignKey.referencedColumns().size()) {
            throw new ColumnProblem("fk.error.columnCount");
        }
        for (String column : foreignKey.columns()) {
            if (table.column(column) == null) {
                throw new ColumnProblem("column.error.notInTable", column);
            }
        }
        for (ForeignKeyDef existing : table.foreignKeys()) {
            if (!existing.name().isBlank() && existing.name().equalsIgnoreCase(foreignKey.name())) {
                throw new ColumnProblem("fk.error.duplicate", foreignKey.name());
            }
        }
        Dialect dialect = session.dialect();
        if (dialect.rebuildsTableOnForeignKeyChange()) {
            List<ForeignKeyDef> keys = new ArrayList<>(table.foreignKeys());
            keys.add(foreignKey);
            return rebuildWith(session, table, table.columns(), table.primaryKeys(),
                    keys, table.checks(), "plan.title.rebuildForForeignKey",
                    table.columns(), foreignKey.displayName());
        }
        String sql = dialect.addForeignKeySql(table.name(), foreignKey.name(),
                foreignKey.columns(), foreignKey.referencedTable(),
                foreignKey.referencedColumns(), foreignKey.onUpdate(), foreignKey.onDelete());
        if (sql == null) {
            throw new ColumnProblem("fk.error.addUnsupported", dialect.displayName());
        }
        return new StructurePlan("plan.title.addForeignKey", List.of(foreignKey.displayName()),
                List.of(sql), Danger.DESTRUCTIVE, true, false);
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
            throw new ColumnProblem("fk.error.pickToDrop");
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
                    keys, table.checks(), "plan.title.rebuildDropForeignKey",
                    table.columns(), foreignKey.displayName());
        }
        String sql = dialect.dropForeignKeySql(table.name(), foreignKey.name());
        if (sql == null) {
            throw new ColumnProblem("fk.error.dropUnsupported", dialect.displayName());
        }
        return new StructurePlan("plan.title.dropForeignKey", List.of(foreignKey.displayName()),
                List.of(sql), Danger.DESTRUCTIVE, true, false);
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
            throw new ColumnProblem("table.error.pickFirst");
        }
        String triggerName = name == null ? "" : name.trim();
        if (triggerName.isEmpty()) {
            throw new ColumnProblem("trigger.error.nameEmpty");
        }
        if (body == null || body.isBlank()) {
            throw new ColumnProblem("trigger.error.bodyEmpty");
        }
        String when = timing == null ? "BEFORE" : timing.trim().toUpperCase(Locale.ROOT);
        String what = event == null ? "INSERT" : event.trim().toUpperCase(Locale.ROOT);
        if (!List.of("BEFORE", "AFTER").contains(when)) {
            throw new ColumnProblem("trigger.error.timing");
        }
        if (!List.of("INSERT", "UPDATE", "DELETE").contains(what)) {
            throw new ColumnProblem("trigger.error.event");
        }
        for (TriggerDef existing : table.triggers()) {
            if (existing.name().equalsIgnoreCase(triggerName)) {
                throw new ColumnProblem("trigger.error.duplicate", triggerName);
            }
        }
        List<String> statements = new ArrayList<>();
        // 形状校验与「建表时带的触发器」共用 appendTriggerStatements —— 两处各写一份
        // 迟早会走出不同规则（例如将来加 INSTEAD OF 时机，只改一处就成了静默漏洞）。
        appendTriggerStatements(session, table.name(),
                List.of(new TriggerDef(triggerName, when, what, body.trim())), statements);
        return new StructurePlan("plan.title.createTrigger", List.of(triggerName), statements,
                Danger.DESTRUCTIVE, true, false);
    }

    /** 删除触发器。 */
    public static StructurePlan dropTrigger(DbSession session, TableStructure table,
                                             TriggerDef trigger) {
        if (table == null || trigger == null) {
            throw new ColumnProblem("trigger.error.pickToDrop");
        }
        String sql = session.dialect().dropTriggerSql(table.name(), trigger.name());
        return new StructurePlan("plan.title.dropTrigger", List.of(trigger.name()),
                List.of(sql), Danger.DESTRUCTIVE, true, false);
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
            throw new ColumnProblem("table.error.pickFirst");
        }
        Dialect dialect = session.dialect();
        if (!dialect.supportsTableComment()) {
            throw new ColumnProblem("table.error.noCommentSupport", dialect.displayName());
        }
        String text = comment == null ? "" : comment.trim();
        String sql = text.isEmpty()
                ? dialect.clearTableCommentSql(table.name())
                : dialect.tableCommentSql(table.name(), text);
        return new StructurePlan(text.isEmpty()
                        ? "plan.title.clearTableComment"
                        : "plan.title.setTableComment",
                List.of(table.name()),
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
            throw new ColumnProblem("index.error.pickTableAndIndex");
        }
        requireIndexName(index.name());
        List<String> columns = index.columns() == null ? List.of() : index.columns();
        if (columns.isEmpty()) {
            throw new ColumnProblem("index.error.needColumns", index.name());
        }
        for (String column : columns) {
            if (table.column(column) == null) {
                throw new ColumnProblem("column.error.notInTable", column);
            }
        }
        for (IndexDef existing : table.indexes()) {
            if (existing.name().equalsIgnoreCase(index.name())) {
                throw new ColumnProblem("index.error.duplicate", index.name());
            }
        }
        return new StructurePlan("plan.title.createIndex", List.of(index.name()),
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
            throw new ColumnProblem("index.error.pickToDrop");
        }
        IndexDef index = table.indexes().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(indexName.trim()))
                .findFirst()
                .orElseThrow(() -> new ColumnProblem("index.error.notFound", indexName));
        if (index.autoCreated() || index.name().equalsIgnoreCase(
                session.dialect().primaryIndexName())) {
            throw new ColumnProblem("index.error.autoCreated", index.name());
        }
        return new StructurePlan("plan.title.dropIndex", List.of(index.name()),
                List.of(session.dialect().dropIndexSql(table.name(), index.name())),
                Danger.DESTRUCTIVE, false, false);
    }

    private static void requireIndexName(String name) {
        if (name == null || name.isBlank()) {
            throw new ColumnProblem("index.error.nameEmpty");
        }
        if (!VALID_COLUMN_NAME.matcher(name).matches()) {
            throw new ColumnProblem("index.error.nameShape");
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
            throw new ColumnProblem("column.error.nameEmpty");
        }
        if (!VALID_COLUMN_NAME.matcher(column.name()).matches()) {
            throw new ColumnProblem("column.error.nameShape");
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
