package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个筛选条件。
 *
 * <p>{@code values} 允许包含 {@code null}：列头值筛选要把 SQL NULL 与空串分开多选。
 * 不要用 {@code List.of(...)} 构造含 null 的列表，本类会自行复制。
 */
public record FilterCondition(String column, FilterOperator operator, List<Object> values) {

    public FilterCondition {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("筛选列不能为空");
        }
        if (operator == null) {
            throw new IllegalArgumentException("筛选操作符不能为空");
        }
        List<Object> copy = values == null ? List.of() : new ArrayList<>(values);
        values = Collections.unmodifiableList(copy);
    }

    public static FilterCondition of(String column, FilterOperator operator, Object value) {
        List<Object> values = new ArrayList<>(1);
        values.add(value);
        return new FilterCondition(column, operator, values);
    }

    public static FilterCondition between(String column, Object from, Object to) {
        List<Object> values = new ArrayList<>(2);
        values.add(from);
        values.add(to);
        return new FilterCondition(column, FilterOperator.BETWEEN, values);
    }

    public static FilterCondition isNull(String column) {
        return new FilterCondition(column, FilterOperator.IS_NULL, List.of());
    }

    public static FilterCondition isNotNull(String column) {
        return new FilterCondition(column, FilterOperator.IS_NOT_NULL, List.of());
    }

    public static FilterCondition in(String column, List<Object> values) {
        return new FilterCondition(column, FilterOperator.IN, values);
    }

    public String summary() {
        return switch (operator) {
            case IS_NULL, IS_NOT_NULL -> column + " " + operator.label();
            case IN -> column + " ∈ {" + values.size() + " 个值}";
            case BETWEEN -> column + " 介于 " + display(values, 0) + " 和 " + display(values, 1);
            default -> column + " " + operator.label() + " " + display(values, 0);
        };
    }

    private static String display(List<Object> values, int index) {
        if (index >= values.size()) {
            return "—";
        }
        Object value = values.get(index);
        if (value == null) {
            return "NULL";
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "\"\"";
        }
        return text.length() > 24 ? text.substring(0, 23) + "…" : text;
    }
}
