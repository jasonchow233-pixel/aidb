package io.agenticdb.asset;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 资产族注册表 —— 「加一种连接类型 = 登记一处」的单一真相源。
 *
 * <p>UI 的向导磁贴、品牌图形、向导步骤都从这里取，不再各自硬编码一份清单。
 * 启动时由应用装配代码（{@code AppShell}）调用 {@link #register(AssetFamily)} 登记全部已知族；
 * 旧 {@code ConnectionCatalog} 的 6 行 SQL 登记收敛进 {@link SqlFamily}。
 *
 * <p>线程安全：登记发生在启动期，但用 {@code synchronized} 兜底，防止后续热注册踩踏。
 */
public final class AssetFamilyRegistry {

    private static final Map<AssetKind, AssetFamily> BY_KIND = new EnumMap<>(AssetKind.class);
    private static final List<AssetFamily> ALL = new ArrayList<>();
    private static final List<TileDescriptor> TILES = new ArrayList<>();

    private AssetFamilyRegistry() {
    }

    /**
     * ⭐ EXT（P0/P1/P2）：类加载期自登记已知族，避免「生产代码要先于 AppShell 启动去
     * register」的时序竞态 —— {@link ConnectionCatalog} 在静态初始化时就读 {@link #tiles()}，
     * 那时 AppShell 还没跑。三个族（SQL / Redis / 对象存储）全部在这里一处登记，
     * 向导磁贴、树分支、品牌图自动跟随（加一种连接类型 = 只改这里）。
     */
    static {
        registerDefaults();
    }

    /**
     * 登记全部内置资产族（幂等）。类初始化与 {@code reset()} 之后的门禁都走这里，
     * 于是「默认登记了什么」这件事可测，而不是藏在只跑一次的静态块里。
     */
    public static void registerDefaults() {
        register(SqlFamily.INSTANCE);
        register(RedisFamily.INSTANCE);
        register(ObjectStoreFamily.INSTANCE);
    }

    public static synchronized void register(AssetFamily family) {
        for (AssetKind kind : family.kinds()) {
            BY_KIND.put(kind, family);
        }
        if (!ALL.contains(family)) {
            ALL.add(family);
            TILES.addAll(family.tiles());
        }
    }

    /** 这个资产种类归哪个族管（认不出返回 {@code null}）。 */
    public static AssetFamily forKind(AssetKind kind) {
        return kind == null ? null : BY_KIND.get(kind);
    }

    public static List<AssetFamily> families() {
        return List.copyOf(ALL);
    }

    /** 全部磁贴（按登记顺序扁平化），连接向导与类型选择器直接用。 */
    public static List<TileDescriptor> tiles() {
        return List.copyOf(TILES);
    }

    /** 测试用：清空登记（每个测试类独立登记，避免跨测试污染）。 */
    public static synchronized void reset() {
        BY_KIND.clear();
        ALL.clear();
        TILES.clear();
    }
}
