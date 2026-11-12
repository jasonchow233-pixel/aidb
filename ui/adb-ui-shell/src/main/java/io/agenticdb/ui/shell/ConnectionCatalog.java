package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetFamilyRegistry;
import io.agenticdb.asset.AssetKind;
import io.agenticdb.asset.TileDescriptor;
import io.agenticdb.ui.Icons;

import java.util.List;
import java.util.Locale;

/**
 * 连接类型页的磁贴清单 —— 现在从 {@link AssetFamilyRegistry} / {@link io.agenticdb.asset.SqlFamily}
 * 读，不再硬编码。
 *
 * <p>⭐ EXT（P0-2）：旧版这里写死 6 个 {@code DialectKind} 磁贴。现在判别轴提到
 * {@link AssetKind}，磁贴由资产族登记提供 —— 加一种连接类型只改族登记（见
 * {@code AssetFamilyRegistry}），这里与向导自动跟随。
 *
 * <p>{@code styleClass} 把厂商作用域类（{@code .adb-db--*}）交给 CSS 选品牌色，
 * 向导磁贴与导航树的连接节点取同一行，所以同一个 MySQL 在两处是同一个图形。
 *
 * <p>厂商<b>图形</b>也登记在这一行上（{@link Entry#brandKey()}）。放这里而不是
 * 另开一个「方言 → 图标」的 switch，是为了守住 A1 的承诺
 * （{@code DialectBranchAuditTest}）：加一种数据库就是在这里加一行，别处不用改。
 */
final class ConnectionCatalog {

    record Entry(String id, String name, AssetKind kind, String styleClass,
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

    /**
     * 磁贴清单（顺序即展示顺序）。
     *
     * <p>⭐ EXT：来源从硬编码 6 项改为资产族登记。当前取 SQL 族（6 家）；Redis / 对象存储
     * 四家在 P1 / P2 落地向导后由 {@link AssetFamilyRegistry#tiles()} 一并给出，
     * 这里只换数据源即可（见 {@link #source()}）。
     */
    static final List<Entry> CONNECTIONS = source().stream()
            .map(t -> new Entry(t.tileId(), t.name(), t.kind(), t.styleClass(), t.hintKey()))
            .toList();

    /** 磁贴数据源：单一真相源 = 资产族注册表（{@link AssetFamilyRegistry} 自登记 SQL + Redis）。 */
    private static List<TileDescriptor> source() {
        return AssetFamilyRegistry.tiles();
    }

    /**
     * 该资产种类登记的那一行；没有或登记不到时返回 {@code null}，调用方自己回落。
     *
     * <p>导航树的连接节点用它取厂商图形 —— 树只知道连接档案里的 {@code kind}，
     * 图形与品牌色类都从同一行拿，保证「向导里的 MySQL」和「树里的 MySQL」是同一个图形。
     */
    static Entry forKind(AssetKind kind) {
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

    /**
     * ⭐ R54 / R63-3：该方言的<b>厂商商标图形</b>（官方色已经拍进路径里），拿不到就交回
     * {@code null} 由调用方用自己的兜底图形。
     *
     * <p>返回 {@code null} 而不是「随便给一枚」是刻意的：认不出类型就是「不知道是哪家」，
     * 画成某家的图形就是编造。
     */
    static javafx.scene.Node graphic(AssetKind kind, double size) {
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
     * 该类型的品牌色作用域类；认不出类型时是<b>空串</b>（不是 null，调用方直接判空即可）。
     *
     * <p>给「形状要留着自己的、颜色要跟那一家走」的表面用 —— 导航树的库节点顶的是圆柱
     * （形状说的是「这是个库」），但 MySQL 的库和 PostgreSQL 的库不该长同一个颜色。
     */
    static String styleClass(AssetKind kind) {
        Entry entry = forKind(kind);
        return entry == null ? "" : entry.styleClass();
    }
}
