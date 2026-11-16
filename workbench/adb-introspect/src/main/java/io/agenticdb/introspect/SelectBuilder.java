package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化条件 → 编辑器里<b>可直接执行的字面量 SELECT</b>。
 *
 * <p>和 {@link DataSqlBuilder} 的分工：数据页走 JDBC 参数绑定，所以那边的值只进
 * {@code params}、SQL 里留 {@code ?}；查询构造器的产物要回填到编辑器里由用户自己 Run，
 * 编辑器没有参数绑定通道，因此这里把值按类型<b>内联成 SQL 字面量</b>（字符串单引号翻倍、
 * 数字 / 布尔 / NULL 裸写）。BLOB 无法安全地字面量化，直接拒绝，不含糊降级。
 *
 * <p>标识符一律走 {@link io.agenticdb.jdbc.Dialect#quote(String)}，不信任任何外部输入；
 * 表名 / 列名为空即 fail-closed。条件结构（组内 AND、组间 OR）与
 * {@link DataSqlBuilder#where} 同语义，复用 {@link DataQuery} / {@link FilterGroup}
 * 那一套已交付、已被门禁覆盖的模型，不另造第二份条件类型。
 *
 * <p><b>只生成 SELECT，不执行、不写库、不加分页</b>：LIMIT / OFFSET 由用户在编辑器里
 * 自行决定，构造器不替他加（方言分页语法各不相同，加了反而会限制用户）。
 */
public final class SelectBuilder {

    private SelectBuilder() {
    }

    /**
     * @param session 只用来取方言引用规则（不需要真查询，测试可用假会话）
     * @param table   表名，非空
     * @param columns 选中列；为空 / null 时生成 {@code SELECT *}
     * @param query   条件与排序；null 当作无条件
     * @return 可直接执行的 SELECT 文本
     */
    public static String select(DbSession session, String table,
                                List<String> columns, DataQuery query) {
        String quotedTable = quote(session, table);
        StringBuilder sql = new StringBuilder("SELECT ").append(projection(session, columns))
                .append(" FROM ").append(quotedTable);

        sql.append(where(session, query));

        if (query != null && query.hasSorts()) {
            sql.append(" ORDER BY ");
            for (int i = 0; i < query.sorts().size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                SortSpec sort = query.sorts().get(i);
                sql.append(quote(session, sort.column()))
                        .append(sort.ascending() ? " ASC" : " DESC");
            }
        }
        return sql.toString();
    }

    private static String projection(DbSession session, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "*";
        }
        List<String> parts = new ArrayList<>(columns.size());
        for (String column : columns) {
            parts.add(quote(session, column));
        }
        return String.join(", ", parts);
    }

    /** 与 DataSqlBuilder.where 同结构：组内 AND、组间 OR，每组各自括起来。 */
    private static String where(DbSession session, DataQuery query) {
        if (query == null || !query.hasFilters()) {
            return "";
        }
        List<String> groupParts = new ArrayList<>();
        for (FilterGroup group : query.groups()) {
            if (group.isEmpty()) {
                continue;
            }
            List<String> parts = new ArrayList<>(group.conditions().size());
            for (FilterCondition filter : group.conditions()) {
                parts.add(condition(session, filter));
            }
            groupParts.add(String.join(" AND ", parts));
        }
        if (groupParts.isEmpty()) {
            return "";
        }
        if (groupParts.size() == 1) {
            return " WHERE " + groupParts.get(0);
        }
        String orClause = groupParts.stream()
                .map(part -> part.startsWith("(") ? part : "(" + part + ")")
                .collect(java.util.stream.Collectors.joining(" OR "));
        return " WHERE " + orClause;
    }

    private static String condition(DbSession session, FilterCondition filter) {
        String column = quote(session, filter.column());
        List<Object> values = filter.values();
        return switch (filter.operator()) {
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case EQ -> {
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("EQ condition missing value: " + filter.column());
                }
                Object v = values.getFirst();
                yield v == null ? column + " IS NULL" : column + " = " + literal(v);
            }
            case NOT_EQ -> {
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("NOT_EQ condition missing value: " + filter.column());
                }
                Object v = values.getFirst();
                yield v == null ? column + " IS NOT NULL" : column + " <> " + literal(v);
            }
            case CONTAINS, STARTS_WITH, ENDS_WITH -> {
                if (values.isEmpty() || values.getFirst() == null) {
                    yield "1 = 0";
                }
                String escaped = escapeLike(String.valueOf(values.getFirst()));
                String pattern = switch (filter.operator()) {
                    case CONTAINS -> "%" + escaped + "%";
                    case STARTS_WITH -> escaped + "%";
                    case ENDS_WITH -> "%" + escaped;
                    default -> throw new IllegalStateException();
                };
                yield column + " LIKE " + literal(pattern) + " ESCAPE '!'";
            }
            case GT, GTE, LT, LTE -> {
                if (values.isEmpty() || values.getFirst() == null) {
                    yield "1 = 0";
                }
                yield column + switch (filter.operator()) {
                    case GT -> " > ";
                    case GTE -> " >= ";
                    case LT -> " < ";
                    case LTE -> " <= ";
                    default -> throw new IllegalStateException();
                } + literal(values.getFirst());
            }
            case BETWEEN -> {
                if (values.size() < 2 || values.get(0) == null || values.get(1) == null) {
                    yield "1 = 0";
                }
                yield column + " BETWEEN " + literal(values.get(0))
                        + " AND " + literal(values.get(1));
            }
            case IN -> inCondition(column, values);
        };
    }

    private static String inCondition(String column, List<Object> values) {
        if (values == null || values.isEmpty()) {
            return "1 = 0";
        }
        boolean hasNull = false;
        List<String> literals = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                hasNull = true;
            } else {
                literals.add(literal(value));
            }
        }
        String in = "";
        if (!literals.isEmpty()) {
            in = column + " IN (" + String.join(", ", literals) + ")";
        }
        if (hasNull && in.isEmpty()) {
            return column + " IS NULL";
        }
        if (hasNull) {
            return "(" + in + " OR " + column + " IS NULL)";
        }
        return in;
    }

    /**
     * 值 → SQL 字面量。与 {@code EditSqlBuilder.literal} 同一套规则，但这里<b>不</b>接受
     * BLOB：字节数组没有跨方言都安全的字面量写法，宁可拒绝也不生成一条可能错乱的 SQL。
     */
    static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof byte[]) {
            throw new IllegalArgumentException("Binary value cannot be rendered as a SQL literal; use a parameterized query");
        }
        // 日期 / 时间 / UUID 等一律按字符串字面量处理（各方言都能隐式转换标准格式）。
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private static String quote(DbSession session, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Identifier must not be blank");
        }
        return session.dialect().quote(identifier);
    }
}
