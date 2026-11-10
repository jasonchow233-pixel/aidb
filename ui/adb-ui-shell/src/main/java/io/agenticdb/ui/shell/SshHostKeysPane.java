package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.KnownHostsStore;
import io.agenticdb.jdbc.SshTunnel;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/**
 * ⭐ SSH 已知主机密钥面板（连接管理页 → 「已知 SSH 主机」）。
 *
 * <p>{@code SshTunnel} 的 TOFU / strict 判定是安全边界，<b>这里一行都不改</b>：
 * 面板只把 {@code known_hosts} 里已经记下的条目列出来、给一个可肉眼比对的指纹，
 * 并允许把某一条删掉 —— 机房换过密钥后，旧条目会让后续连接一律被拒，
 * 以前用户只能手工编辑 {@code ~/.agenticdb/known_hosts}。
 *
 * <p>诚实边界：这里<b>不会</b>替用户「接受一把新密钥」。信任决策仍由连接向导里的
 * 「首次使用即记录」开关与 verifier 负责；本面板只是把已信任的条目变得可见、可撤销。
 */
final class SshHostKeysPane extends VBox {

    private final Path file = SshTunnel.knownHostsFile();
    private final TableView<KnownHostsStore.Entry> table = new TableView<>();
    private final Button remove = new Button(I18n.t("ssh.hosts.remove"));
    private final Label message = new Label();
    /** ⭐ 空态的出口：回到「新建连接」，让用户知道下一步该干什么而不是只看见一句灰字。 */
    private final Runnable onNewConnection;

    SshHostKeysPane() {
        this(() -> { });
    }

    SshHostKeysPane(Runnable onNewConnection) {
        this.onNewConnection = onNewConnection == null ? () -> { } : onNewConnection;
        setId("ssh-host-keys");
        setSpacing(10);
        setPadding(new Insets(12));
        setPrefWidth(DialogSizes.TABLE);

        Label path = new Label(file.toString());
        path.setId("ssh-host-keys-path");
        path.getStyleClass().add("adb-muted");
        path.setWrapText(true);

        buildColumns();
        table.setId("ssh-host-keys-table");
        // ⭐ U24：空态不许是裸 Label —— 要有图标、说明和一条出路。
        table.setPlaceholder(EmptyState.of(Icons.Icon.LOCK, I18n.t("ssh.hosts.empty.title"),
                I18n.t("ssh.hosts.empty.note"),
                I18n.t("ssh.hosts.empty.action"), onNewConnection));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        remove.setId("ssh-host-keys-remove");
        remove.getStyleClass().add("adb-button");
        remove.setOnAction(event -> removeSelected());

        Label hint = new Label(I18n.t("ssh.hosts.hint"));
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);

        message.setId("ssh-host-keys-message");
        message.getStyleClass().add("adb-muted");
        message.setWrapText(true);

        getChildren().addAll(path, table, remove, hint, message);
        refresh();
    }

    private void buildColumns() {
        TableColumn<KnownHostsStore.Entry, String> host =
                new TableColumn<>(I18n.t("ssh.hosts.column.host"));
        host.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().host()));
        TableColumn<KnownHostsStore.Entry, String> type =
                new TableColumn<>(I18n.t("ssh.hosts.column.type"));
        type.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().keyType()));
        TableColumn<KnownHostsStore.Entry, String> fingerprint =
                new TableColumn<>(I18n.t("ssh.hosts.column.fingerprint"));
        fingerprint.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().fingerprint()));
        table.getColumns().setAll(host, type, fingerprint);
    }

    /** 重新读文件；失败时把原因显示出来，不弹异常、不假装文件是空的。 */
    void refresh() {
        try {
            List<KnownHostsStore.Entry> entries = KnownHostsStore.read(file);
            table.setItems(FXCollections.observableArrayList(entries));
            message.setText(entries.isEmpty() ? I18n.t("ssh.hosts.empty") : "");
            remove.setDisable(entries.isEmpty());
        } catch (Exception e) {
            table.setItems(FXCollections.observableArrayList());
            remove.setDisable(true);
            message.setText(I18n.t("ssh.hosts.error", describe(e)));
        }
    }

    private void removeSelected() {
        KnownHostsStore.Entry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        try {
            KnownHostsStore.remove(file, selected.lineNumber());
            refresh();
        } catch (Exception e) {
            message.setText(I18n.t("ssh.hosts.error", describe(e)));
        }
    }

    private static String describe(Exception e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
    }

    /** 包级读数口：门禁直接看表格里的条目，不依赖模态窗口的 CSS lookup。 */
    List<KnownHostsStore.Entry> entriesForTest() {
        return List.copyOf(table.getItems());
    }

    /** 包级读数口：门禁直接看表格占位是不是统一空态（而不是一行裸 Label）。 */
    javafx.scene.Node placeholderForTest() {
        return table.getPlaceholder();
    }
}
