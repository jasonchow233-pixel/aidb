package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.model.ColumnDef;
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

    /** 新增/修改列的输入。{@code rawType} 为空时按 {@link ColumnType} 给默认类型。 */
    public record ColumnSpec(
            String name,
            String rawType,
            boolean nullable,
            String defaultValue,
            String comment,
            boolean unique,
            boolean primaryKey) {

        public ColumnSpec {
            name = name == null ? "" : name.trim();
            rawType = rawType == null ? "" : rawType.trim();
            defaultValue = defaultValue == null || defaultValue.isBlank()
                    ? null : defaultValue.trim();
            comment = comment == null || comment.isBlank() ? null : comment.trim();
        }

        public static ColumnSpec from(ColumnDef column) {
            return new ColumnSpec(column.name(), column.rawType(), column.nullable(),
                    column.defaultValue(), column.comment(), column.unique(),
                    column.primaryKey());
        }

        public ColumnSpec withName(String value) {
            return new ColumnSpec(value, rawType, nullable, defaultValue, comment, unique,
                    primaryKey);
        }
    }

    public static StructurePlan addColumn(DbSession session, TableStructure table,
                                          ColumnSpec column) {
        validateColumn(column);
        if (session.dialect() == DialectKind.SQLITE && !column.nullable()
                && column.defaultValue() == null && !column.primaryKey()) {
            throw new IllegalArgumentException("SQLite 新增 NOT NULL 列必须提供默认值");
        }
        List<String> statements = new ArrayList<>();
        String definition = columnDefinition(session, column, true);
        statements.add("ALTER TABLE " + session.dialect().quote(table.name())
                + " ADD COLUMN " + definition);
        appendComment(session, table.name(), column, statements);
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
        String sql = "ALTER TABLE " + session.dialect().quote(table.name())
                + " RENAME COLUMN " + session.dialect().quote(oldName)
                + " TO " + session.dialect().quote(newName.trim());
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

        if (session.dialect() == DialectKind.SQLITE) {
            ColumnSpec preserved = new ColumnSpec(next.name(), next.rawType(), next.nullable(),
                    next.defaultValue(), next.comment(), old.unique(), old.primaryKey());
            return rebuildPlan(session, table, oldName, preserved);
        }

        List<String> statements = new ArrayList<>();
        String tableName = session.dialect().quote(table.name());
        String columnName = session.dialect().quote(oldName);
        String rawType = rawType(next);
        if (!sameType(old.rawType(), rawType)) {
            statements.add("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                    + " TYPE " + rawType);
        }
        if (session.dialect() == DialectKind.MYSQL) {
            // MySQL 没有独立的 SET NOT NULL / SET DEFAULT；一条 MODIFY 必须带完整定义。
            statements.clear();
            statements.add("ALTER TABLE " + tableName + " MODIFY COLUMN "
                    + columnDefinition(session, next, true));
        } else {
            statements.add("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                    + (next.nullable() ? " DROP NOT NULL" : " SET NOT NULL"));
            statements.add("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                    + (next.defaultValue() == null ? " DROP DEFAULT"
                    : " SET DEFAULT " + next.defaultValue()));
        }
        appendComment(session, table.name(), next, statements);
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
        String sql = "ALTER TABLE " + session.dialect().quote(table.name())
                + " DROP COLUMN " + session.dialect().quote(columnName);
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
        List<String> views = sqliteDependentViews(session, table.name());
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
            String sql = sqliteViewSql(session, view);
            if (sql != null && !sql.isBlank()) {
                statements.add(sql.strip().replaceAll(";\\s*$", ""));
            }
        }
        return new StructurePlan("重建表以修改列 " + oldName, statements,
                Danger.DESTRUCTIVE, true, true);
    }

    private static List<String> sqliteDependentViews(DbSession session, String table) {
        if (session.dialect() != DialectKind.SQLITE) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        java.util.regex.Pattern reference = java.util.regex.Pattern.compile(
                "(?i)\\b" + java.util.regex.Pattern.quote(table) + "\\b");
        try (var statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name, sql FROM sqlite_master WHERE type = 'view' ORDER BY name")) {
            while (rs.next()) {
                String sql = rs.getString(2);
                if (sql != null && reference.matcher(sql).find()) {
                    names.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException ignored) {
            // 没有 sqlite_master 权限时让重建走到数据库自己的错误，UI 会展示原因。
        }
        return names;
    }

    private static String sqliteViewSql(DbSession session, String view) {
        try (var statement = session.connection().prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'view' AND name = ?")) {
            statement.setString(1, view);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
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

    private static String columnDefinition(DbSession session, ColumnSpec column,
                                           boolean includeName) {
        String definition = (includeName ? quoteIdentifier(session, column.name()) + " " : "")
                + rawType(column);
        if (!column.nullable()) {
            definition += " NOT NULL";
        }
        if (column.defaultValue() != null) {
            definition += " DEFAULT " + column.defaultValue();
        }
        if (column.unique()) {
            definition += " UNIQUE";
        }
        if (session.dialect() == DialectKind.MYSQL && column.comment() != null) {
            definition += " COMMENT '" + column.comment().replace("'", "''") + "'";
        }
        return definition;
    }

    private static void appendComment(DbSession session, String table, ColumnSpec column,
                                      List<String> statements) {
        if (column.comment() == null || session.dialect() != DialectKind.POSTGRESQL) {
            return;
        }
        statements.add("COMMENT ON COLUMN " + quoteIdentifier(session, table) + "."
                + quoteIdentifier(session, column.name()) + " IS '"
                + column.comment().replace("'", "''") + "'");
    }

    private static String rawType(ColumnSpec column) {
        return column.rawType().isBlank() ? defaultType(column.primaryKey()) : column.rawType();
    }

    private static String defaultType(boolean primaryKey) {
        return primaryKey ? "INTEGER" : "TEXT";
    }

    private static String defaultRawType(ColumnDef column) {
        if (column.rawType() != null && !column.rawType().isBlank()) {
            return column.rawType();
        }
        return switch (column.type()) {
            case NUMBER -> "NUMERIC";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "TIMESTAMP";
            case BINARY -> "BLOB";
            case JSON -> "JSON";
            case UUID -> "UUID";
            default -> "TEXT";
        };
    }

    private static boolean sameType(String left, String right) {
        return left != null && right != null
                && left.replace(" ", "").equalsIgnoreCase(right.replace(" ", ""));
    }

    private static ColumnDef toColumnDef(ColumnSpec column) {
        return new ColumnDef(column.name(), ColumnType.OTHER, column.nullable(),
                column.primaryKey(), column.defaultValue(), column.comment(), null,
                null, null, null, column.unique(), rawType(column));
    }

    private static String quoteIdentifier(DbSession session, String value) {
        return session.dialect().quote(value);
    }
}
