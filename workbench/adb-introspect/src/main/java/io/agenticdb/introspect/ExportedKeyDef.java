package io.agenticdb.introspect;

import java.util.List;

/**
 * ⭐ P5-4：**被其它表引用的外键**（{@code getExportedKeys}）。
 *
 * <p>{@link ForeignKeyDef} 回答「我的列指向谁」，这个记录回答「谁指向我」——
 * 删表 / 改主键之前，用户必须先知道有哪些子表在引用它。
 *
 * @param name              外键名；SQLite 的内联 REFERENCES 可能没有名字
 * @param childTable        引用本表的子表
 * @param columns           子表上的外键列
 * @param referencedColumns 本表被引用的列
 * @param onUpdate          ON UPDATE 动作
 * @param onDelete          ON DELETE 动作
 */
public record ExportedKeyDef(
        String name,
        String childTable,
        List<String> columns,
        List<String> referencedColumns,
        String onUpdate,
        String onDelete) {

    public ExportedKeyDef {
        name = name == null ? "" : name;
        childTable = childTable == null ? "" : childTable;
        columns = List.copyOf(columns == null ? List.of() : columns);
        referencedColumns = List.copyOf(referencedColumns == null ? List.of() : referencedColumns);
        onUpdate = normalize(onUpdate);
        onDelete = normalize(onDelete);
    }

    public String displayName() {
        return name.isBlank() ? childTable + "_fk" : name;
    }

    private static String normalize(String rule) {
        if (rule == null || rule.isBlank()) {
            return "NO ACTION";
        }
        return rule.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
