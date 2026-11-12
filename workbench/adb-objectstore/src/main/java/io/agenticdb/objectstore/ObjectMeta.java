package io.agenticdb.objectstore;

import java.time.Instant;

/**
 * 对象元数据（四家 provider 共用）。
 *
 * <p>{@code storageClass} 为存储类型（Standard / IA / Archive 等），不同 provider 取值不同；
 * 不识别时给空串。
 */
public record ObjectMeta(
        String key,
        long size,
        Instant lastModified,
        String etag,
        String storageClass) {
}
