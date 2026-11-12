package io.agenticdb.objectstore;

import java.util.List;

/**
 * 对象存储后端会话（P2 落地四个 provider 适配器，统一在本接口背后）。
 *
 * <p>S3 / 阿里云 OSS / 七牛 / 腾讯云 COS 共用这同一组语义
 * （bucket / object / prefix），只在 SDK、endpoint、鉴权上不同 —— 由各 provider 适配器实现。
 * 不支持的操作（如七牛无服务端 move）由具体适配器抛 {@link UnsupportedOperationException}
 * 或提供能力查询，UI 据能力置灰，不造假控件。
 */
public interface ObjectStoreSession extends AutoCloseable {

    /** 列出全部 bucket。 */
    List<Bucket> listBuckets();

    @Override
    void close();
}
