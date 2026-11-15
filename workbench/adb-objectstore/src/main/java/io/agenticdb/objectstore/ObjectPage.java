package io.agenticdb.objectstore;

import java.util.List;

/**
 * 一页对象列举结果 —— 超大 bucket 渐进加载的载体。
 *
 * <p>{@link #nextToken()} 是<b>不透明</b>游标：四家 SDK 的续页凭证语义不同
 * （S3 是 continuationToken，OSS / COS / Kodo 是 marker），由各会话自己编码，
 * 调用方只负责把上一次的值原样传回 {@link ObjectStoreSession#listObjectsPage}。
 * {@code null} / 空白表示已经到底，不必再问下一页。
 *
 * <p>分页与 {@link ObjectStoreSession#listObjects} 的全量列举是两条独立入口：
 * 旧入口保留给递归删前缀、连接测试与既有真端点契约；浏览页只用本页模型，
 * 不把整桶拉回来再交给 UI 切片。
 */
public record ObjectPage(List<ObjectEntry> entries, String nextToken) {

    public ObjectPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** 还有下一页；UI 据此决定「加载更多」是否可见 / 可用。 */
    public boolean hasMore() {
        return nextToken != null && !nextToken.isBlank();
    }

    /** 最后一页：不带游标。 */
    public static ObjectPage last(List<ObjectEntry> entries) {
        return new ObjectPage(entries, null);
    }
}
