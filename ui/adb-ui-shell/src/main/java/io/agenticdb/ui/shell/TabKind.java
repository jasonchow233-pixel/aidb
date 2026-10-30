package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;

/**
 * 标签类型决定图标与内容页。
 *
 * <p>⭐ 图标是 {@link Icons.Icon} 矢量图，不是 Unicode 符号 ——
 * 符号的字形随系统字体变，标签栏是用户一直在看的地方，不能靠运气。
 */
public enum TabKind {
    GRID(Icons.Icon.TABLE, "grid.tab.data"),
    QUERY(Icons.Icon.QUERY, "category.query"),
    STRUCTURE(Icons.Icon.STRUCTURE, "grid.tab.structure"),
    CONNECTIONS(Icons.Icon.CONNECT, "window.pageType.connections"),
    TOOL(Icons.Icon.DIAGNOSTICS, "category.tools"),
    /** ⭐ S5：常驻的「对象」首页标签（Navicat 形态，不可关闭）。 */
    OBJECT(Icons.Icon.DATABASE, "category.object"),
    /** ⭐ O2：函数 / 存储过程的**定义体**（只读标签页）。 */
    ROUTINE(Icons.Icon.FUNCTION, "tab.kind.routine"),
    SETTINGS(Icons.Icon.SETTINGS, "window.pageType.settings");

    private final Icons.Icon icon;
    private final String labelKey;

    TabKind(Icons.Icon icon, String labelKey) {
        this.icon = icon;
        this.labelKey = labelKey;
    }

    public Icons.Icon icon() {
        return icon;
    }

    /** 渲染时才解析 —— 徽标、页签、标题栏共用这一份，不留第二套文案。 */
    public String label() {
        return I18n.t(labelKey);
    }
}
