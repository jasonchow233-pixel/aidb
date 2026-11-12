package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.asset.ObjectStoreProvider;
import io.agenticdb.objectstore.ObjectStoreCapabilities;
import io.agenticdb.objectstore.ObjectStoreCredentials;
import io.agenticdb.objectstore.ObjectStoreDanger;
import io.agenticdb.objectstore.ObjectStoreSessions;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2-9：对象浏览器页的「诚实性」门禁。
 *
 * <p>四家 provider 共用一页，差异只允许出现在 {@link ObjectStoreCapabilities} 这一份事实里。
 * 本类断三件事：
 * <ol>
 *   <li>七牛 {@code supportsMove=false} → 移动按钮置灰 + tooltip 说明原因（不是点了没反应）；</li>
 *   <li>S3 / OSS / COS 声明支持 → 按钮可用，说明文案回到普通操作名；</li>
 *   <li>删除类操作的确认走 {@link ObjectStoreDanger} + {@code DestructiveConfirm}，
 *       与 Redis / SQL 同一套三级门禁，不另造一份确认 UI。</li>
 * </ol>
 *
 * <p>能力声明本身由 {@code ObjectStoreSessionTest} / {@code ObjectStoreDangerTest} 在后端守；
 * 这里只管「后端声明了，UI 真的照做了」。
 */
@ExtendWith(ApplicationExtension.class)
class ObjectBrowserPageTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ConnectionProfile profile(ObjectStoreProvider provider) {
        return new ConnectionProfile("os-1", "对象存储", AssetKind.OBJECT_STORE,
                "endpoint.example.com", 443, "assets", "ak", false, 0, "",
                Map.of(ObjectStoreCredentials.OPT_PROVIDER, provider.id()), SshConfig.NONE);
    }

    private static ObjectBrowserPage page(ObjectStoreProvider provider) {
        TabItem tab = new TabItem("object-browser:os-1:assets", "assets",
                TabKind.OBJECT_BROWSER, "assets", "os-1");
        return onFx(() -> new ObjectBrowserPage(tab, profile(provider),
                () -> ObjectStoreSessions.inMemory(provider)));
    }

    @Test
    void qiniuMoveIsDisabledAndExplainsWhy() {
        ObjectBrowserPage page = page(ObjectStoreProvider.QINIU);
        Button move = onFx(page::moveButtonForTest);
        assertThat(move).isNotNull();

        onFxRun(() -> page.applyCapabilitiesForTest(
                ObjectStoreCapabilities.forProvider(ObjectStoreProvider.QINIU)));

        assertThat(onFx(move::isDisable))
                .as("七牛无服务端 move：按钮不许留成假控件").isTrue();
        String note = onFx(() -> {
            Tooltip tip = move.getTooltip();
            return tip == null ? null : tip.getText();
        });
        assertThat(note).isEqualTo(I18n.t("objectstore.capability.qiniu.noMove"));
    }

    @Test
    void providersThatDeclareMoveKeepTheButtonEnabled() {
        for (ObjectStoreProvider provider : new ObjectStoreProvider[]{
                ObjectStoreProvider.S3, ObjectStoreProvider.OSS, ObjectStoreProvider.COS}) {
            ObjectBrowserPage page = page(provider);
            Button move = onFx(page::moveButtonForTest);
            onFxRun(() -> page.applyCapabilitiesForTest(
                    ObjectStoreCapabilities.forProvider(provider)));
            assertThat(onFx(move::isDisable))
                    .as("%s 声明支持 move，按钮不该灰", provider.displayName())
                    .isFalse();
        }
    }

    /** 后端能力 / 危险分级与 UI 用的是同一份事实，不是各抄一张表。 */
    @Test
    void capabilityAndDangerTruthLivesInTheBackend() {
        assertThat(ObjectStoreCapabilities.forProvider(ObjectStoreProvider.QINIU).supportsMove())
                .isFalse();
        assertThat(ObjectStoreDanger.level("DELETE_PREFIX").name()).isEqualTo("DESTRUCTIVE");
        assertThat(ObjectStoreDanger.level("LIST_OBJECTS").name()).isEqualTo("READ_ONLY");
        assertThat(ObjectStoreDanger.level(" ").name())
                .as("未知 / 空白操作 fail-closed").isEqualTo("ADMIN");
        // 内存会话的四家 factory 都能开，UI 才谈得上「共用一页」。
        for (ObjectStoreProvider provider : ObjectStoreProvider.values()) {
            assertThat(ObjectStoreSessions.inMemory(provider).provider()).isEqualTo(provider);
        }
    }

    /**
     * 结构门禁：删一颗 / 递归删前缀的确认必须经 ObjectStoreDanger.level(op) 判定，
     * 且用 DestructiveConfirm（取消默认）—— 不许在页面里手搓一个 Alert。
     */
    @Test
    void destructiveOperationsGoThroughTheSharedDangerGate() throws IOException {
        Path root = repositoryRoot();
        Path source = root.resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ObjectBrowserPage.java");
        assertThat(source).exists();
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(text).contains("ObjectStoreDanger.level(op).requiresApproval()");
        assertThat(text).contains("\"DELETE_OBJECT\"", "\"DELETE_PREFIX\"");
        assertThat(text).contains("DestructiveConfirm.of(");
        assertThat(text)
                .as("不许在页面里另造 Alert 绕过确认门禁")
                .doesNotContain("new Alert(");
        assertThat(text)
                .as("弹窗要套应用主题")
                .contains("DialogStyles.apply(");
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
