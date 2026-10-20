package io.agenticdb.model;

/**
 * 列类型。<b>粗粒度分类</b> —— UI 的 Renderer、筛选面板、导出格式都按这个分类走，
 * 不直接判断字符串类型名（那是方言细节，会散落到各处）。
 */
public enum ColumnType {
    TEXT,
    NUMBER,
    BOOLEAN,
    DATE,
    TIME,
    TIMESTAMP,
    BINARY,
    JSON,
    UUID,
    ENUM,
    SET,
    ARRAY,
    GEO,
    OTHER;

    /** 是否需要引号才能放进 SQL 字面量。 */
    public boolean isLiteralBareable() {
        return this == NUMBER || this == BOOLEAN;
    }

    /** 是否需要考虑时区。 */
    public boolean isTemporal() {
        return this == DATE || this == TIME || this == TIMESTAMP;
    }

    /** 是否可能有超长内容（详情面板 / 大字段延迟加载只对这类有意义）。 */
    public boolean possiblyLarge() {
        return this == TEXT || this == BINARY || this == JSON || this == ARRAY;
    }
}