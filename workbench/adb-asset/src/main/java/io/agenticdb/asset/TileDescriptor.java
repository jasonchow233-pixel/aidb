package io.agenticdb.asset;

import java.util.Locale;

/**
 * 连接向导里的「一张磁贴」的纯数据描述。
 *
 * <p>UI 层只问 {@link AssetFamily} 要磁贴，不问「这是哪一家」—— 这样加一种连接类型只改
 * 资产族登记，向导磁贴自动出现（同 {@code ConnectionCatalog} 的老承诺，只是判别轴换成了
 * {@link AssetKind}）。
 *
 * <p>{@code brandKey} 与 {@code styleClass} 交给 CSS 选品牌色（沿用 {@code .adb-db--*}
 * 那套作用域类机制）；{@code provider} 仅对象存储磁贴非空。
 */
public record TileDescriptor(
        /** 资产种类（SQL 的六种之一 / REDIS / OBJECT_STORE）。 */
        AssetKind kind,
        /** 磁贴显示名（产品名，正常大小写）。 */
        String name,
        /** 品牌作用域类（{@code .adb-db--mysql} 之类），用于取厂商色。 */
        String styleClass,
        /** i18n 提示键（{@code wizard.tile.<id>}）。 */
        String hintKey,
        /** 品牌图形资产名（{@code icons/brand/<brandKey>.svg}）。 */
        String brandKey,
        /** 对象存储子判别；SQL / Redis 磁贴为 {@code null}。 */
        ObjectStoreProvider provider) {

    /** 磁贴的稳定 id：对象存储用 {@code object_store:<provider>}，其余用资产 kind 的 id。 */
    public String tileId() {
        if (provider != null) {
            return kind.id() + ":" + provider.id();
        }
        return kind.id();
    }

    /** 品牌图形资产名兜底：对象存储用 provider 的 brandKey。 */
    public String effectiveBrandKey() {
        return brandKey != null ? brandKey : (provider != null ? provider.brandKey() : kind.id());
    }

    /**
     * 厂商商标资产名（小写），同旧 {@code ConnectionCatalog.Entry#brandKey()} 口径。
     */
    public String vendorBrandKey() {
        return effectiveBrandKey().toLowerCase(Locale.ROOT);
    }
}
