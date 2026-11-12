package io.agenticdb.objectstore;

import java.time.Instant;

/**
 * 对象列表里的一行 —— 可能是「对象」，也可能是 delimiter 折叠出来的「文件夹」。
 *
 * <p>四家 provider 都没有真正的目录，只有 key 里的 {@code /}；用 delimiter 列举时
 * 服务端会把同前缀的一段折成一个 common prefix，这里统一记成 {@code folder=true}，
 * 树的「文件夹」节点与浏览器的面包屑都按它渲染，避免每处各写一套切分逻辑。
 */
public record ObjectEntry(
        String key,
        long size,
        Instant lastModified,
        String etag,
        String storageClass,
        boolean folder) {

    /** 文件夹行没有 size / etag / 存储类型，工厂保证这些字段是中性值。 */
    public static ObjectEntry folder(String prefix) {
        return new ObjectEntry(prefix, 0L, null, "", "", true);
    }

    public static ObjectEntry object(ObjectMeta meta) {
        return new ObjectEntry(meta.key(), meta.size(), meta.lastModified(),
                meta.etag(), meta.storageClass(), false);
    }
}
