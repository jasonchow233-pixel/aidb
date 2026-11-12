package io.agenticdb.objectstore;

import io.agenticdb.asset.ObjectStoreProvider;

import java.util.List;

/**
 * 对象存储后端会话 —— S3 / 阿里云 OSS / 七牛 Kodo / 腾讯云 COS 共用的一组语义。
 *
 * <p>四家共享 bucket / object / prefix 模型，只在 SDK、endpoint、鉴权上不同；
 * 差异通过 {@link #provider()} 与 {@link #capabilities()} 显式声明，
 * <b>不支持的操作必须抛 {@link UnsupportedOperationException}</b>，UI 依据能力把按钮置灰，
 * 不造假控件（规划 §3「诚实性」）。
 *
 * <p>写安全分工与 Redis 一致：本接口只负责执行；风险等级由 {@link ObjectStoreDanger}
 * 判定、确认弹窗由 UI 的 {@code DestructiveConfirm} 负责。会话自身不拦截写操作，
 * 于是行为门禁可以直接在真实现上验证「删除确实删了」。
 *
 * <p>{@link #close()} 收窄为不抛受检异常（与 {@code RedisSession} 同口径）。
 */
public interface ObjectStoreSession extends AutoCloseable {

    ObjectStoreProvider provider();

    /** 能力声明；UI 只问一次就把整页按钮状态定下来。 */
    ObjectStoreCapabilities capabilities();

    /** 列出全部 bucket。不支持列举的 provider（七牛）返回配置里指定的那一个。 */
    List<Bucket> listBuckets();

    /**
     * 列举某个 bucket 在 {@code prefix} 下的对象。
     *
     * @param delimiter 传 {@code "/"} 时把同前缀折叠成 {@link ObjectEntry#folder()} 行；
     *                  传 {@code null} / 空串时平铺全部对象（递归删前缀用）
     */
    List<ObjectEntry> listObjects(String bucket, String prefix, String delimiter);

    byte[] getObject(String bucket, String key);

    ObjectMeta putObject(String bucket, String key, byte[] data, String contentType);

    void deleteObject(String bucket, String key);

    /** 递归删除某前缀下全部对象，返回删除条数（DESTRUCTIVE，UI 必须先确认）。 */
    int deletePrefix(String bucket, String prefix);

    ObjectMeta metadata(String bucket, String key);

    /** 生成临时访问 URL；不支持的 provider 抛 UOE。 */
    String presignUrl(String bucket, String key, long ttlSeconds);

    /** 移动/重命名；不支持的 provider 抛 UOE（七牛无服务端 move）。 */
    void moveObject(String bucket, String from, String to);

    @Override
    void close();
}
