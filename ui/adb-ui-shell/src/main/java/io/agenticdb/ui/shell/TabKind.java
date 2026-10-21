package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;

/**
 * 标签类型决定图标与内容页。
 *
 * <p>⭐ 图标是 {@link Icons.Icon} 矢量图，不是 Unicode 符号 ——
 * 符号的字形随系统字体变，标签栏是用户一直在看的地方，不能靠运气。
 */
public enum TabKind {
    GRID(Icons.Icon.TABLE, "数据"),
    QUERY(Icons.Icon.QUERY, "查询"),
    STRUCTURE(Icons.Icon.STRUCTURE, "结构"),
    CONNECTIONS(Icons.Icon.CONNECT, "连接"),
    TOOL(Icons.Icon.DIAGNOSTICS, "工具"),
    SETTINGS(Icons.Icon.SETTINGS, "设置");

    private final Icons.Icon icon;
    private final String label;

    TabKind(Icons.Icon icon, String label) {
        this.icon = icon;
        this.label = label;
    }

    public Icons.Icon icon() {
        return icon;
    }

    public String label() {
        return label;
    }
}
