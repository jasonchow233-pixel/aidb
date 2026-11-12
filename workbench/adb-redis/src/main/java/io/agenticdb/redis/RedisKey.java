package io.agenticdb.redis;

/**
 * Redis 里一个 key 的轻量视图（供树与 Key 浏览器渲染）。
 *
 * <p>不含值本身 —— 值在「值查看器」里按需拉（见 P1-6），这里只给列表/过滤要的元数据：
 * 类型、TTL、大小。大小按类型取最佳可代表量（string=字节数、list/set/zset/hash=元素数、stream=条目数）。
 *
 * <p>类型常量与 Redis 自身一致（{@code string/list/set/zset/hash/stream/none}），
 * 渲染层直接拿去配图标，不在连接端翻译。
 */
public record RedisKey(
        /** key 名（不含前缀过滤的部分，原样）。 */
        String key,
        /** Redis 类型：string/list/set/zset/hash/stream；库里已删除的瞬时态会是 none（理论上 scan 不会返回）。 */
        String type,
        /** 剩余 TTL（毫秒）；-1 = 不过期；-2 = 不存在（瞬时态，不会出现）。 */
        long ttlMillis,
        /** 大小：string=字节数，其余=元素数。 */
        long size) {
}
