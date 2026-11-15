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
     * 列举某个 bucket 在 {@code prefix} 下的<b>整层全部</b>对象。
     *
     * @param delimiter 传 {@code "/"} 时把同前缀折叠成 {@link ObjectEntry#folder()} 行；
     *                  传 {@code null} / 空串时平铺全部对象（递归删前缀用）
     *
     * <p>⚠️ 这是全量入口：超大 bucket 会一次把所有页拉回来。浏览页请用
     * {@link #listObjectsPage}，本入口保留给递归删前缀、连接测试与既有真端点契约。
     */
    List<ObjectEntry> listObjects(String bucket, String prefix, String delimiter);

    /**
     * 列举一页；首屏 {@code cursor = null}，之后把 {@link ObjectPage#nextToken()}
     * 原样传回，直到 {@link ObjectPage#hasMore()} 为 false。
     *
     * @param maxKeys   本页条数上限（非正数取默认 200，最多夹到 1000）
     * @return          本页条目 + 下一页游标；到底时游标为 {@code null}
     *
     * <p><b>默认实现是兼容 fallback，不是真渐进</b>：它调
     * {@link #listObjects} 取全量再按偏移切片，只保证语义正确，<b>不</b>减少服务端请求量。
     * 四家 SDK 会话与内存后端都覆写成各自的分页原语（服务端 delimiter 折叠 +
     * continuationToken / marker），结构门禁 {@code ObjectStorePagingTest} 钉住这一点。
     * 新接入的会话若不覆写，就不能宣称支持渐进加载。
     */
    default ObjectPage listObjectsPage(String bucket, String prefix, String delimiter,
                                       String cursor, int maxKeys) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeDelimiter = delimiter == null ? "" : delimiter;
        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            String token = PageCursor.tokenFor(cursor, bucket, safePrefix, safeDelimiter);
            try {
                offset = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid object list cursor", e);
            }
            if (offset < 0) {
                throw new IllegalArgumentException("invalid object list cursor offset");
            }
        }
        List<ObjectEntry> all = listObjects(bucket, safePrefix, safeDelimiter);
        int size = PageCursor.pageSize(maxKeys);
        long endLong = (long) offset + size;
        int end = (int) Math.min((long) all.size(), endLong);
        List<ObjectEntry> slice = offset >= all.size()
                ? List.of() : List.copyOf(all.subList(offset, end));
        String next = end < all.size()
                ? PageCursor.of(bucket, safePrefix, safeDelimiter, Integer.toString(end))
                : null;
        return new ObjectPage(slice, next);
    }

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
