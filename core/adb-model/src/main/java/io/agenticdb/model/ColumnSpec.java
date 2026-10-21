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
 */
public record ColumnSpec(
        String name,
        String rawType,
        boolean nullable,
        String defaultValue,
        String comment,
        boolean unique,
        boolean primaryKey) {

    public ColumnSpec {
        name = name == null ? "" : name.trim();
        rawType = rawType == null ? "" : rawType.trim();
        defaultValue = defaultValue == null || defaultValue.isBlank()
                ? null : defaultValue.trim();
        comment = comment == null || comment.isBlank() ? null : comment.trim();
    }

    public static ColumnSpec from(ColumnDef column) {
        return new ColumnSpec(column.name(), column.rawType(), column.nullable(),
                column.defaultValue(), column.comment(), column.unique(),
                column.primaryKey());
    }

    public ColumnSpec withName(String value) {
        return new ColumnSpec(value, rawType, nullable, defaultValue, comment, unique,
                primaryKey);
    }

    /** 声明类型；为空时按是否主键给默认类型。 */
    public String effectiveType() {
        return rawType.isBlank() ? (primaryKey ? "INTEGER" : "TEXT") : rawType;
    }
}
