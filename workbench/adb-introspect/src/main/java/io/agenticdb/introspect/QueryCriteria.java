package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询构造器表单行 → {@link DataQuery} 的纯逻辑转换（无头可测）。
 *
 * <p>UI 只负责把用户输入收进 {@link Criterion} 列表；怎么把「每行一个条件、行与行以
 * AND / OR 连接」翻译成 {@link FilterGroup}（组内 AND、组间 OR）、怎么把值框里的文本
 * 解析成有类型的字面量，都在这里定死，门禁钉住，不散落在 JavaFX 控件监听里。
 */
public final class QueryCriteria {

    /** 本行与上一行的连接词；第一行的值被忽略。 */
    public enum Connector { AND, OR }

    /**
     * @param connector 与上一行的连接词
     * @param column    列名
     * @param operator  操作符
     * @param value     第一个值（BETWEEN 的下界、IN 的逗号分隔列表）
     * @param value2    BETWEEN 的上界，其余忽略
     */
    public record Criterion(Connector connector, String column, FilterOperator operator,
                            String value, String value2) {
        public Criterion {
            connector = connector == null ? Connector.AND : connector;
        }
    }

    private final List<Criterion> rows;

    public QueryCriteria(List<Criterion> rows) {
        this.rows = List.copyOf(rows == null ? List.of() : rows);
    }

    public static QueryCriteria empty() {
        return new QueryCriteria(List.of());
    }

    /**
     * 按 OR 切分成组：遇 {@link Connector#OR} 开新组，AND 留在当前组；空列的行忽略。
     * 没有有效条件时返回一个空组（等价无条件）。
     */
    public DataQuery toDataQuery() {
        List<FilterGroup> groups = new ArrayList<>();
        List<FilterCondition> current = new ArrayList<>();
        for (Criterion row : rows) {
            if (row.column() == null || row.column().isBlank()) {
                continue;
            }
            FilterCondition condition = toCondition(row);
            if (current.isEmpty()) {
                current.add(condition);
            } else if (row.connector() == Connector.OR) {
                groups.add(FilterGroup.of(new ArrayList<>(current)));
                current.clear();
                current.add(condition);
            } else {
                current.add(condition);
            }
        }
        if (!current.isEmpty()) {
            groups.add(FilterGroup.of(new ArrayList<>(current)));
        }
        if (groups.isEmpty()) {
            groups.add(FilterGroup.empty());
        }
        return new DataQuery(List.copyOf(groups), List.of());
    }

    private static FilterCondition toCondition(Criterion row) {
        String column = row.column();
        return switch (row.operator()) {
            case IS_NULL -> FilterCondition.isNull(column);
            case IS_NOT_NULL -> FilterCondition.isNotNull(column);
            case IN -> FilterCondition.in(column, splitValues(row.value()));
            case BETWEEN -> FilterCondition.between(column, parseValue(row.value()),
                    parseValue(row.value2()));
            case CONTAINS, STARTS_WITH, ENDS_WITH, EQ, NOT_EQ, GT, GTE, LT, LTE ->
                    FilterCondition.of(column, row.operator(), parseValue(row.value()));
        };
    }

    /** 文本 → 有类型的值：布尔 / 整数 / 小数各自归位，其余按字符串。空文本当 null。 */
    static Object parseValue(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        if (t.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (t.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (t.matches("-?\\d+")) {
            try {
                return Long.valueOf(t);
            } catch (NumberFormatException ignored) {
                // 落到字符串
            }
        }
        if (t.matches("-?\\d*\\.\\d+([eE][+-]?\\d+)?")) {
            try {
                return Double.valueOf(t);
            } catch (NumberFormatException ignored) {
                // 落到字符串
            }
        }
        return t;
    }

    private static List<Object> splitValues(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Object> values = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            values.add(parseValue(part));
        }
        return values;
    }
}
