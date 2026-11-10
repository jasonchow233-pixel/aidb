package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.KnownHostsStore;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ SSH 已知主机面板的 FX 门禁：文件里记了什么就显示什么，删一条就真的少一条。
 *
 * <p>{@code -Dadb.ssh.known_hosts} 重定向到临时目录 —— 验收绝不碰用户真实的
 * {@code ~/.agenticdb/known_hosts}（与 R47-b 同一条红线）。
 */
@ExtendWith(ApplicationExtension.class)
class SshHostKeysPaneTest {

    @TempDir
    Path temp;

    private String previous;

    @Start
    void start(Stage stage) {
        stage.show();
    }

    @Test
    void paneListsRecordedKeysAndRemovingOneActuallyRewritesTheFile() throws Exception {
        Path file = temp.resolve("known_hosts");
        String keyA = base64("key-a");
        String keyB = base64("key-b");
        Files.writeString(file, String.join("\n", List.of(
                "# header",
                "a.example ssh-ed25519 " + keyA,
                "b.example ssh-ed25519 " + keyB)), StandardCharsets.UTF_8);

        withRedirect(file, () -> {
            SshHostKeysPane pane = onFx(SshHostKeysPane::new);
            assertThat(pane.entriesForTest()).hasSize(2);
            assertThat(pane.entriesForTest().get(0).host()).isEqualTo("a.example");
            assertThat(pane.entriesForTest().get(0).fingerprint()).startsWith("SHA256:");
            assertThat(pane.entriesForTest().get(0).fingerprint())
                    .isNotEqualTo(pane.entriesForTest().get(1).fingerprint());

            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                TableView<KnownHostsStore.Entry> table = (TableView<KnownHostsStore.Entry>)
                        pane.lookup("#ssh-host-keys-table");
                table.getSelectionModel().select(1);
                ((Button) pane.lookup("#ssh-host-keys-remove")).fire();
            });

            assertThat(pane.entriesForTest()).extracting(KnownHostsStore.Entry::host)
                    .containsExactly("a.example");
            assertThat(onFx(() -> ((Label) pane.lookup("#ssh-host-keys-path")).getText()))
                    .isEqualTo(file.toString());
            return null;
        });

        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).containsExactly(
                "# header", "a.example ssh-ed25519 " + keyA);
    }

    @Test
    void missingFileIsAnHonestEmptyStateNotAPretendTable() {
        Path file = temp.resolve("absent-known_hosts");
        withRedirect(file, () -> {
            SshHostKeysPane pane = onFx(SshHostKeysPane::new);
            assertThat(pane.entriesForTest()).isEmpty();
            assertThat(onFx(() -> ((Button) pane.lookup("#ssh-host-keys-remove")).isDisabled()))
                    .isTrue();
            assertThat(onFx(() -> ((Label) pane.lookup("#ssh-host-keys-message")).getText()))
                    .isEqualTo(I18n.t("ssh.hosts.empty"));
            return null;
        });
    }

    /** 面板只负责「已信任的条目」，绝不能悄悄替用户接受一把新密钥。 */
    @Test
    void paneNeverOffersATrustAction() {
        withRedirect(temp.resolve("another-absent"), () -> {
            SshHostKeysPane pane = onFx(SshHostKeysPane::new);
            assertThat(onFx(() -> pane.lookup("#ssh-host-keys-trust"))).isNull();
            assertThat(onFx(() -> ((Label) pane.lookup("#ssh-host-keys-message")).getText()))
                    .isNotBlank();
            return null;
        });
    }

    /**
     * ⭐ U24：空态不许是裸 Label —— 表格占位必须走统一空态（图标 + 说明 + 出口）。
     *
     * <p>顺带钉住出口真的会触发「新建连接」：空态只讲「为什么空」不给下一步，
     * 用户只能猜自己该干什么。
     */
    @Test
    void theEmptyTablePlaceholderIsTheSharedEmptyStateWithAWayOut() {
        withRedirect(temp.resolve("empty-absent"), () -> {
            java.util.concurrent.atomic.AtomicInteger clicks =
                    new java.util.concurrent.atomic.AtomicInteger();
            SshHostKeysPane pane = onFx(() -> new SshHostKeysPane(clicks::incrementAndGet));

            javafx.scene.Node placeholder = onFx(pane::placeholderForTest);
            assertThat(placeholder)
                    .as("空表占位必须走 EmptyState，不能是一行裸 Label")
                    .isInstanceOf(EmptyState.class);
            assertThat(onFx(() -> placeholder.lookupAll(".adb-empty__icon")))
                    .as("空态要有图标")
                    .isNotEmpty();
            assertThat(onFx(() -> placeholder.lookupAll(".adb-empty__title")))
                    .as("空态要有标题：说清这是什么状态")
                    .isNotEmpty();
            assertThat(onFx(() -> placeholder.lookupAll(".adb-muted")))
                    .as("空态要有说明：讲清为什么会空")
                    .isNotEmpty();

            java.util.List<javafx.scene.Node> actions =
                    onFx(() -> new java.util.ArrayList<>(placeholder.lookupAll(".adb-button--primary")));
            assertThat(actions)
                    .as("空态必须给一条能点的出路")
                    .isNotEmpty();
            onFxRun(() -> ((javafx.scene.control.Button) actions.get(0)).fire());
            assertThat(clicks.get())
                    .as("点空态的出口按钮真的会触发「新建连接」")
                    .isEqualTo(1);
            return null;
        });
    }

    private void withRedirect(Path file, java.util.function.Supplier<Void> body) {
        previous = System.getProperty("adb.ssh.known_hosts");
        System.setProperty("adb.ssh.known_hosts", file.toString());
        try {
            body.get();
        } finally {
            if (previous == null) {
                System.clearProperty("adb.ssh.known_hosts");
            } else {
                System.setProperty("adb.ssh.known_hosts", previous);
            }
        }
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
