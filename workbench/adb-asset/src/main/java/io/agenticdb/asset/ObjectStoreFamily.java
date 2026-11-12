package io.agenticdb.asset;

import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储资产族：S3 / 阿里云 OSS / 七牛 / 腾讯云 COS 四家共用一个
 * {@link AssetKind#OBJECT_STORE} kind，靠 {@link ObjectStoreProvider} 子判别。
 *
 * <p>四家共享 95% 数据模型（bucket / object / prefix），所以合并成一个 kind、四张磁贴，
 * 而不是四个独立 kind（EXT 规划 §0.2 Q2）。后端会话与向导步骤在 P2 落地
 * （{@code adb-objectstore} 模块）。当前 {@link #wizardStepIds()} 为空 = 尚未开放连接。
 */
public final class ObjectStoreFamily implements AssetFamily {

    public static final ObjectStoreFamily INSTANCE = new ObjectStoreFamily();

    private ObjectStoreFamily() {
    }

    @Override
    public List<AssetKind> kinds() {
        return List.of(AssetKind.OBJECT_STORE);
    }

    @Override
    public List<TileDescriptor> tiles() {
        List<TileDescriptor> tiles = new ArrayList<>();
        for (ObjectStoreProvider provider : ObjectStoreProvider.values()) {
            // 品牌作用域类：沿用 .adb-db--<brandKey> 机制，与 SQL 磁贴同口径。
            // 用 provider.styleClass()（完整字面量）而非拼接，让覆盖门禁能校验它。
            String styleClass = provider.styleClass();
            String hintKey = "wizard.tile.objectstore." + provider.id();
            tiles.add(new TileDescriptor(
                    AssetKind.OBJECT_STORE, provider.displayName(), styleClass,
                    hintKey, provider.brandKey(), provider));
        }
        return tiles;
    }

    // connectable() = false：wizardStepIds 为空，向导给 WIP 提示。
}
