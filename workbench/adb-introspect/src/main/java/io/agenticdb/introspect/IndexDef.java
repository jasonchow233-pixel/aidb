package io.agenticdb.introspect;

import java.util.List;

/**
 * 索引元数据。{@code origin} 由 SQLite 提供（pk/u/c），其它数据库留空。
 * 结构页只读展示，重建表时用它恢复用户创建的索引。
 */
public record IndexDef(String name, boolean unique, List<String> columns, String origin) {

    public IndexDef {
        name = name == null ? "" : name;
        columns = List.copyOf(columns == null ? List.of() : columns);
        origin = origin == null ? "" : origin;
    }

    public boolean autoCreated() {
        return name.startsWith("sqlite_autoindex")
                || "pk".equalsIgnoreCase(origin)
                || "u".equalsIgnoreCase(origin);
    }

    public String columnsLabel() {
        return String.join(", ", columns);
    }
}
