package io.agenticdb.objectstore;

import io.agenticdb.asset.ObjectStoreProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对象存储<b>渐进分页</b>的行为门禁（无 Docker、无云账号也能全跑）。
 *
 * <p>背景：真 OSS 账号实测桶内 &gt;12000 对象且顶层没有 {@code /} 层级，
 * 旧的 {@code listObjects} 一次把整层拉回来再交给 UI。本类钉住新增的
 * {@link ObjectStoreSession#listObjectsPage} 在语义上真的能一页一页走完，
 * 并钉住「四家 SDK 会话必须自己覆写」——否则接口的 default fallback 会
 * 悄悄把「渐进加载」退化成「全量拉取后切片」，等于假能力。
 *
 * <p>⚠️ 这里验的是<b>接口语义</b>，不是四家 SDK 的真端点连通性；
 * 后者仍由 S3 / OSS / Kodo 三支真端点契约负责，COS 一支仍缺。
 */
class ObjectStorePagingTest {

    private static final String BUCKET = "demo-bucket";
    private static final int FLAT_COUNT = 1250;
    private static final int FOLDER_COUNT = 60;

    private static ObjectStoreSession session() {
        return ObjectStoreSessions.inMemory(ObjectStoreProvider.S3);
    }

    /** 顶层无斜杠的对象 + 60 个会被 delimiter 折成文件夹的键。 */
    private static void seed(ObjectStoreSession s) {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < FLAT_COUNT; i++) {
            s.putObject(BUCKET, String.format(Locale.ROOT, "item-%04d.bin", i),
                    data, "application/octet-stream");
        }
        for (int i = 0; i < FOLDER_COUNT; i++) {
            s.putObject(BUCKET, String.format(Locale.ROOT, "dir-%02d/a.txt", i),
                    data, "text/plain");
        }
    }

    private static List<String> keys(List<ObjectEntry> rows) {
        List<String> out = new ArrayList<>();
        for (ObjectEntry row : rows) {
            out.add(row.key());
        }
        return out;
    }

    /** 逐页走完，返回所有页；顺带钉住每页条数与「最后一页无游标」。 */
    private static List<ObjectPage> walk(ObjectStoreSession s, String prefix,
                                        String delimiter, int maxKeys) {
        List<ObjectPage> pages = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (true) {
            ObjectPage page = s.listObjectsPage(BUCKET, prefix, delimiter, cursor, maxKeys);
            pages.add(page);
            assertThat(page.entries().size()).isLessThanOrEqualTo(PageCursor.pageSize(maxKeys));
            if (!page.hasMore()) {
                break;
            }
            assertThat(page.nextToken()).isNotEqualTo(cursor).as("游标必须前进，不能原地打转");
            cursor = page.nextToken();
            if (++guard > 100) {
                throw new AssertionError("pagination did not terminate");
            }
        }
        return pages;
    }

    @Test
    void delimiterLayerIsWalkedPageByPageWithoutLossOrDuplicates() {
        try (ObjectStoreSession s = session()) {
            seed(s);
            List<ObjectPage> pages = walk(s, "", "/", 200);

            assertThat(pages.size()).isGreaterThan(5).as("1310 行不该一页装完");
            List<String> accumulated = new ArrayList<>();
            for (ObjectPage page : pages) {
                accumulated.addAll(keys(page.entries()));
            }
            List<String> all = keys(s.listObjects(BUCKET, "", "/"));
            assertThat(accumulated).isEqualTo(all)
                    .as("逐页累加必须与全量列举逐条一致（顺序、去重都不许漂）");
            assertThat(accumulated).doesNotHaveDuplicates();
            assertThat(accumulated).hasSize(FLAT_COUNT + FOLDER_COUNT);
            assertThat(pages.get(pages.size() - 1).nextToken()).isNull();
        }
    }

    @Test
    void flatListingAlsoPaginates() {
        try (ObjectStoreSession s = session()) {
            seed(s);
            List<ObjectPage> pages = walk(s, "", null, 333);
            List<String> accumulated = new ArrayList<>();
            for (ObjectPage page : pages) {
                accumulated.addAll(keys(page.entries()));
            }
            assertThat(accumulated)
                    .isEqualTo(keys(s.listObjects(BUCKET, "", null)))
                    .hasSize(FLAT_COUNT + FOLDER_COUNT);
        }
    }

    @Test
    void prefixNarrowsEveryPage() {
        try (ObjectStoreSession s = session()) {
            seed(s);
            List<ObjectPage> pages = walk(s, "item-01", "/", 50);
            List<String> accumulated = new ArrayList<>();
            for (ObjectPage page : pages) {
                accumulated.addAll(keys(page.entries()));
            }
            assertThat(accumulated).isEqualTo(keys(s.listObjects(BUCKET, "item-01", "/")));
            for (String key : accumulated) {
                assertThat(key).startsWith("item-01");
            }
        }
    }

    /** 游标与 (bucket, prefix, delimiter) 绑定：换了上下文的旧游标不许串层。 */
    @Test
    void cursorIsBoundToItsBucketPrefixAndDelimiter() {
        try (ObjectStoreSession s = session()) {
            seed(s);
            String cursor = s.listObjectsPage(BUCKET, "", "/", null, 200).nextToken();
            assertThat(cursor).isNotBlank();

            assertThatThrownBy(() -> s.listObjectsPage("another-bucket", "", "/", cursor, 200))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
            assertThatThrownBy(() -> s.listObjectsPage(BUCKET, "dir-", "/", cursor, 200))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
            assertThatThrownBy(() -> s.listObjectsPage(BUCKET, "", null, cursor, 200))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }
    }

    @Test
    void brokenCursorFailsClosedInsteadOfSilentlyRestarting() {
        try (ObjectStoreSession s = session()) {
            seed(s);
            for (String bad : List.of("!!!not-base64!!!", "YWJj", " ")) {
                if (bad.isBlank()) {
                    continue; // 空白按约定是首页
                }
                assertThatThrownBy(() -> s.listObjectsPage(BUCKET, "", "/", bad, 200))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void pageSizeIsClampedToWhatSdkAccepts() {
        try (ObjectStoreSession s = session()) {
            seed(s);
            for (int requested : new int[]{0, -5, 1, 5000}) {
                ObjectPage page = s.listObjectsPage(BUCKET, "", null, null, requested);
                int expected = PageCursor.pageSize(requested);
                assertThat(page.entries()).hasSize(expected)
                        .as("requested=%d → clamped=%d", requested, expected);
                assertThat(expected).isBetween(1, 1000);
            }
        }
    }

    /**
     * ⭐ 结构门禁：四家 SDK 会话必须自己覆写分页入口。接口的 default 只是兼容
     * fallback（全量列举后切片），谁若忘了覆写，浏览器就会「看着能翻页、其实整桶全拉」。
     */
    @Test
    void fourSdkSessionsImplementRealPagingInsteadOfTheFallback() throws Exception {
        Method method = ObjectStoreSession.class.getMethod("listObjectsPage",
                String.class, String.class, String.class, String.class, int.class);
        for (Class<?> type : List.of(S3ObjectStoreSession.class, OssObjectStoreSession.class,
                QiniuObjectStoreSession.class, CosObjectStoreSession.class)) {
            Method own = type.getDeclaredMethod("listObjectsPage",
                    String.class, String.class, String.class, String.class, int.class);
            assertThat(own.getDeclaringClass()).isEqualTo(type);
            assertThat(method.isDefault()).isTrue()
                    .as("接口保留 default 只为兼容第三方实现");
        }
    }

    /** 第三方只实现旧接口的会话，仍能通过 default 拿到语义正确的分页。 */
    @Test
    void legacySessionStillGetsAWorkingDefaultPaging() {
        ObjectStoreSession legacy = new ObjectStoreSession() {
            @Override
            public ObjectStoreProvider provider() {
                return ObjectStoreProvider.S3;
            }

            @Override
            public ObjectStoreCapabilities capabilities() {
                return ObjectStoreCapabilities.forProvider(ObjectStoreProvider.S3);
            }

            @Override
            public List<Bucket> listBuckets() {
                return List.of(new Bucket(BUCKET, Instant.EPOCH));
            }

            @Override
            public List<ObjectEntry> listObjects(String bucket, String prefix, String delimiter) {
                List<ObjectEntry> rows = new ArrayList<>();
                for (int i = 0; i < 455; i++) {
                    rows.add(ObjectEntry.object(new ObjectMeta(
                            String.format(Locale.ROOT, "k-%04d", i), 1L, Instant.EPOCH,
                            "", "", "")));
                }
                return rows;
            }

            @Override
            public byte[] getObject(String bucket, String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ObjectMeta putObject(String bucket, String key, byte[] data, String contentType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteObject(String bucket, String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int deletePrefix(String bucket, String prefix) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ObjectMeta metadata(String bucket, String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String presignUrl(String bucket, String key, long ttlSeconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void moveObject(String bucket, String from, String to) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };

        List<ObjectPage> pages = walk(legacy, "", "/", 200);
        assertThat(pages).hasSize(3);
        List<String> accumulated = new ArrayList<>();
        for (ObjectPage page : pages) {
            accumulated.addAll(keys(page.entries()));
        }
        assertThat(accumulated).hasSize(455).doesNotHaveDuplicates();
    }
}
