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

    /**
     * 是否「文本档」：这一档的列吃得下空串，所以「清空一格」和「填一个空串」是同一件事。
     *
     * <p>其余档（数字 / 布尔 / 日期 / 二进制…）没有空串这个值 —— 把它们那一格清空，
     * 交到服务端的是 {@code null}（真 NULL），不是 {@code ''}。这不是偏好：实测把
     * {@code ''} 绑进 {@code DECIMAL} 列，MySQL / MariaDB / PG / Oracle 四家<b>整批保存失败</b>
     * （{@code Incorrect decimal value: ''}、{@code expression is of type character varying}），
     * 而 SQL Server 的 {@code BIT} 会把 {@code ''} <b>静默折成 0</b> —— 一边是报错，
     * 一边是悄悄改数，两个都不能给用户。
     *
     * <p>这条分类是<b>界面与真库契约共用的唯一出处</b>：界面那个「清空这一格」最终绑进
     * SQL 的值由 {@code adb-editing} 的 {@code EditValues.emptiedCell} 按它决定，
     * 所以这里加一档漏了那边，格子写回的真库契约（{@code EditWriteBackMatrixTest}）会报红。
     */
    public boolean textual() {
        return this == TEXT || this == JSON || this == UUID || this == OTHER;
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