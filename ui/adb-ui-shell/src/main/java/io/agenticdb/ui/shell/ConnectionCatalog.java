package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.Icons;

import java.util.List;

/**
 * 连接类型页的唯一清单：{@link DialectKind} 里那六种<b>真带驱动</b>的数据库。
 *
 * <p>⭐ R43-b：这里原来照 Navicat 17 摆 22 张磁贴（9 类型 + 13 厂商），其中 16 张
 * 没有驱动、点进去是一份「本版不支持」的说明。那不等于 Navicat 的形态 ——
 * Navicat 摆出这些家是因为它真连得上去；我们摆出来只是让用户多点十六次空。
 * 磁贴收成六张之后，类型页不再需要过滤、搜索与滚动（见 {@code ConnectionWizard}）。
 *
 * <p>{@code styleClass} 把厂商作用域类（{@code .adb-db--*}）交给 CSS 选品牌色，
 * 向导磁贴与导航树的连接节点取同一行，所以同一个 MySQL 在两处是同一个图形。
 *
 * <p>厂商<b>图形</b>也登记在这一行上（{@link Entry#glyph()}）。放这里而不是
 * 另开一个「方言 → 图标」的 switch，是为了守住 A1 的承诺
 * （{@code DialectBranchAuditTest}）：加一种数据库就是在这里加一行，别处不用改。
 */
final class ConnectionCatalog {

    record Entry(String id, String name, DialectKind kind, String styleClass,
                 String hintKey, Icons.Icon glyph) {
    }

    private ConnectionCatalog() {
    }

    /** 顺序与 {@link DialectKind} 的登记一致，磁贴按 3 列两行摆。 */
    static final List<Entry> CONNECTIONS = List.of(
            entry("MYSQL", "MySQL", DialectKind.MYSQL, "adb-db--mysql", "wizard.tile.mysql",
                    Icons.Icon.DB_MYSQL),
            entry("POSTGRESQL", "PostgreSQL", DialectKind.POSTGRESQL, "adb-db--postgresql",
                    "wizard.tile.postgresql", Icons.Icon.DB_POSTGRESQL),
            entry("SQLSERVER", "SQL Server", DialectKind.SQLSERVER, "adb-db--sqlserver",
                    "wizard.tile.sqlserver", Icons.Icon.DB_SQLSERVER),
            entry("ORACLE", "Oracle", DialectKind.ORACLE, "adb-db--oracle", "wizard.tile.oracle",
                    Icons.Icon.DB_ORACLE),
            entry("SQLITE", "SQLite", DialectKind.SQLITE, "adb-db--sqlite", "wizard.tile.sqlite",
                    Icons.Icon.DB_SQLITE),
            entry("MARIADB", "MariaDB", DialectKind.MARIADB, "adb-db--mariadb",
                    "wizard.tile.mariadb", Icons.Icon.DB_MARIADB));

    /**
     * 该方言登记的那一行；没有方言或登记不到时返回 {@code null}，调用方自己回落。
     *
     * <p>导航树的连接节点用它取厂商图形 —— 树只知道连接档案里的 {@code kind}，
     * 图形与品牌色类都从同一行拿，保证「向导里的 MySQL」和「树里的 MySQL」是同一个图形。
     */
    static Entry forKind(DialectKind kind) {
        if (kind == null) {
            return null;
        }
        for (Entry entry : CONNECTIONS) {
            if (kind.equals(entry.kind())) {
                return entry;
            }
        }
        return null;
    }

    private static Entry entry(String id, String name, DialectKind kind, String styleClass,
                               String hintKey, Icons.Icon glyph) {
        return new Entry(id, name, kind, styleClass, hintKey, glyph);
    }

    /**
     * ⭐ R54：该方言的**厂商图形**（分层上色 + 品牌色作用域类已经挂好），拿不到就交回
     * {@code null} 由调用方用自己的兜底图形。
     *
     * <p>为什么要收成这一个方法：「取字形 → 上彩色 → 挂 {@code .adb-db--*}」这三步
     * 此前只在导航树里内联做过一次，其余说得出方言的表面（连接管理器、状态栏、页签、
     * 库选择器）各自退回通用图形 + 文字。表面之间分叉过一次就是 D-38 那个形状 ——
     * 同一个 MySQL 在两个地方长得不一样，而没有任何一处会报错。
     *
     * <p>返回 {@code null} 而不是「随便给一枚」是刻意的：认不出方言就是「不知道是哪家」，
     * 画成某家的图形就是编造。
     */
    static javafx.scene.Node graphic(DialectKind kind, double size) {
        Entry entry = forKind(kind);
        if (entry == null) {
            return null;
        }
        javafx.scene.Node icon = Icons.colored(entry.glyph(), size);
        icon.getStyleClass().add(entry.styleClass());
        return icon;
    }

    /**
     * 该方言的品牌色作用域类；认不出方言时是<b>空串</b>（不是 null，调用方直接判空即可）。
     *
     * <p>给「形状要留着自己的、颜色要跟那一家走」的表面用 —— 导航树的库节点顶的是圆柱
     * （形状说的是「这是个库」），但 MySQL 的库和 PostgreSQL 的库不该长同一个颜色。
     */
    static String styleClass(DialectKind kind) {
        Entry entry = forKind(kind);
        return entry == null ? "" : entry.styleClass();
    }
}
