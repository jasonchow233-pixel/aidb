package io.agenticdb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 列定义。
 *
 * @param enumValues      枚举取值，形如 {@code 1=待支付,2=已支付}；非枚举为 null
 * @param charMaxLength   变长类型的最大长度；无长度概念为 null
 * @param defaultValue    DDL 里的默认值表达式
 * @param comment         数据库原生注释（走 OID 路径读，不是我们生成的）
 * @param rawType         方言原始类型名（如 {@code VARCHAR(255)}），结构页重建表时保留
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnDef(
        String name,
        ColumnType type,
        boolean nullable,
        boolean primaryKey,
        String defaultValue,
        String comment,
        String enumValues,
        Integer charMaxLength,
        Integer numericPrecision,
        Integer numericScale,
        boolean unique,
        String rawType) {

    /** 兼容 P1/P2 的构造调用；结构内省会补上 {@code rawType}。 */
    public ColumnDef(String name, ColumnType type, boolean nullable, boolean primaryKey,
                     String defaultValue, String comment, String enumValues,
                     Integer charMaxLength, Integer numericPrecision, Integer numericScale,
                     boolean unique) {
        this(name, type, nullable, primaryKey, defaultValue, comment, enumValues,
                charMaxLength, numericPrecision, numericScale, unique, null);
    }

    public String displayType() {
        return rawType == null || rawType.isBlank() ? type.name() : rawType;
    }

    /**
     * ⭐ 渲染成 DDL 里的类型（**带长度 / 精度**）。
     *
     * <p>为什么不能直接用 {@link #rawType()}：JDBC 的
     * {@code ResultSetMetaData.getColumnTypeName()} 给的是**不带长度**的类型名
     * （MySQL 是 {@code VARCHAR}，不是 {@code VARCHAR(120)}）。
     * 直接拿它生成 DDL 会得到 {@code ALTER TABLE t MODIFY COLUMN c VARCHAR NOT NULL}
     * —— MySQL 直接报语法错误。
     *
     * <p>规则：
     * <ul>
     *   <li>原始类型已经带括号（SQLite 的 {@code VARCHAR(64)}）→ 原样用；</li>
     *   <li>变长类型（VARCHAR 家族）→ 补长度；</li>
     *   <li>定点数（DECIMAL / NUMERIC）→ 补精度与小数位；</li>
     *   <li>其余（INT / TEXT / BLOB / TIMESTAMP…）→ 原样用
     *       （TEXT 的 length 可能是 65535，补上去反而非法）。</li>
     * </ul>
     */
    public String ddlType() {
        String raw = rawType == null || rawType.isBlank() ? defaultTypeName() : rawType.trim();
        String upper = raw.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("(")) {
            return raw;
        }
        if (LENGTH_TYPES.contains(upper)) {
            return charMaxLength == null ? raw : raw + "(" + charMaxLength + ")";
        }
        if ("DECIMAL".equals(upper) || "NUMERIC".equals(upper)) {
            if (numericPrecision == null) {
                return raw;
            }
            return numericScale == null
                    ? raw + "(" + numericPrecision + ")"
                    : raw + "(" + numericPrecision + "," + numericScale + ")";
        }
        return raw;
    }

    /** 需要补长度的类型（其余类型补了反而非法）。 */
    private static final java.util.Set<String> LENGTH_TYPES = java.util.Set.of(
            "VARCHAR", "NVARCHAR", "VARBINARY", "CHARACTER VARYING", "VARCHAR2",
            "NCHAR VARYING", "BINARY VARYING");

    /** 没有原始类型时的兜底。 */
    private String defaultTypeName() {
        return switch (type) {
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

    public boolean isEnum() {
        return enumValues != null && !enumValues.isBlank();
    }

    /** 是否需要详情面板（大字段延迟加载只对这类有意义）。 */
    public boolean possiblyLarge() {
        return type.possiblyLarge();
    }
}