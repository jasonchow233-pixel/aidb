package io.agenticdb.introspect;

import java.util.Locale;

/** 数据页筛选操作符。值不拼进 SQL，统一由 {@link DataSqlBuilder} 生成占位符。 */
public enum FilterOperator {

    EQ("等于"),
    NOT_EQ("不等于"),
    CONTAINS("包含"),
    STARTS_WITH("开头是"),
    ENDS_WITH("结尾是"),
    GT("大于"),
    GTE("大于等于"),
    LT("小于"),
    LTE("小于等于"),
    IN("属于"),
    IS_NULL("为 NULL"),
    IS_NOT_NULL("不为 NULL"),
    BETWEEN("介于");

    private final String label;

    FilterOperator(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** 列头值筛选面板用「属于」，条件构造器用其余操作符。 */
    public boolean acceptsValue() {
        return this != IN && this != IS_NULL && this != IS_NOT_NULL;
    }

    public boolean acceptsSecondValue() {
        return this == BETWEEN;
    }

    public static FilterOperator ofLabel(String text) {
        if (text != null) {
            for (FilterOperator operator : values()) {
                if (operator.label.equals(text)) {
                    return operator;
                }
            }
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 交给调用方报错
            }
        }
        throw new IllegalArgumentException("未知筛选操作符: " + text);
    }
}
