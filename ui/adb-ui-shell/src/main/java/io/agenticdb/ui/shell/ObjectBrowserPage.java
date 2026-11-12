package io.agenticdb.ui.shell;

import io.agenticdb.objectstore.ObjectEntry;
import io.agenticdb.objectstore.ObjectMeta;
import io.agenticdb.objectstore.ObjectStoreCapabilities;
import io.agenticdb.objectstore.ObjectStoreDanger;
import io.agenticdb.objectstore.ObjectStoreSession;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ⭐ EXT（P2-8/P2-9）：对象存储浏览器页。
 *
 * <p>四家 provider 共用这一页：差异只在 {@link ObjectStoreCapabilities} 与会话实现。
 * 前缀层级用 delimiter 折叠出的 {@link ObjectEntry#folder()} 行 + 面包屑表达
 * （对象存储没有真目录，key 里的 {@code /} 就是全部层级）。
 *
 * <p><b>诚实性</b>：不支持的操作（七牛无服务端 move）按钮置灰并带说明，
 * 不留「点了没反应」的假控件；会话对未声明的操作抛 UOE，UI 与后端是同一份事实。
 *
 * <p><b>写安全</b>：删除 / 递归删前缀走 {@link DestructiveConfirm}；
 * 等级由 {@link ObjectStoreDanger} 判定（DELETE_* = DESTRUCTIVE），
 * 与 Redis / SQL 的三级门禁同一套语义，不另造确认 UI。
 *
 * <p>按 {@code (连接, bucket)} 一个实例并缓存（{@link WorkspaceView} 按 tab id 存），
 * 切标签不重连、不丢当前前缀；{@link #close()} 时断开会话。
 */
final class ObjectBrowserPage extends VBox {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    private final ConnectionProfile profile;
    private final String bucket;
    private final Supplier<ObjectStoreSession> sessionFactory;

    private final TableView<ObjectEntry> table = new TableView<>();
    private final TextField prefixField = new TextField();
    private final Label statusLabel = new Label();
    private final Label breadcrumb = new Label();
    private final VBox details = new VBox(8);

    private volatile ObjectStoreSession session;
    private volatile boolean busy;
    private boolean activated;
    private String prefix = "";
    private ObjectStoreCapabilities capabilities;

    /** 移动/重命名按钮：能力不支持时置灰（测试据此验证诚实性）。 */
    private javafx.scene.control.Button moveButton;

    ObjectBrowserPage(TabItem tab, ConnectionProfile profile,
                      Supplier<ObjectStoreSession> sessionFactory) {
        this.profile = profile;
        this.bucket = tab.target() == null ? "" : tab.target();
        this.sessionFactory = sessionFactory;

        getStyleClass().add("adb-page");
        setSpacing(0);

        HBox header = buildHeader();
        header.getStyleClass().add("adb-page__header");

        details.getStyleClass().add("adb-details");
        details.setVisible(false);
        details.setManaged(false);
        details.setMinWidth(300);
        details.setPrefWidth(340);
        HBox body = new HBox(table, details);
        body.getStyleClass().add("adb-page__body");
        HBox.setHgrow(table, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, new Separator(), body);
        configureTable();
        setDensity("standard");
        statusLabel.setText(I18n.t("objectstore.loading"));
    }

    private HBox buildHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(I18n.t("objectstore.header", profile.name(), bucket));
        title.getStyleClass().add("adb-page__title");

        breadcrumb.getStyleClass().add("adb-muted");
        breadcrumb.setText("/");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        prefixField.setPromptText(I18n.t("objectstore.prefix"));
        prefixField.setPrefWidth(180);
        prefixField.setOnAction(event -> {
            prefix = prefixField.getText() == null ? "" : prefixField.getText();
            load();
        });

        var up = IconButtons.of(Icons.Icon.PREVIOUS, I18n.t("objectstore.up"));
        up.getStyleClass().addAll("adb-button");
        up.setOnAction(event -> upOneLevel());

        var refresh = IconButtons.of(Icons.Icon.REFRESH, I18n.t("objectstore.refresh"));
        refresh.getStyleClass().addAll("adb-button");
        refresh.setOnAction(event -> load());

        HBox filter = new HBox(8, breadcrumb, up, prefixField, refresh);
        filter.getStyleClass().add("adb-toolbar__group");
        filter.setAlignment(Pos.CENTER_LEFT);

        var upload = IconButtons.of(Icons.Icon.IMPORT, I18n.t("objectstore.upload"));
        upload.getStyleClass().addAll("adb-button");
        upload.setOnAction(event -> uploadFile());

        var download = IconButtons.of(Icons.Icon.EXPORT, I18n.t("objectstore.download"));
        download.getStyleClass().addAll("adb-button");
        download.setOnAction(event -> downloadSelected());

        var presign = IconButtons.of(Icons.Icon.OPEN, I18n.t("objectstore.presign"));
        presign.getStyleClass().addAll("adb-button");
        presign.setOnAction(event -> copyPresignedUrl());

        var metadata = IconButtons.of(Icons.Icon.LIST, I18n.t("objectstore.metadata"));
        metadata.getStyleClass().addAll("adb-button");
        metadata.setOnAction(event -> {
            ObjectEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.folder()) {
                showMetadata(selected.key());
            }
        });

        // ⭐ 能力差异的可视落点：七牛无服务端 move，按钮置灰 + tooltip 说明。
        moveButton = IconButtons.of(Icons.Icon.EDIT, I18n.t("objectstore.move"));
        moveButton.getStyleClass().addAll("adb-button");
        moveButton.setOnAction(event -> moveSelected());

        var deleteOne = IconButtons.of(Icons.Icon.DELETE, I18n.t("objectstore.delete"));
        deleteOne.getStyleClass().addAll("adb-button", "adb-button--danger");
        deleteOne.setOnAction(event -> deleteSelected());

        var deletePrefix = IconButtons.of(Icons.Icon.DELETE, I18n.t("objectstore.deletePrefix"));
        deletePrefix.getStyleClass().addAll("adb-button", "adb-button--danger");
        deletePrefix.setOnAction(event -> deleteCurrentPrefix());

        HBox actions = new HBox(8, upload, download, presign, metadata, moveButton,
                deleteOne, deletePrefix);
        actions.getStyleClass().add("adb-toolbar__group");
        actions.setAlignment(Pos.CENTER_LEFT);

        statusLabel.getStyleClass().add("adb-muted");
        header.getChildren().addAll(title, spacer, filter, actions, statusLabel);
        return header;
    }

    private void configureTable() {
        TableColumn<ObjectEntry, String> keyCol = new TableColumn<>(I18n.t("objectstore.column.key"));
        keyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().folder()
                        ? I18n.t("objectstore.folder") + "  " + c.getValue().key()
                        : c.getValue().key()));
        keyCol.setPrefWidth(360);

        TableColumn<ObjectEntry, String> sizeCol =
                new TableColumn<>(I18n.t("objectstore.column.size"));
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().folder() ? "" : humanSize(c.getValue().size())));
        sizeCol.setPrefWidth(110);

        TableColumn<ObjectEntry, String> modifiedCol =
                new TableColumn<>(I18n.t("objectstore.column.modified"));
        modifiedCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                formatTime(c.getValue().lastModified())));
        modifiedCol.setPrefWidth(170);

        TableColumn<ObjectEntry, String> storageCol =
                new TableColumn<>(I18n.t("objectstore.column.storage"));
        storageCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().storageClass()));
        storageCol.setPrefWidth(120);

        table.getColumns().setAll(keyCol, sizeCol, modifiedCol, storageCol);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // ⭐ 空态给「上传」这个明确出口，不是一行「暂无数据」。
        table.setPlaceholder(EmptyState.of(Icons.Icon.FOLDER,
                I18n.t("objectstore.emptyTitle"), I18n.t("objectstore.emptyHint"),
                I18n.t("objectstore.upload"), this::uploadFile));
        VBox.setVgrow(table, Priority.ALWAYS);

        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<ObjectEntry> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() != 2 || row.isEmpty()) {
                    return;
                }
                ObjectEntry entry = row.getItem();
                if (entry.folder()) {
                    prefix = entry.key();
                    prefixField.setText(prefix);
                    load();
                } else {
                    showMetadata(entry.key());
                }
            });
            return row;
        });
    }

    /** 首次显示时触发加载（切回已激活的标签不再重读）。 */
    void activate() {
        if (!activated) {
            activated = true;
            load();
        }
    }

    private void load() {
        if (busy) {
            return;
        }
        busy = true;
        statusLabel.setText(I18n.t("objectstore.loading"));
        breadcrumb.setText("/" + prefix);
        new Thread(() -> {
            try {
                ObjectStoreSession s = ensureSession();
                List<ObjectEntry> rows = List.copyOf(s.listObjects(bucket, prefix, "/"));
                ObjectStoreCapabilities caps = s.capabilities();
                Platform.runLater(() -> {
                    table.getItems().setAll(rows);
                    capabilities = caps;
                    applyCapabilities();
                    statusLabel.setText(
                            I18n.tPlural("objectstore.count", rows.size(), rows.size()));
                    busy = false;
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.t("objectstore.error", msg));
                    busy = false;
                });
            }
        }).start();
    }

    /**
     * 门禁用：直接灌一份能力声明，避免为了测「置灰」去起云会话线程。
     * 生产链路仍只有 {@link #load()} → {@link #applyCapabilities()} 一条。
     */
    void applyCapabilitiesForTest(ObjectStoreCapabilities caps) {
        this.capabilities = caps;
        applyCapabilities();
    }

    /** 门禁用：读那颗随能力开合的按钮状态。 */
    javafx.scene.control.Button moveButtonForTest() {
        return moveButton;
    }

    /** 能力声明 → 按钮状态：不支持就置灰并说明，绝不留假控件。 */
    private void applyCapabilities() {
        if (capabilities == null || moveButton == null) {
            return;
        }
        boolean supports = capabilities.supportsMove();
        moveButton.setDisable(!supports);
        String note = supports ? I18n.t("objectstore.move")
                : I18n.t(capabilities.moveNote());
        // setTooltip 而不是 Tooltip.install：后者只挂到场景的 tooltip 行为上，
        // getTooltip() 仍为 null —— 无障碍读数与门禁都读不到这句原因说明。
        moveButton.setTooltip(new javafx.scene.control.Tooltip(note));
    }

    /** 移动 / 重命名：不支持的 provider 从 UI 到后端都明确拒绝。 */
    private void moveSelected() {
        if (capabilities != null && !capabilities.supportsMove()) {
            statusLabel.setText(I18n.t("objectstore.status.unsupported"));
            return;
        }
        ObjectEntry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.folder()) {
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(selected.key());
        dialog.setTitle(I18n.t("objectstore.move"));
        dialog.setHeaderText(null);
        // ⭐ §七：弹窗有自己的 Scene，主题类不抄过去就是满屏 Modena 灰。
        DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
        java.util.Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }
        String from = selected.key();
        String to = result.get().trim();
        mutate(() -> ensureSession().moveObject(bucket, from, to),
                I18n.t("objectstore.status.moved"));
    }

    /** 会话按需建立一次（后台线程调用）。 */
    private ObjectStoreSession ensureSession() {
        ObjectStoreSession s = session;
        if (s == null) {
            s = sessionFactory.get();
            if (s == null) {
                throw new IllegalStateException("object store session factory returned null");
            }
            session = s;
        }
        return s;
    }

    private void upOneLevel() {
        if (prefix.isEmpty()) {
            return;
        }
        String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        int cut = trimmed.lastIndexOf('/');
        prefix = cut < 0 ? "" : trimmed.substring(0, cut + 1);
        prefixField.setText(prefix);
        load();
    }

    // ── 操作（写在后台线程，回弹到 FX 线程）──

    private void uploadFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("objectstore.upload.title", bucket));
        File file = chooser.showOpenDialog(getScene() == null
                ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        String key = prefix + file.getName();
        mutate(() -> {
            byte[] data = Files.readAllBytes(file.toPath());
            ensureSession().putObject(bucket, key, data, probeContentType(file));
        }, I18n.t("objectstore.status.uploaded"));
    }

    private void downloadSelected() {
        ObjectEntry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.folder()) {
            return;
        }
        File target = new FileChooser().showSaveDialog(getScene() == null
                ? null : getScene().getWindow());
        if (target == null) {
            return;
        }
        String key = selected.key();
        mutate(() -> {
            byte[] data = ensureSession().getObject(bucket, key);
            Files.write(target.toPath(), data);
        }, I18n.t("objectstore.status.downloaded"));
    }

    private void deleteSelected() {
        ObjectEntry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.folder()) {
            return;
        }
        if (!confirm(I18n.t("objectstore.confirm.delete.header", selected.key()),
                I18n.t("objectstore.confirm.delete.content"),
                I18n.t("objectstore.delete"), "DELETE_OBJECT")) {
            return;
        }
        String key = selected.key();
        mutate(() -> ensureSession().deleteObject(bucket, key),
                I18n.t("objectstore.status.deleted"));
    }

    private void deleteCurrentPrefix() {
        String target = prefix;
        if (!confirm(I18n.t("objectstore.confirm.deletePrefix.header", target),
                I18n.t("objectstore.confirm.deletePrefix.content"),
                I18n.t("objectstore.deletePrefix"), "DELETE_PREFIX")) {
            return;
        }
        mutate(() -> ensureSession().deletePrefix(bucket, target),
                I18n.t("objectstore.status.deleted"));
    }

    private void copyPresignedUrl() {
        ObjectEntry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.folder()) {
            return;
        }
        String key = selected.key();
        new Thread(() -> {
            try {
                String url = ensureSession().presignUrl(bucket, key, 600L);
                Platform.runLater(() -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(url);
                    Clipboard.getSystemClipboard().setContent(content);
                    statusLabel.setText(I18n.t("objectstore.status.presign"));
                    showPresign(key, url);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> statusLabel.setText(I18n.t("objectstore.error", msg)));
            }
        }).start();
    }

    private void showMetadata(String key) {
        new Thread(() -> {
            try {
                ObjectMeta meta = ensureSession().metadata(bucket, key);
                Platform.runLater(() -> renderDetails(I18n.t("objectstore.metadata.title", key),
                        List.of(
                                I18n.t("objectstore.meta.key") + ": " + meta.key(),
                                I18n.t("objectstore.meta.size") + ": " + humanSize(meta.size()),
                                I18n.t("objectstore.meta.modified") + ": "
                                        + formatTime(meta.lastModified()),
                                I18n.t("objectstore.meta.etag") + ": " + meta.etag(),
                                I18n.t("objectstore.meta.storage") + ": "
                                        + meta.storageClass())));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> statusLabel.setText(I18n.t("objectstore.error", msg)));
            }
        }).start();
    }

    private void showPresign(String key, String url) {
        renderDetails(I18n.t("objectstore.presign.title", key),
                List.of(url, I18n.t("objectstore.copyUrl")));
    }

    private void renderDetails(String title, List<String> lines) {
        Label heading = new Label(title);
        heading.getStyleClass().add("adb-details__title");
        details.getChildren().setAll(heading);
        for (String line : lines) {
            Label label = new Label(line);
            label.getStyleClass().add("adb-muted");
            label.setWrapText(true);
            details.getChildren().add(label);
        }
        var close = new javafx.scene.control.Button(I18n.t("redis.value.close"));
        close.getStyleClass().addAll("adb-button");
        close.setOnAction(event -> {
            details.setVisible(false);
            details.setManaged(false);
        });
        details.getChildren().add(close);
        details.setVisible(true);
        details.setManaged(true);
    }

    /** 后台执行写操作 → FX 回弹刷新 + 状态提示。 */
    private void mutate(ThrowingOperation op, String okMessage) {
        new Thread(() -> {
            try {
                op.run();
                Platform.runLater(() -> {
                    statusLabel.setText(okMessage);
                    busy = false;
                    load();
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> statusLabel.setText(I18n.t("objectstore.error", msg)));
            }
        }).start();
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    /**
     * 写安全分级在这里真正生效：等级由 {@link ObjectStoreDanger} 给出，
     * 只有 DESTRUCTIVE / ADMIN 才弹确认框；未知操作 fail-closed 到 ADMIN 也会被拦。
     */
    private boolean confirm(String header, String content, String label, String op) {
        if (!ObjectStoreDanger.level(op).requiresApproval()) {
            return true;
        }
        DestructiveConfirm confirm = DestructiveConfirm.of(
                I18n.t("objectstore.header", profile.name(), bucket),
                header, content, label);
        // ⭐ §七：DestructiveConfirm 自己只造 Alert，主题类得由调用方抄过去，
        //    否则这颗确认框会退回 Modena 灰皮肤（DialogStylesTest 按文件计数守着）。
        DialogStyles.apply(confirm.alert(), getScene() == null ? null : getScene().getWindow());
        return confirm.confirmed();
    }

    /** 密度档位：直接改行高（与 RedisKeysPage 同口径）。 */
    void setDensity(String density) {
        double h = switch (density) {
            case "compact" -> 24;
            case "loose" -> 40;
            default -> 32;
        };
        table.setFixedCellSize(h);
    }

    /** 断开底层会话（标签关闭时由 WorkspaceView.closeTab 调用）。 */
    void close() {
        ObjectStoreSession s = session;
        session = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // 关闭失败不影响 UI 释放。
            }
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String formatTime(Instant instant) {
        return instant == null ? "" : TIME.format(instant);
    }

    private static String probeContentType(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            return "text/plain";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }
}
