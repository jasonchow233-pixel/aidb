package io.agenticdb.introspect;

/**
 * 表的**物理选项** —— 结构页「选项」页签的内容。
 *
 * <p>只有 MySQL 把这些放在 {@code information_schema.tables} 里；
 * SQLite / PostgreSQL 没有表级引擎、排序规则、行格式的概念。
 * 拿不到的字段保持 {@code null}，由 UI 显示「—」——**不编造值**。
 *
 * @param engine        存储引擎（InnoDB / MyISAM…）
 * @param collation     表排序规则（utf8mb4_0900_ai_ci…）
 * @param autoIncrement 自增列的下一个值；没有自增列时为 {@code null}
 * @param rowFormat     行格式（Dynamic / Compact…）
 */
public record TableOptions(
        String engine,
        String collation,
        Long autoIncrement,
        String rowFormat) {

    private static final TableOptions NONE = new TableOptions(null, null, null, null);

    /** 该方言 / 这张表没有可展示的选项。 */
    public static TableOptions unavailable() {
        return NONE;
    }

    /** 四项里至少有一项是真值。 */
    public boolean available() {
        return !isBlank(engine) || !isBlank(collation)
                || autoIncrement != null || !isBlank(rowFormat);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
