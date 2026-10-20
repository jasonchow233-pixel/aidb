package io.agenticdb.ui.shell;

/** 标签类型决定图标与内容页。 */
public enum TabKind {
    GRID("▦", "数据"),
    QUERY("⌨", "查询"),
    STRUCTURE("⚙", "结构"),
    CONNECTIONS("◈", "连接"),
    TOOL("◆", "工具"),
    SETTINGS("⚙", "设置");

    private final String icon;
    private final String label;

    TabKind(String icon, String label) {
        this.icon = icon;
        this.label = label;
    }

    public String icon() {
        return icon;
    }

    public String label() {
        return label;
    }
}
