package io.agenticdb.asset;

import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储资产族：S3 / 阿里云 OSS / 七牛 / 腾讯云 COS 四家共用一个
 * {@link AssetKind#OBJECT_STORE} kind，靠 {@link ObjectStoreProvider} 子判别。
 *
 * <p>四家共享 95% 数据模型（bucket / object / prefix），所以合并成一个 kind、四张磁贴，
 * 而不是四个独立 kind（EXT 规划 §0.2 Q2）。后端会话由 {@code adb-objectstore} 模块提供，
 * 向导步骤在 P2-6 落地 —— {@link #wizardStepIds()} 返回非空步骤 id = 已开放连接。
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
            tiles.add(new TileDescriptor(
                    AssetKind.OBJECT_STORE, provider.displayName(), styleClass,
                    hintKey(provider), provider.brandKey(), provider));
        }
        return tiles;
    }

    /**
     * ⚠️ 提示键写成<b>完整字面量</b>而不是 {@code "wizard.tile.objectstore." + id} 拼接：
     * {@code I18nAuditTest.deadKeysAreRatchetedDown()} 只认源码里的字符串字面量，
     * 拼出来的键会被判成「零消费者的死键」。四家一处登记，也免得资源包与枚举漂移。
     */
    private static String hintKey(ObjectStoreProvider provider) {
        return switch (provider) {
            case S3 -> "wizard.tile.objectstore.s3";
            case OSS -> "wizard.tile.objectstore.oss";
            case QINIU -> "wizard.tile.objectstore.qiniu";
            case COS -> "wizard.tile.objectstore.cos";
        };
    }

    /**
     * ⭐ P2-6：对象存储向导页已落地（provider 子表单 + endpoint/region/bucket/凭据）。
     * 返回非空步骤 id 后 {@link #connectable()} 自动为 true，向导据此渲染真实控件，
     * 不再摆「尚未开放」的假提示。
     */
    @Override
    public List<String> wizardStepIds() {
        return List.of("objectstore");
    }
}
