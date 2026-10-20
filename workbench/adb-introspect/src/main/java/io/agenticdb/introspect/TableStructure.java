package io.agenticdb.introspect;

import io.agenticdb.model.ColumnDef;

import java.util.List;

/** 结构页的数据快照。所有列表都不可变。 */
public record TableStructure(
        String name,
        boolean view,
        List<ColumnDef> columns,
        List<String> primaryKeys,
        List<IndexDef> indexes,
        List<ForeignKeyDef> foreignKeys,
        List<TriggerDef> triggers,
        String ddl) {

    public TableStructure {
        name = name == null ? "" : name;
        columns = List.copyOf(columns == null ? List.of() : columns);
        primaryKeys = List.copyOf(primaryKeys == null ? List.of() : primaryKeys);
        indexes = List.copyOf(indexes == null ? List.of() : indexes);
        foreignKeys = List.copyOf(foreignKeys == null ? List.of() : foreignKeys);
        triggers = List.copyOf(triggers == null ? List.of() : triggers);
        ddl = ddl == null ? "" : ddl;
    }

    public boolean editable() {
        return !view;
    }

    public ColumnDef column(String columnName) {
        return columns.stream()
                .filter(column -> column.name().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
    }

    public String kindLabel() {
        return view ? "视图" : "表";
    }
}
