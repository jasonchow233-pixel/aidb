package io.agenticdb.model;

/**
 * 新增 / 修改列的输入。
 *
 * <p>放在 {@code adb-model} 而不是结构变更构造器里，是因为<b>方言要生成 DDL</b>：
 * MySQL 把注释写在列定义里、PostgreSQL 用独立的 {@code COMMENT ON} 语句 ——
 * 这些差异只有方言自己知道，构造器不该拿一堆布尔开关去问它。
 *
 * @param name        列名
 * @param rawType     声明类型；空时按 {@code primaryKey} 给默认类型
 * @param nullable    是否可空
 * @param defaultValue 默认值字面量
 * @param comment     列注释
 * @param unique      是否唯一
 * @param primaryKey  是否主键
 * @param autoIncrement 是否自增（MySQL {@code AUTO_INCREMENT} / PG identity / SQLite rowid）
 * @param charset     列级字符集（仅 MySQL 有意义；{@code null} = 跟随表）
 * @param collation   列级排序规则（MySQL / PostgreSQL；{@code null} = 跟随表/库）
 * @param keyLength   索引前缀长度（MySQL；{@code null} = 整列进索引）
 * @param binary      是否加 MySQL 的 {@code BINARY} 列属性
 * @param generatedExpression 生成列表达式（{@code null} = 普通列）；非空时写进
 *                    {@code GENERATED ALWAYS AS (…)}
 */
public record ColumnSpec(
        String name,
        String rawType,
        boolean nullable,
        String defaultValue,
        String comment,
        boolean unique,
        boolean primaryKey,
        boolean autoIncrement,
        String charset,
        String collation,
        String keyLength,
        boolean binary,
        String generatedExpression) {

    /**
     * 旧签名：不带自增与列级字符集。
     *
     * <p>保留它是因为调用点太多（结构页改列、各类测试）；语义与新的 10 参数构造一致 ——
     * 自增默认关、字符集/排序规则默认跟随表。
     */
    public ColumnSpec(String name, String rawType, boolean nullable, String defaultValue,
                      String comment, boolean unique, boolean primaryKey) {
        this(name, rawType, nullable, defaultValue, comment, unique, primaryKey,
                false, null, null);
    }

    /** R31 的 10 参数签名：不带前缀长度 / BINARY / 生成列。 */
    public ColumnSpec(String name, String rawType, boolean nullable, String defaultValue,
                      String comment, boolean unique, boolean primaryKey,
                      boolean autoIncrement, String charset, String collation) {
        this(name, rawType, nullable, defaultValue, comment, unique, primaryKey,
                autoIncrement, charset, collation, null, false, null);
    }

    public ColumnSpec {
        name = name == null ? "" : name.trim();
        rawType = rawType == null ? "" : rawType.trim();
        defaultValue = defaultValue == null || defaultValue.isBlank()
                ? null : defaultValue.trim();
        comment = comment == null || comment.isBlank() ? null : comment.trim();
        charset = charset == null || charset.isBlank() ? null : charset.trim();
        collation = collation == null || collation.isBlank() ? null : collation.trim();
        keyLength = keyLength == null || keyLength.isBlank() ? null : keyLength.trim();
        generatedExpression = generatedExpression == null || generatedExpression.isBlank()
                ? null : generatedExpression.trim();
    }

    /** 是否是生成列（{@code Virtual}）。 */
    public boolean generated() {
        return generatedExpression != null;
    }

    /** 去掉内联 {@code UNIQUE}（前缀唯一键改为表级 {@code UNIQUE (col(n))}）。 */
    public ColumnSpec withoutInlineUnique() {
        if (!unique) {
            return this;
        }
        return new ColumnSpec(name, rawType, nullable, defaultValue, comment, false,
                primaryKey, autoIncrement, charset, collation, keyLength, binary,
                generatedExpression);
    }

    public static ColumnSpec from(ColumnDef column) {
        return new ColumnSpec(column.name(), column.rawType(), column.nullable(),
                column.defaultValue(), column.comment(), column.unique(),
                column.primaryKey());
    }

    public ColumnSpec withName(String value) {
        return new ColumnSpec(value, rawType, nullable, defaultValue, comment, unique,
                primaryKey, autoIncrement, charset, collation, keyLength, binary,
                generatedExpression);
    }

    /** 声明类型；为空时按是否主键给默认类型。 */
    public String effectiveType() {
        return rawType.isBlank() ? (primaryKey ? "INTEGER" : "TEXT") : rawType;
    }

    /** 类型名（去掉长度/精度括号），大写 —— 方言判断「是不是整型」用。 */
    public String baseType() {
        String type = effectiveType();
        int paren = type.indexOf('(');
        String base = (paren < 0 ? type : type.substring(0, paren)).trim();
        return base.toUpperCase(java.util.Locale.ROOT);
    }
}
