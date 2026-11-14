package io.agenticdb.objectstore;

import java.time.Instant;

/**
 * 对象元数据（四家 provider 共用）。
 *
 * <p>{@code storageClass} 为存储类型（Standard / IA / Archive 等），不同 provider 取值不同；
 * 不识别时给空串。{@code contentType} 是对象的 MIME 类型，与存储类型<b>互不相关</b> ——
 * 早期代码把两者混用（甚至把 contentType 塞进 storageClass），真端点契约里表现为
 * 「移动一次对象，MIME 类型变成 application/octet-stream」。列举接口拿不到 MIME 时给空串，
 * 只有 {@code metadata()} 才保证填充。
 */
public record ObjectMeta(
        String key,
        long size,
        Instant lastModified,
        String etag,
        String storageClass,
        String contentType) {
}
