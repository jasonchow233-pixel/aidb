package io.agenticdb.introspect;

import java.util.List;

/** 外键（含复合键）。{@code name} 在 SQLite 里可能为空，因为内联 REFERENCES 没有名字。 */
public record ForeignKeyDef(
        String name,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns,
        String onUpdate,
        String onDelete) {

    public ForeignKeyDef {
        name = name == null ? "" : name;
        columns = List.copyOf(columns == null ? List.of() : columns);
        referencedTable = referencedTable == null ? "" : referencedTable;
        referencedColumns = List.copyOf(referencedColumns == null ? List.of() : referencedColumns);
        onUpdate = normalize(onUpdate);
        onDelete = normalize(onDelete);
    }

    public String displayName() {
        return name.isBlank() ? columns.stream().reduce((a, b) -> a + "_" + b).orElse("fk")
                + "_" + referencedTable : name;
    }

    private static String normalize(String rule) {
        if (rule == null || rule.isBlank()) {
            return "NO ACTION";
        }
        return rule.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
