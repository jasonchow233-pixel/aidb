package io.agenticdb.redis;

import java.util.List;
import java.util.Map;

/**
 * Redis 后端会话（基于 Lettuce，P1 落地）。
 *
 * <p>连接 / 鉴权 / 选库 / SSL 由 {@link RedisSessions#connect} 建立；所有方法都在「当前已选中的库」
 * 语义下工作 —— 调用方显式传 {@code db}，会话内部会先 {@code SELECT} 再操作，互不串库。
 *
 * <p>写操作（值提交 / DEL / FLUSHDB / FLUSHALL）统一走 SQL 已有的三级门禁：
 * 服务端分级（{@link RedisDanger}）→ 用户确认（{@code DestructiveConfirm}）→ 这里只负责执行。
 * 会话自身不做确认拦截，确认在 UI 层完成，避免「能写却没确认」的假安全，也避免「拦了又不说清等级」。
 */
public interface RedisSession extends AutoCloseable {

    /**
     * 有 key 的库下标（{@code INFO keyspace} 过滤掉空库）。
     *
     * <p>Redis 默认 16 个库，但大多空着；只返回实际有数据的，避免树上一长串空节点。
     */
    List<Integer> databases();

    /**
     * 列出某库下的 key 视图。
     *
     * @param db         库下标（0..15）
     * @param prefix     前缀过滤；{@code null}/空串表示不过滤（匹配 {@code *}）
     * @param typeFilter 类型过滤（{@code string/list/set/zset/hash/stream}）；{@code null}/空串表示不过滤
     * @return key 视图列表，每个带类型 / TTL / 大小
     */
    List<RedisKey> scan(int db, String prefix, String typeFilter);

    // ────────────────────────────────────────────────────────────
    // P1-6：值查看 / 编辑 / TTL / 危险操作
    // 等级映射见 RedisDanger；UI 据此弹确认框。
    // ────────────────────────────────────────────────────────────

    /** 取一个 key 的值（按类型返回对应 {@link RedisValue} 子类型）。key 不存在返回 {@code null}。 */
    RedisValue get(int db, String key);

    /** 剩余 TTL（毫秒）；-1 = 不过期；-2 = key 不存在。 */
    long ttl(int db, String key);

    /** 设置 TTL（毫秒）。{@code Danger.MUTATING} —— UI 弹写确认。 */
    void expire(int db, String key, long ttlMillis);

    /** 清除 TTL（永不过期）。{@code Danger.MUTATING}。 */
    void persist(int db, String key);

    /** 写入 string 值（覆盖）。{@code Danger.MUTATING}。 */
    void set(int db, String key, String value);

    // —— 集合元素的增量编辑（均 MUTATING）——

    /** list：尾部追加一个元素。 */
    void listAppend(int db, String key, String element);

    /** list：按索引覆盖一个元素（LSET）。 */
    void listSetAt(int db, String key, int index, String value);

    /** list：删除第一个等于 value 的元素（LREM count=1）。 */
    void listRemove(int db, String key, String value);

    /** set：添加一个成员。 */
    void setAdd(int db, String key, String member);

    /** set：删除一个成员。 */
    void setRemove(int db, String key, String member);

    /** hash：写入一个字段。 */
    void hashPut(int db, String key, String field, String value);

    /** hash：删除一个字段。 */
    void hashRemove(int db, String key, String field);

    /** zset：按 score 添加/更新一个成员。 */
    void zAdd(int db, String key, double score, String member);

    /** zset：删除一个成员。 */
    void zRemove(int db, String key, String member);

    // —— 危险操作（均 DESTRUCTIVE，UI 走 DestructiveConfirm）——

    /** 删除一个 key。 */
    void del(int db, String key);

    /** 清空当前库。 */
    void flushDb(int db);

    /** 清空所有库（跨库，等价于 redis-cli FLUSHALL）。 */
    void flushAll();

    @Override
    void close();
}
