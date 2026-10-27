package io.agenticdb.introspect;

import java.util.List;

/**
 * 索引元数据。{@code origin} 由 SQLite 提供（pk/u/c），其它数据库留空。
 * 结构页只读展示，重建表时用它恢复用户创建的索引。
 *
 * <p>{@code type} / {@code descending} 是<b>用户在弹窗里选的可选属性</b>，
 * 内省拿到的既有索引都用 4 参构造 —— 也就是「类型随默认、列全升序」，
 * 不会凭空给一个只读索引编出属性来（方言不支持时，UI 根本不摆这两个控件）。
 */
public record IndexDef(String name, boolean unique, List<String> columns, String origin,
                       String type, List<String> descending) {

    /** 既有调用方 / 内省结果的口径：没有类型选择、没有降序列。 */
    public IndexDef(String name, boolean unique, List<String> columns, String origin) {
        this(name, unique, columns, origin, "", List.of());
    }

    public IndexDef {
        name = name == null ? "" : name;
        columns = List.copyOf(columns == null ? List.of() : columns);
        origin = origin == null ? "" : origin;
        type = type == null ? "" : type;
        descending = List.copyOf(descending == null ? List.of() : descending);
    }

    /** 这一列在索引里是不是降序。 */
    public boolean isDescending(String column) {
        return descending.contains(column);
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
