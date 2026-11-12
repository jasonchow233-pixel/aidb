package io.agenticdb.redis;

/**
 * Redis 一个 key 的值视图（P1-6 值查看/编辑用）。
 *
 * <p>类型与 Redis 自身一致（{@code string/list/set/zset/hash/stream}），渲染层按
 * {@link #type()} 分发到不同的编辑器；这里只携带「足够查看 + 编辑」的数据，不携带 TTL
 * （TTL 在会话里单独取，见 {@link RedisSession#ttl}）。
 *
 * <p>用 sealed 接口分发，避免渲染层写 {@code instanceof} 链 + 一堆 null 字段。
 * 各子类型与辅助记录各自独立成文件（public，供 UI 层跨包访问）。
 */
public sealed interface RedisValue
        permits RedisString, RedisList, RedisSet, RedisHash, RedisZSet, RedisStream {

    /** Redis 类型字符串：string / list / set / zset / hash / stream。 */
    String type();
}
