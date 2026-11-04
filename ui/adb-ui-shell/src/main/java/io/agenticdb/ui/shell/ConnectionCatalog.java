package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.Icons;

import java.util.List;
import java.util.Locale;

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
 * <p>厂商<b>图形</b>也登记在这一行上（{@link Entry#brandKey()}）。放这里而不是
 * 另开一个「方言 → 图标」的 switch，是为了守住 A1 的承诺
 * （{@code DialectBranchAuditTest}）：加一种数据库就是在这里加一行，别处不用改。
 */
final class ConnectionCatalog {

    record Entry(String id, String name, DialectKind kind, String styleClass,
                 String hintKey) {

        /**
         * ⭐ R63-3：这一家的<b>商标资产名</b>（{@code icons/brand/<key>.svg}）。
         *
         * <p>从 {@code id} 推导而不是再登记一遍：id 已经是 {@code MYSQL}/{@code SQLSERVER}
         * 这种唯一键，再加一列就是给「两处不同步」留位置。
         */
        String brandKey() {
            return id.toLowerCase(Locale.ROOT);
        }
    }

    private ConnectionCatalog() {
    }

    /** 顺序与 {@link DialectKind} 的登记一致，磁贴按 3 列两行摆。 */
    static final List<Entry> CONNECTIONS = List.of(
            entry("MYSQL", "MySQL", DialectKind.MYSQL, "adb-db--mysql", "wizard.tile.mysql"),
            entry("POSTGRESQL", "PostgreSQL", DialectKind.POSTGRESQL, "adb-db--postgresql",
                    "wizard.tile.postgresql"),
            entry("SQLSERVER", "SQL Server", DialectKind.SQLSERVER, "adb-db--sqlserver",
                    "wizard.tile.sqlserver"),
            entry("ORACLE", "Oracle", DialectKind.ORACLE, "adb-db--oracle", "wizard.tile.oracle"),
            entry("SQLITE", "SQLite", DialectKind.SQLITE, "adb-db--sqlite", "wizard.tile.sqlite"),
            entry("MARIADB", "MariaDB", DialectKind.MARIADB, "adb-db--mariadb",
                    "wizard.tile.mariadb"));

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
                               String hintKey) {
        return new Entry(id, name, kind, styleClass, hintKey);
    }

    /**
     * ⭐ R54 / R63-3：该方言的<b>厂商商标图形</b>（官方色已经拍进路径里），拿不到就交回
     * {@code null} 由调用方用自己的兜底图形。
     *
     * <p>为什么要收成这一个方法：「取图形 → 上色 → 挂类」这三步此前只在导航树里内联做过
     * 一次，其余说得出方言的表面（连接管理器、状态栏、页签、库选择器）各自退回通用图形
     * + 文字。表面之间分叉过一次就是 D-38 那个形状 ——
     * 同一个 MySQL 在两个地方长得不一样，而没有任何一处会报错。
     *
     * <p>返回 {@code null} 而不是「随便给一枚」是刻意的：认不出方言就是「不知道是哪家」，
     * 画成某家的图形就是编造。
     *
     * <p>R63-3 起这里给的是<b>官方标志</b>（{@code icons/brand/}，字面官方色），不再是
     * 自绘的同风格图形；作用域类照旧挂上 —— 库节点那种「形状是自己的、颜色跟厂走」的
     * 表面还吃它，磁贴自己也要它（见 {@code ConnectionWizard}）。
     *
     * <p>外面套的那层 {@code adb-brand-plate} 不是装饰：官方色不许改（各家商标条款），
     * 而 MySQL 的深青、MariaDB 的海豚压在暗色档的侧栏底上只有 1.2～1.9:1，等于看不见。
     * 于是改「它坐的表面」而不是改「它的颜色」—— 一层浅色底板，标志本身一个像素没动。
     */
    static javafx.scene.Node graphic(DialectKind kind, double size) {
        Entry entry = forKind(kind);
        if (entry == null) {
            return null;
        }
        javafx.scene.Node mark = Icons.brand(entry.brandKey(), size);
        if (mark == null) {
            return null;
        }
        javafx.scene.layout.StackPane plate = new javafx.scene.layout.StackPane(mark);
        // 槽位锁成 size×size：树行的缩进全靠图标盒子那一格的宽度（见 H5-k 的学费）。
        plate.setMinSize(size, size);
        plate.setPrefSize(size, size);
        plate.setMaxSize(size, size);
        plate.getStyleClass().addAll("adb-brand-plate", entry.styleClass());
        return plate;
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
