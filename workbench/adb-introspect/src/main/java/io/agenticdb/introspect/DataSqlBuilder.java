package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.QueryLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据页 SQL 构造器。
 *
 * <p>⛔ 用户输入只进入 {@code params}，绝不拼进 SQL 文本。排序/筛选列名来自
 * 数据库内省结果，仍统一按方言引用，避免大小写与保留字问题。
 */
public final class DataSqlBuilder {

    public record Sql(String text, List<Object> params) {
        public Sql {
            params = Collections.unmodifiableList(new ArrayList<>(
                    params == null ? List.of() : params));
        }

        public boolean hasParams() {
            return !params.isEmpty();
        }
    }

    private DataSqlBuilder() {
    }

    public static Sql count(DbSession session, String table, DataQuery query) {
        Sql where = where(session, table, query);
        return new Sql("SELECT COUNT(*) AS adb_count FROM " + quote(session, table)
                + where.text(), where.params());
    }

    public static Sql page(DbSession session, String table, DataQuery query,
                           int pageSize, long offset) {
        int limit = QueryLimits.clampPageSize(pageSize);
        long safeOffset = Math.max(0, offset);
        if (QueryLimits.exceedsOffsetLimit(safeOffset)) {
            throw new IllegalArgumentException(
                    "OFFSET " + safeOffset + " 超过上限 " + QueryLimits.MAX_OFFSET
                            + "，请改用筛选条件");
        }

        Sql where = where(session, table, query);
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(quote(session, table))
                .append(where.text());
        if (query.hasSorts()) {
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
        sql.append(session.dialect().pagingClause(limit, safeOffset, query.hasSorts()));
        return new Sql(sql.toString(), where.params());
    }

    /**
     * 列头筛选候选值。调用方传入的 {@code query} 应已排除当前列的筛选条件，
     * 这样才能得到「其他列筛选后，本列还剩哪些值」的级联计数。
     */
    public static Sql distinctValues(DbSession session, String table, String column,
                                     DataQuery query, int limit) {
        int cap = Math.max(1, limit);
        Sql where = where(session, table, query);
        String quotedColumn = quote(session, column);
        String sql = "SELECT " + quotedColumn + " AS adb_value, COUNT(*) AS adb_count"
                + " FROM " + quote(session, table)
                + where.text()
                + " GROUP BY " + quotedColumn
                + " ORDER BY adb_count DESC, adb_value ASC"
                + session.dialect().pagingClause(cap, 0, true);
        return new Sql(sql, where.params());
    }

    private static Sql where(DbSession session, String table, DataQuery query) {
        if (query == null || !query.hasFilters()) {
            return new Sql("", List.of());
        }
        List<String> parts = new ArrayList<>(query.filters().size());
        List<Object> params = new ArrayList<>();
        for (FilterCondition filter : query.filters()) {
            parts.add(condition(session, filter, params));
        }
        return new Sql(" WHERE " + String.join(" AND ", parts), params);
    }

    private static String condition(DbSession session, FilterCondition filter,
                                    List<Object> params) {
        String column = quote(session, filter.column());
        List<Object> values = filter.values();
        return switch (filter.operator()) {
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case EQ -> {
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("等于条件缺少值: " + filter.column());
                }
                if (values.getFirst() == null) {
                    yield column + " IS NULL";
                }
                params.add(values.getFirst());
                yield column + " = ?";
            }
            case NOT_EQ -> {
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("不等于条件缺少值: " + filter.column());
                }
                if (values.getFirst() == null) {
                    yield column + " IS NOT NULL";
                }
                params.add(values.getFirst());
                yield column + " <> ?";
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
                params.add(pattern);
                yield column + " LIKE ? ESCAPE '!'";
            }
            case GT, GTE, LT, LTE -> {
                if (values.isEmpty() || values.getFirst() == null) {
                    yield "1 = 0";
                }
                params.add(values.getFirst());
                yield column + switch (filter.operator()) {
                    case GT -> " > ?";
                    case GTE -> " >= ?";
                    case LT -> " < ?";
                    case LTE -> " <= ?";
                    default -> throw new IllegalStateException();
                };
            }
            case BETWEEN -> {
                if (values.size() < 2 || values.get(0) == null || values.get(1) == null) {
                    yield "1 = 0";
                }
                params.add(values.get(0));
                params.add(values.get(1));
                yield column + " BETWEEN ? AND ?";
            }
            case IN -> inCondition(column, values, params);
        };
    }

    private static String inCondition(String column, List<Object> values, List<Object> params) {
        if (values == null || values.isEmpty()) {
            return "1 = 0";
        }
        boolean hasNull = false;
        List<Object> nonNull = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                hasNull = true;
            } else {
                nonNull.add(value);
            }
        }

        String in = "";
        if (!nonNull.isEmpty()) {
            for (Object value : nonNull) {
                params.add(value);
            }
            in = column + " IN (" + String.join(", ",
                    Collections.nCopies(nonNull.size(), "?")) + ")";
        }
        if (hasNull && in.isEmpty()) {
            return column + " IS NULL";
        }
        if (hasNull) {
            return "(" + in + " OR " + column + " IS NULL)";
        }
        return in;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private static String quote(DbSession session, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return session.dialect().quote(identifier);
    }
}
