package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetFamilyRegistry;
import io.agenticdb.asset.AssetKind;
import io.agenticdb.asset.ObjectStoreProvider;
import io.agenticdb.asset.TileDescriptor;
import io.agenticdb.ui.Icons;

import java.util.List;
import java.util.Locale;

/**
 * 连接类型页的磁贴清单 —— 现在从 {@link AssetFamilyRegistry} 读，不再硬编码。
 *
 * <p>⭐ EXT（P0-2 / P2-6）：旧版这里写死 6 个 {@code DialectKind} 磁贴。现在判别轴提到
 * {@link AssetKind}，磁贴由资产族登记提供 —— 加一种连接类型只改族登记，这里与向导自动跟随。
 *
 * <p>⭐ P2-6 修正：{@link Entry} 现在<b>保留</b> {@link TileDescriptor#provider()} 与
 * {@code brandAsset}。对象存储是一个 kind 挂四张磁贴，只靠 {@code kind} 反查会把四家
 * 都画成第一家的商标，也会把四张磁贴当成同一个选择。向导按 {@link Entry#id()} 精确定位，
 * 品牌图按 {@link Entry#brandKey()} 取该家自己的资产。
 *
 * <p>厂商<b>图形</b>也登记在这一行上。放这里而不是另开一个「方言 → 图标」的 switch，
 * 是为了守住 A1 的承诺（{@code DialectBranchAuditTest}）：加一种数据库就是在这里加一行，
 * 别处不用改；商标只从 {@link #graphic} 这个唯一出口取（{@code VendorGlyphTest} 守着）。
 */
final class ConnectionCatalog {

    record Entry(String id, String name, AssetKind kind, String styleClass,
                 String hintKey, String brandAsset, ObjectStoreProvider provider) {

        /** 这一家的商标资产名（{@code icons/brand/<key>.svg}），全部小写。 */
        String brandKey() {
            String source = brandAsset == null || brandAsset.isBlank() ? id : brandAsset;
            return source.toLowerCase(Locale.ROOT);
        }
    }

    private ConnectionCatalog() {
    }

    /**
     * 磁贴清单（顺序即展示顺序）：6 SQL + 1 Redis + 4 对象存储 = 11 张。
     * 数据源是资产族注册表的单一真相源。
     */
    static final List<Entry> CONNECTIONS = source().stream()
            .map(t -> new Entry(t.tileId(), t.name(), t.kind(), t.styleClass(),
                    t.hintKey(), t.effectiveBrandKey(), t.provider()))
            .toList();

    private static List<TileDescriptor> source() {
        return AssetFamilyRegistry.tiles();
    }

    /** 按磁贴 id 精确定位（对象存储四张同 kind，必须用 id 而不是 kind）。 */
    static Entry forTileId(String tileId) {
        if (tileId == null) {
            return null;
        }
        for (Entry entry : CONNECTIONS) {
            if (tileId.equals(entry.id())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 该资产种类登记的第一行；没有或登记不到时返回 {@code null}，调用方自己回落。
     *
     * <p>导航树的连接节点用它取厂商图形。对象存储有多个 provider 时应用
     * {@link #forTileId}（按连接档案里的 provider 精确取），这里只作 kind 级兜底。
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
     * 按具体磁贴行取该家的商标图形（对象存储四家各一枚）。
     *
     * <p>方法名刻意不叫 {@code graphic}：{@code graphic(AssetKind, double)} 的现有契约
     * 允许传 {@code null}（认不出时交回 null），多一个引用类型重载会让
     * {@code graphic(null, size)} 变成编译期歧义。两者语义也不同 —— 一个是
     * 「按种类兜底」，一个是「按具体磁贴行精确取图」。
     */
    static javafx.scene.Node graphicFor(Entry entry, double size) {
        if (entry == null) {
            return null;
        }
        javafx.scene.Node mark = Icons.brand(entry.brandKey(), size);
        if (mark == null) {
            return null;
        }
        javafx.scene.layout.StackPane plate = new javafx.scene.layout.StackPane(mark);
        plate.setMinSize(size, size);
        plate.setPrefSize(size, size);
        plate.setMaxSize(size, size);
        plate.getStyleClass().addAll("adb-brand-plate", entry.styleClass());
        return plate;
    }

    /** 按资产种类取图形（kind 级兜底；对象存储请用 {@link #graphicFor(Entry, double)}）。 */
    static javafx.scene.Node graphic(AssetKind kind, double size) {
        return graphicFor(forKind(kind), size);
    }

    /**
     * 该类型的品牌色作用域类；认不出类型时是<b>空串</b>（不是 null，调用方直接判空即可）。
     */
    static String styleClass(AssetKind kind) {
        Entry entry = forKind(kind);
        return entry == null ? "" : entry.styleClass();
    }
}
