package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.asset.ObjectStoreProvider;
import io.agenticdb.objectstore.Bucket;
import io.agenticdb.objectstore.ObjectEntry;
import io.agenticdb.objectstore.ObjectMeta;
import io.agenticdb.objectstore.ObjectPage;
import io.agenticdb.objectstore.ObjectStoreCapabilities;
import io.agenticdb.objectstore.ObjectStoreCredentials;
import io.agenticdb.objectstore.ObjectStoreSession;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 渐进加载的 UI 行为门禁。
 *
 * <p>后端 {@code ObjectStorePagingTest} 只保证「分页接口语义正确」，管不到
 * 浏览器有没有偷偷退回全量 {@code listObjects}：那样界面看起来也能翻页，
 * 但真 OSS 账号那个 &gt;12000 对象的桶仍会被整桶拉回内存。这里用一份会计数、
 * 会分页的假会话钉住 UI 真的只问一页、逐页追加、到底收口、换前缀重置。
 *
 * <p>另有一条纪律：没有下一页时页脚必须<b>隐藏</b>，不许摆一颗灰掉的假按钮
 * —— 这也是既有 74 张截图帧在小 bucket 上不受本轮改动影响的原因。
 */
@ExtendWith(ApplicationExtension.class)
class ObjectBrowserPagingTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    private static <T> T onFx(Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 后台线程 + FX 回弹都要等；断言数据一律用 onFx 收回来读。 */
    private static <T> T await(Supplier<T> read, Predicate<T> done, String what) {
        T last = null;
        for (int i = 0; i < 400; i++) {
            last = read.get();
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待 " + what + " 超时（最后一次读到：" + last + "）");
    }

    private record Call(String bucket, String prefix, String delimiter, String cursor, int maxKeys) {
    }

    /** 只实现列举路径的假会话：分页与全量各计一次，UI 走错入口立刻现形。 */
    private static final class PagingSession implements ObjectStoreSession {

        private final List<ObjectEntry> rows;
        final List<Call> pageCalls = new CopyOnWriteArrayList<>();
        volatile int fullCalls;

        PagingSession(List<ObjectEntry> rows) {
            this.rows = List.copyOf(rows);
        }

        static PagingSession of(int count) {
            List<ObjectEntry> rows = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                rows.add(ObjectEntry.object(new ObjectMeta(
                        String.format(Locale.ROOT, "key-%04d.bin", i), 1L,
                        Instant.EPOCH, "", "", "")));
            }
            return new PagingSession(rows);
        }

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
            return List.of(new Bucket("assets", Instant.EPOCH));
        }

        @Override
        public List<ObjectEntry> listObjects(String bucket, String prefix, String delimiter) {
            fullCalls++;
            return rows;
        }

        @Override
        public ObjectPage listObjectsPage(String bucket, String prefix, String delimiter,
                                          String cursor, int maxKeys) {
            pageCalls.add(new Call(bucket, prefix, delimiter, cursor, maxKeys));
            int offset = 0;
            if (cursor != null && !cursor.isBlank()) {
                String[] parts = cursor.split("\\|", -1);
                if (parts.length != 4 || !parts[0].equals(bucket)
                        || !parts[1].equals(prefix == null ? "" : prefix)
                        || !parts[2].equals(delimiter == null ? "" : delimiter)) {
                    throw new IllegalArgumentException("cursor does not match context");
                }
                offset = Integer.parseInt(parts[3]);
            }
            List<ObjectEntry> inScope = rows;
            if (prefix != null && !prefix.isEmpty()) {
                inScope = rows.stream().filter(r -> r.key().startsWith(prefix)).toList();
            }
            int from = Math.min(offset, inScope.size());
            int end = Math.min(inScope.size(), from + maxKeys);
            String next = end < inScope.size()
                    ? bucket + "|" + (prefix == null ? "" : prefix) + "|"
                            + (delimiter == null ? "" : delimiter) + "|" + end
                    : null;
            return new ObjectPage(inScope.subList(from, end), next);
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
    }

    private static ObjectBrowserPage page(PagingSession fake) {
        ConnectionProfile profile = new ConnectionProfile(
                "os-1", "对象存储", AssetKind.OBJECT_STORE,
                "endpoint.example.com", 443, "assets", "ak", false, 0, "",
                Map.of(ObjectStoreCredentials.OPT_PROVIDER, ObjectStoreProvider.S3.id()),
                SshConfig.NONE);
        TabItem tab = new TabItem("object-browser:os-1:assets", "assets",
                TabKind.OBJECT_BROWSER, "assets", "os-1");
        return onFx(() -> new ObjectBrowserPage(tab, profile, () -> fake));
    }

    private static int rowCount(ObjectBrowserPage page) {
        return onFx(() -> page.tableForTest().getItems().size());
    }

    private static boolean moreVisible(ObjectBrowserPage page) {
        return onFx(() -> page.moreBarForTest().isVisible());
    }

    @Test
    void firstScreenAsksForExactlyOnePageAndNeverFallsBackToTheFullListing() {
        PagingSession fake = PagingSession.of(450);
        ObjectBrowserPage page = page(fake);
        onFxRun(page::activate);

        await(() -> rowCount(page), n -> n == 200, "首屏 200 行");
        assertThat(fake.pageCalls).hasSize(1);
        assertThat(fake.fullCalls)
                .as("UI 不许退回全量 listObjects，那等于把整桶拉回内存再切片")
                .isZero();
        Call first = fake.pageCalls.get(0);
        assertThat(first.cursor()).isNull();
        assertThat(first.delimiter()).isEqualTo("/");
        assertThat(first.maxKeys()).isEqualTo(200);
        assertThat(moreVisible(page)).isTrue();
        assertThat(onFx(() -> page.moreButtonForTest().isDisable())).isFalse();
        assertThat(onFx(() -> page.statusLabelForTest().getText()))
                .isEqualTo(I18n.tPlural("objectstore.count.more", 200, 200));
    }

    @Test
    void loadMoreAppendsUntilTheLastPageThenHidesTheFooter() {
        PagingSession fake = PagingSession.of(450);
        ObjectBrowserPage page = page(fake);
        onFxRun(page::activate);
        await(() -> rowCount(page), n -> n == 200, "首页");

        onFxRun(() -> page.moreButtonForTest().fire());
        await(() -> rowCount(page), n -> n == 400, "第二页");
        assertThat(moreVisible(page)).isTrue();

        onFxRun(() -> page.moreButtonForTest().fire());
        await(() -> rowCount(page), n -> n == 450, "最后一页");
        await(() -> moreVisible(page), visible -> !visible, "页脚收起");

        assertThat(fake.pageCalls).hasSize(3);
        assertThat(fake.pageCalls.get(1).cursor()).contains("|200");
        assertThat(fake.pageCalls.get(2).cursor()).contains("|400");
        assertThat(fake.fullCalls).isZero();
        assertThat(onFx(() -> page.statusLabelForTest().getText()))
                .isEqualTo(I18n.tPlural("objectstore.count", 450, 450));
    }

    @Test
    void folderRowsAreMergedWhenTheyAppearOnTwoPages() {
        // 第 199 / 200 行是同一个虚拟文件夹：服务端 delimiter 折叠跨页时可能重复回。
        List<ObjectEntry> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(ObjectEntry.object(new ObjectMeta(
                    String.format(Locale.ROOT, "key-%04d.bin", i), 1L,
                    Instant.EPOCH, "", "", "")));
        }
        rows.set(199, ObjectEntry.folder("shared/"));
        rows.set(200, ObjectEntry.folder("shared/"));
        PagingSession dedup = new PagingSession(rows);
        ObjectBrowserPage page = page(dedup);
        onFxRun(page::activate);
        await(() -> rowCount(page), n -> n == 200, "首页");

        onFxRun(() -> page.moreButtonForTest().fire());
        await(() -> rowCount(page), n -> n == 249, "去重后的累加行数");

        long shared = onFx(() -> page.tableForTest().getItems().stream()
                .filter(e -> "shared/".equals(e.key())).count());
        assertThat(shared).isEqualTo(1);
        assertThat(dedup.fullCalls).isZero();
    }

    @Test
    void changingPrefixStartsFromANewFirstPage() {
        PagingSession fake = PagingSession.of(450);
        ObjectBrowserPage page = page(fake);
        onFxRun(page::activate);
        await(() -> rowCount(page), n -> n == 200, "首页");
        assertThat(fake.pageCalls.get(0).cursor()).isNull();

        onFxRun(() -> {
            page.prefixFieldForTest().setText("key-0001");
            page.prefixFieldForTest().fireEvent(new ActionEvent());
        });
        await(() -> fake.pageCalls.size(), n -> n == 2, "换前缀后的首次列举");

        Call after = fake.pageCalls.get(1);
        assertThat(after.prefix()).isEqualTo("key-0001");
        assertThat(after.cursor())
                .as("换前缀后必须从头问，不许把旧层的游标带过去").isNull();
        assertThat(moreVisible(page))
                .as("新前缀只命中 1 条，没有下一页，页脚该收起").isFalse();
    }

    /**
     * 页脚这颗不在 {@code ObjectBrowserDensityTest} 的「工具带 9 键」范围内，
     * 所以这里单独钉：文字不许压成省略号、完整动作名要进 tooltip / 读屏、
     * 并且不许混进工具带的样式类把那条门禁的数量断言搅乱。
     */
    @Test
    void moreButtonKeepsItsFullLabelAndStaysOutOfTheToolbarClass() {
        PagingSession fake = PagingSession.of(450);
        ObjectBrowserPage page = page(fake);

        assertThat(onFx(() -> page.moreButtonForTest().getMinWidth()))
                .isEqualTo(Region.USE_PREF_SIZE);
        assertThat(onFx(() -> page.moreButtonForTest().getTooltip().getText()))
                .isEqualTo(I18n.t("objectstore.loadMore"));
        assertThat(onFx(() -> page.moreButtonForTest().getAccessibleText()))
                .isEqualTo(I18n.t("objectstore.loadMore"));
        assertThat(onFx(() -> page.moreButtonForTest().getStyleClass()))
                .contains("adb-button", "adb-objects__more-button")
                .doesNotContain("adb-objects__action");
    }

    @Test
    void smallBucketHasNoFakeMoreButton() {
        PagingSession fake = PagingSession.of(3);
        ObjectBrowserPage page = page(fake);
        onFxRun(page::activate);

        await(() -> rowCount(page), n -> n == 3, "小桶三行");
        assertThat(moreVisible(page)).isFalse();
        assertThat(onFx(() -> page.moreBarForTest().isManaged())).isFalse();
        assertThat(onFx(() -> page.statusLabelForTest().getText()))
                .isEqualTo(I18n.tPlural("objectstore.count", 3, 3));
    }

    /** 源码级钉子：列举入口只有分页，加载更多的按钮与页脚不是摆设。 */
    @Test
    void browserSourceUsesThePagingEntryPointOnly() throws IOException {
        Path root = repositoryRoot();
        Path source = root.resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ObjectBrowserPage.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertThat(text).contains("listObjectsPage(bucket, p, \"/\", null, PAGE_SIZE)");
        assertThat(text).contains("private void loadMore()");
        assertThat(text)
                .as("不许留一条悄悄全量列举再切片的后门")
                .doesNotContain("List<ObjectEntry> rows = List.copyOf(s.listObjects(");
        assertThat(text).contains("pageRows.putIfAbsent(entry.key(), entry)");
        assertThat(I18n.t("objectstore.action.loadMore")).isNotBlank();
        assertThat(I18n.t("objectstore.loadMore")).isNotBlank();
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("ui"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
