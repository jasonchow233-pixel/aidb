package io.agenticdb.asset;

import java.util.List;

/**
 * Redis 资产族。
 *
 * <p>Redis 不是 SQL，没有方言。本族声明「一张磁贴 + 一个 kind + 一个向导步骤」，
 * 后端会话由 {@code adb-redis} 模块的 {@code RedisSessions} 提供（P1-1 / P1-2 已落地）。
 * 向导步骤 {@code "redis"} 由 {@link io.agenticdb.ui.shell.ConnectionWizard} 渲染为
 * host / port / auth / db / SSL / SSH 表单。
 */
public final class RedisFamily implements AssetFamily {

    public static final RedisFamily INSTANCE = new RedisFamily();

    private RedisFamily() {
    }

    @Override
    public List<AssetKind> kinds() {
        return List.of(AssetKind.REDIS);
    }

    @Override
    public List<TileDescriptor> tiles() {
        return List.of(new TileDescriptor(
                AssetKind.REDIS, "Redis", "adb-db--redis",
                "wizard.tile.redis", "redis", null));
    }

    @Override
    public List<String> wizardStepIds() {
        return List.of("redis");
    }
}
