package io.agenticdb.introspect;

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
import java.util.Objects;
import java.util.Set;

/**
 * 结构变更 SQL 构造器。
 *
 * <p>UI 只负责收集用户输入，危险判断、方言差异、SQLite 表重建都集中在这里。
 * 所有方法返回完整的 {@link StructurePlan}，调用方必须先展示再执行。
 */
public final class StructureSqlBuilder {

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
        dialect.appendColumnComment(table.name(), next, statements);
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
        String temporary = "__adb_rebuild_" + table.name() + "_" + System.nanoTime();
        String quote = session.dialect().quote(table.name());
        String temporaryQuote = session.dialect().quote(temporary);

        List<String> statements = new ArrayList<>();
        List<String> views = dependentViews(session, table.name());
        for (String view : views) {
            statements.add("DROP VIEW " + session.dialect().quote(view));
        }
        statements.add(createTableSql(session, temporary, columns, table.primaryKeys(),
                table.indexes(), table.foreignKeys()));
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
        return new StructurePlan("重建表以修改列 " + oldName, statements,
                Danger.DESTRUCTIVE, true, true);
    }

    /** 引用了这张表的视图 —— 只有整表重建需要拆它们。 */
    private static List<String> dependentViews(DbSession session, String table) {
        try {
            return session.dialect().dependentViews(session.connection(), table);
        } catch (java.sql.SQLException ignored) {
            // 读不到就当作没有依赖视图，让重建走到数据库自己的错误。
            return List.of();
        }
    }

    /** 视图的 DDL。 */
    private static String viewDdl(DbSession session, String view) {
        try {
            return session.dialect().readDdl(session.connection(), view, true);
        } catch (java.sql.SQLException ignored) {
            return null;
        }
    }

    public static String createTableSql(DbSession session, String name, List<ColumnDef> columns,
                                        List<String> primaryKeys, List<IndexDef> indexes,
                                        List<ForeignKeyDef> foreignKeys) {
        return createTableSql(session, name, columns, primaryKeys, foreignKeys);
    }

    private static String createTableSql(DbSession session, String name, List<ColumnDef> columns,
                                         List<String> primaryKeys,
                                         List<ForeignKeyDef> foreignKeys) {
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
        return "CREATE TABLE " + quoteIdentifier(session, name) + " (\n    "
                + String.join(",\n    ", lines) + "\n)";
    }

    private static String createIndexSql(DbSession session, String tableName, IndexDef index) {
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX "
                + quoteIdentifier(session, index.name()) + " ON "
                + quoteIdentifier(session, tableName) + " ("
                + index.columns().stream()
                .map(column -> quoteIdentifier(session, column))
                .reduce((a, b) -> a + ", " + b).orElse("") + ")";
    }

    private static void validateColumn(ColumnSpec column) {
        if (column.name() == null || column.name().isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        if (!column.name().matches("[A-Za-z_][A-Za-z0-9_$]*")) {
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
