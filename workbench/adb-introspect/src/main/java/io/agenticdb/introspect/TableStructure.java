package io.agenticdb.introspect;

import io.agenticdb.model.ColumnDef;

import java.util.List;

/**
 * 结构页的数据快照。所有列表都不可变。
 *
 * <p>⭐ {@code checks} 是第十一轮补上的：以前**没有 CHECK 约束的概念**，
 * 于是 SQLite 改列整表重建时，约束被静默丢掉（TODO D3）。
 */
public record TableStructure(
        String name,
        boolean view,
        List<ColumnDef> columns,
        List<String> primaryKeys,
        List<IndexDef> indexes,
        List<ForeignKeyDef> foreignKeys,
        List<TriggerDef> triggers,
        List<CheckDef> checks,
        String comment,
        String ddl) {

    /**
     * 兼容构造器：没有 CHECK 约束的表（老调用方不必改）。
     */
    public TableStructure(String name, boolean view, List<ColumnDef> columns,
                          List<String> primaryKeys, List<IndexDef> indexes,
                          List<ForeignKeyDef> foreignKeys, List<TriggerDef> triggers,
                          String ddl) {
        this(name, view, columns, primaryKeys, indexes, foreignKeys, triggers,
                List.of(), "", ddl);
    }

    /** 没有 CHECK、没有表注释的表（老调用方）。 */
    public TableStructure(String name, boolean view, List<ColumnDef> columns,
                          List<String> primaryKeys, List<IndexDef> indexes,
                          List<ForeignKeyDef> foreignKeys, List<TriggerDef> triggers,
                          List<CheckDef> checks, String ddl) {
        this(name, view, columns, primaryKeys, indexes, foreignKeys, triggers,
                checks, "", ddl);
    }

    public TableStructure {
        name = name == null ? "" : name;
        columns = List.copyOf(columns == null ? List.of() : columns);
        primaryKeys = List.copyOf(primaryKeys == null ? List.of() : primaryKeys);
        indexes = List.copyOf(indexes == null ? List.of() : indexes);
        foreignKeys = List.copyOf(foreignKeys == null ? List.of() : foreignKeys);
        triggers = List.copyOf(triggers == null ? List.of() : triggers);
        checks = List.copyOf(checks == null ? List.of() : checks);
        comment = comment == null ? "" : comment;
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
