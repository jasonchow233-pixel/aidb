package io.agenticdb.editing;

import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 把编辑缓冲翻译成按主键定位的 UPDATE。
 *
 * <p>SQL 里的值一律使用 {@code ?}；预览只是把参数替换成可读字面量，
 * 真正执行仍走 {@link DbSession#update(String, List)}。
 */
public final class EditSqlBuilder {

    public record Statement(String sql, List<Object> params) {
        public Statement {
            params = Collections.unmodifiableList(new ArrayList<>(
                    params == null ? List.of() : params));
        }

        public String preview() {
            StringBuilder text = new StringBuilder(sql.length() + 32);
            int param = 0;
            for (int i = 0; i < sql.length(); i++) {
                char ch = sql.charAt(i);
                if (ch == '?' && param < params.size()) {
                    text.append(literal(params.get(param++)));
                } else {
                    text.append(ch);
                }
            }
            return text.toString();
        }
    }

    public record Plan(List<Statement> statements, boolean complete) {
        public Plan {
            statements = List.copyOf(statements == null ? List.of() : statements);
        }

        public Plan(List<Statement> statements) {
            this(statements, true);
        }

        public boolean isEmpty() {
            return statements.isEmpty();
        }

        public int size() {
            return statements.size();
        }

        public String preview() {
            StringBuilder text = new StringBuilder();
            for (Statement statement : statements) {
                if (!text.isEmpty()) {
                    text.append(";\n");
                }
                text.append(statement.preview());
            }
            return text.toString();
        }
    }

    private EditSqlBuilder() {
    }

    public static Plan plan(DbSession session, TableSchema schema, EditBuffer buffer) {
        if (!schema.editable()) {
            throw new IllegalStateException("表 " + schema.name() + " 没有主键，不能安全编辑");
        }
        if (buffer.dirtyCount() == 0) {
            return new Plan(List.of());
        }

        String table = session.dialect().quote(schema.name());
        List<Statement> statements = new ArrayList<>();
        for (Map.Entry<RowIdentity, Map<String, Object>> entry : buffer.editsByRow().entrySet()) {
            RowIdentity identity = entry.getKey();
            Map<String, Object> changes = entry.getValue();
            if (changes.isEmpty()) {
                continue;
            }

            List<Object> params = new ArrayList<>(changes.size() + schema.primaryKeys().size());
            StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
            int index = 0;
            for (ColumnDef column : schema.columns()) {
                if (!changes.containsKey(column.name())) {
                    continue;
                }
                if (index++ > 0) {
                    sql.append(", ");
                }
                sql.append(session.dialect().quote(column.name())).append(" = ?");
                params.add(changes.get(column.name()));
            }

            sql.append(" WHERE ");
            for (int i = 0; i < schema.primaryKeys().size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                String key = schema.primaryKeys().get(i);
                sql.append(session.dialect().quote(key)).append(" = ?");
                params.add(identity.values().get(key));
            }
            statements.add(new Statement(sql.toString(), params));
        }

        for (RowIdentity identity : buffer.deletedRows()) {
            List<Object> params = new ArrayList<>(schema.primaryKeys().size());
            StringBuilder sql = new StringBuilder("DELETE FROM ").append(table).append(" WHERE ");
            for (int i = 0; i < schema.primaryKeys().size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                String key = schema.primaryKeys().get(i);
                sql.append(session.dialect().quote(key)).append(" = ?");
                params.add(identity.values().get(key));
            }
            statements.add(new Statement(sql.toString(), params));
        }

        for (Map<String, Object> values : buffer.drafts().values()) {
            List<ColumnDef> present = new ArrayList<>();
            for (ColumnDef column : schema.columns()) {
                if (values.containsKey(column.name())) {
                    present.add(column);
                }
            }
            if (present.isEmpty()) {
                statements.add(new Statement("INSERT INTO " + table + " DEFAULT VALUES",
                        List.of()));
                continue;
            }
            List<Object> params = new ArrayList<>(present.size());
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
            for (int i = 0; i < present.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(session.dialect().quote(present.get(i).name()));
                params.add(values.get(present.get(i).name()));
            }
            sql.append(") VALUES (");
            for (int i = 0; i < present.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('?');
            }
            sql.append(')');
            statements.add(new Statement(sql.toString(), params));
        }
        return new Plan(statements);
    }

    /**
     * 生成“撤销这次保存”的 SQL。
     *
     * <p>必须在 {@link EditBuffer#clear()} 之前调用：
     * UPDATE 用 originals 还原；DELETE 用删除时保留的整行值重新 INSERT；
     * 新增行只有主键齐全时才能用 DELETE 反向撤销，否则标记为不完整。
     */
    public static Plan inversePlan(DbSession session, TableSchema schema, EditBuffer buffer) {
        if (!schema.editable() || !buffer.isDirty()) {
            return new Plan(List.of());
        }
        String table = session.dialect().quote(schema.name());
        List<Statement> statements = new ArrayList<>();
        boolean complete = true;

        // 反向顺序：先删掉本次 INSERT，再恢复本次 DELETE，最后还原 UPDATE。

        for (Map<String, Object> values : buffer.drafts().values()) {
            List<Object> params = new ArrayList<>(schema.primaryKeys().size());
            boolean keyComplete = true;
            for (String key : schema.primaryKeys()) {
                Object value = values.get(key);
                if (value == null) {
                    keyComplete = false;
                    break;
                }
                params.add(value);
            }
            if (!keyComplete) {
                complete = false;
                continue;
            }
            StringBuilder sql = new StringBuilder("DELETE FROM ").append(table).append(" WHERE ");
            for (int i = 0; i < schema.primaryKeys().size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                sql.append(session.dialect().quote(schema.primaryKeys().get(i))).append(" = ?");
            }
            statements.add(new Statement(sql.toString(), params));
        }

        for (Map.Entry<RowIdentity, Map<String, Object>> entry
                : buffer.deletedRowValues().entrySet()) {
            Map<String, Object> row = entry.getValue();
            List<ColumnDef> present = new ArrayList<>();
            for (ColumnDef column : schema.columns()) {
                if (row.containsKey(column.name())) {
                    present.add(column);
                }
            }
            if (present.isEmpty()) {
                complete = false;
                continue;
            }
            List<Object> params = new ArrayList<>(present.size());
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
            for (int i = 0; i < present.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(session.dialect().quote(present.get(i).name()));
                params.add(row.get(present.get(i).name()));
            }
            sql.append(") VALUES (");
            for (int i = 0; i < present.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('?');
            }
            sql.append(')');
            statements.add(new Statement(sql.toString(), params));
        }

        Map<CellKey, Object> originalValues = buffer.originals();
        for (Map.Entry<RowIdentity, Map<String, Object>> entry
                : buffer.editsByRow().entrySet()) {
            RowIdentity identity = entry.getKey();
            Map<String, Object> changes = entry.getValue();
            List<Object> params = new ArrayList<>(changes.size() + schema.primaryKeys().size());
            StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
            int index = 0;
            for (ColumnDef column : schema.columns()) {
                if (!changes.containsKey(column.name())) {
                    continue;
                }
                CellKey key = new CellKey(identity, column.name());
                if (!originalValues.containsKey(key)) {
                    complete = false;
                    continue;
                }
                if (index++ > 0) {
                    sql.append(", ");
                }
                sql.append(session.dialect().quote(column.name())).append(" = ?");
                params.add(originalValues.get(key));
            }
            if (index == 0) {
                continue;
            }
            sql.append(" WHERE ");
            for (int i = 0; i < schema.primaryKeys().size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                String key = schema.primaryKeys().get(i);
                sql.append(session.dialect().quote(key)).append(" = ?");
                params.add(identity.values().get(key));
            }
            statements.add(new Statement(sql.toString(), params));
        }

        return new Plan(statements, complete);
    }

    /** 在一个事务里执行；任一条失败整体回滚，避免半保存。 */
    public static int execute(DbSession session, Plan plan) throws Exception {
        if (plan.isEmpty()) {
            return 0;
        }
        boolean ownTransaction = !session.inTransaction();
        if (ownTransaction) {
            session.begin();
        }
        try {
            int affected = 0;
            for (Statement statement : plan.statements()) {
                // ⭐ 第三层门禁：保存路径的每条语句也声明自己的危险等级。
                //    数据页生成的都是 UPDATE / DELETE / INSERT，级别是「写数据」，
                //    驱动层复查后一致才放行。
                affected += session.update(statement.sql(), statement.params(),
                        io.agenticdb.model.Danger.MUTATING);
            }
            if (ownTransaction) {
                session.commit();
            }
            return affected;
        } catch (Exception e) {
            if (ownTransaction) {
                session.rollback();
            }
            throw e;
        }
    }

    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof byte[] bytes) {
            return "<BLOB " + bytes.length + " B>";
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }
}
