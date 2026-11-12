package io.agenticdb.ui.shell;

import io.agenticdb.redis.RedisKey;
import io.agenticdb.redis.RedisSession;
import io.agenticdb.redis.RedisSessions;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * ⭐ EXT（P1-5）：Redis 键浏览器页。
 *
 * <p>按 {@code (连接, db)} 一个实例并缓存（{@link WorkspaceView} 按 tab id 存），
 * 切标签不会重连、不会丢前缀 / 类型过滤状态。本身不持有连接会话 —— 会话在首次
 * {@link #activate()} 时按需建立，{@link #close()} 时断开，避免「能看却没释放」的泄漏。
 *
 * <p>列数据（key / 类型 / TTL / 大小）都来自 {@code adb-redis} 的只读 {@code scan}，
 * 值在 P1-6 才按需拉取。TTL 按 Redis 语义渲染（{@code -1} = 不过期 = 「永久」）。
 */
final class RedisKeysPage extends VBox {

    private final ConnectionProfile profile;
    private final String password;
    private final int db;

    private final TableView<RedisKey> table = new TableView<>();
    private final TextField prefixField = new TextField();
    private final ComboBox<String> typeCombo = new ComboBox<>();
    private final Label statusLabel = new Label();
    private final RedisValueDrawer drawer;

    private volatile RedisSession session;
    private volatile boolean busy;
    private boolean activated;

    RedisKeysPage(TabItem tab, ConnectionProfile profile, String password) {
        this.profile = profile;
        this.password = password == null ? "" : password;
        this.db = parseDb(tab.target());

        getStyleClass().add("adb-page");
        setSpacing(0);

        HBox header = buildHeader();
        header.getStyleClass().add("adb-page__header");

        drawer = new RedisValueDrawer(db, this::ensureSession, this::reload, statusLabel::setText);
        HBox body = new HBox(table, drawer);
        body.getStyleClass().add("adb-page__body");
        HBox.setHgrow(table, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, new javafx.scene.control.Separator(), body);

        configureTable();
        // 双击键行 → 打开值查看/编辑抽屉。
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<RedisKey> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    drawer.open(row.getItem().key());
                }
            });
            return row;
        });
        setDensity("standard");
        statusLabel.setText(I18n.t("redis.keys.empty"));
    }

    private static int parseDb(String target) {
        if (target == null) {
            return 0;
        }
        try {
            return Integer.parseInt(target.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private HBox buildHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(I18n.t("redis.keys.header", profile.name(), db));
        title.getStyleClass().add("adb-page__title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        prefixField.setPromptText(I18n.t("redis.keys.prefix"));
        prefixField.setPrefWidth(180);
        prefixField.setOnAction(event -> load());

        typeCombo.getItems().addAll(
                I18n.t("redis.keys.type.all"), "string", "list", "set", "zset", "hash", "stream");
        typeCombo.setValue(I18n.t("redis.keys.type.all"));
        typeCombo.setPrefWidth(120);

        var refresh = IconButtons.of(Icons.Icon.REFRESH, I18n.t("redis.keys.refresh"));
        refresh.getStyleClass().addAll("adb-button");
        refresh.setOnAction(event -> load());

        HBox filter = new HBox(8, prefixField, typeCombo, refresh);
        filter.getStyleClass().add("adb-toolbar__group");
        filter.setAlignment(Pos.CENTER_LEFT);

        statusLabel.getStyleClass().add("adb-muted");

        // ⚠️ 危险操作集中在一处，且都用 DESTRUCTIVE 确认（见方法内的 confirmDestructive）。
        var delSelected = IconButtons.of(Icons.Icon.DELETE, I18n.t("redis.keys.delSelected"));
        delSelected.getStyleClass().addAll("adb-button", "adb-button--danger");
        delSelected.setOnAction(event -> deleteSelected());

        var flushDbBtn = IconButtons.of(Icons.Icon.DELETE, I18n.t("redis.keys.flushDb"));
        flushDbBtn.getStyleClass().addAll("adb-button", "adb-button--danger");
        flushDbBtn.setOnAction(event -> flushDb());

        var flushAllBtn = IconButtons.of(Icons.Icon.DELETE, I18n.t("redis.keys.flushAll"));
        flushAllBtn.getStyleClass().addAll("adb-button", "adb-button--danger");
        flushAllBtn.setOnAction(event -> flushAll());

        HBox danger = new HBox(8, delSelected, flushDbBtn, flushAllBtn);
        danger.getStyleClass().add("adb-toolbar__group");
        danger.setAlignment(Pos.CENTER_LEFT);

        header.getChildren().addAll(title, spacer, filter, danger, statusLabel);
        return header;
    }

    @SuppressWarnings("unchecked")
    private void configureTable() {
        TableColumn<RedisKey, String> keyCol = new TableColumn<>(I18n.t("redis.keys.column.key"));
        // ⚠️ RedisKey 是 record，访问器是 key()/type()/size() 而非 getXxx()，
        //    PropertyValueFactory 找不到 getter，必须用 lambda 工厂（否则列表列空白）。
        keyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().key()));
        keyCol.setPrefWidth(360);

        TableColumn<RedisKey, String> typeCol = new TableColumn<>(I18n.t("redis.keys.column.type"));
        typeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().type()));
        typeCol.setPrefWidth(110);

        TableColumn<RedisKey, String> ttlCol = new TableColumn<>(I18n.t("redis.keys.column.ttl"));
        ttlCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                formatTtl(c.getValue().ttlMillis())));
        ttlCol.setPrefWidth(120);

        TableColumn<RedisKey, Number> sizeCol = new TableColumn<>(I18n.t("redis.keys.column.size"));
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue().size()));
        sizeCol.setPrefWidth(110);

        table.getColumns().setAll(keyCol, typeCol, ttlCol, sizeCol);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label(I18n.t("redis.keys.empty")));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    private static String formatTtl(long ttlMillis) {
        if (ttlMillis < 0) {
            return I18n.t("redis.keys.ttlForever");
        }
        if (ttlMillis >= 1000) {
            return String.format("%.1fs", ttlMillis / 1000.0);
        }
        return ttlMillis + "ms";
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
        statusLabel.setText(I18n.t("redis.keys.loading"));

        String prefix = prefixField.getText();
        String selected = typeCombo.getValue();
        String typeFilter = selected == null || selected.equals(I18n.t("redis.keys.type.all"))
                ? null : selected;

        new Thread(() -> {
            try {
                RedisSession s = session;
                if (s == null) {
                    s = RedisSessions.connect(profile, password);
                    session = s;
                }
                List<RedisKey> keys = List.copyOf(s.scan(db, prefix, typeFilter));
                Platform.runLater(() -> {
                    table.getItems().setAll(keys);
                    // ⚠️ 数量要按数取形：1 个 = "1 key"，其余 = "N keys"，避免「1 keys」的硬复数。
                    statusLabel.setText(I18n.tPlural("redis.keys.count", keys.size(), keys.size()));
                    busy = false;
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.t("redis.keys.error", msg));
                    busy = false;
                });
            }
        }).start();
    }

    /** 密度档位：直接改行高（与 CSS 无关，保证三档可见差异）。 */
    void setDensity(String density) {
        double h = switch (density) {
            case "compact" -> 24;
            case "loose" -> 40;
            default -> 32;
        };
        table.setFixedCellSize(h);
    }

    /** 返回当前会话，没有就现建（后台线程调用安全）。 */
    private RedisSession ensureSession() {
        RedisSession s = session;
        if (s == null) {
            s = RedisSessions.connect(profile, password);
            session = s;
        }
        return s;
    }

    /** 重新扫描当前库（删除 / 清空 / TTL 变更后刷新列表）。 */
    void reload() {
        load();
    }

    /** 删除选中的 key（DESTRUCTIVE，走确认）。 */
    private void deleteSelected() {
        RedisKey selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (!confirmDestructive(I18n.t("redis.confirm.deleteKey.header", selected.key()),
                I18n.t("redis.confirm.deleteKey.content"), I18n.t("redis.keys.delSelected"))) {
            return;
        }
        String key = selected.key();
        new Thread(() -> {
            try {
                ensureSession().del(db, key);
                Platform.runLater(() -> {
                    drawer.hideDrawer();
                    reload();
                    statusLabel.setText(I18n.t("redis.value.deleted"));
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> statusLabel.setText(I18n.t("redis.value.error", msg)));
            }
        }).start();
    }

    /** 清空当前库（DESTRUCTIVE，走确认）。 */
    private void flushDb() {
        if (!confirmDestructive(I18n.t("redis.confirm.flushDb.header", db),
                I18n.t("redis.confirm.flushDb.content"), I18n.t("redis.keys.flushDb"))) {
            return;
        }
        new Thread(() -> {
            try {
                ensureSession().flushDb(db);
                Platform.runLater(() -> {
                    drawer.hideDrawer();
                    reload();
                    statusLabel.setText(I18n.t("redis.value.deleted"));
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> statusLabel.setText(I18n.t("redis.value.error", msg)));
            }
        }).start();
    }

    /** 清空所有库（DESTRUCTIVE，走确认 —— 跨库，最危险）。 */
    private void flushAll() {
        if (!confirmDestructive(I18n.t("redis.confirm.flushAll.header"),
                I18n.t("redis.confirm.flushAll.content"), I18n.t("redis.keys.flushAll"))) {
            return;
        }
        new Thread(() -> {
            try {
                ensureSession().flushAll();
                Platform.runLater(() -> {
                    drawer.hideDrawer();
                    reload();
                    statusLabel.setText(I18n.t("redis.value.deleted"));
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> statusLabel.setText(I18n.t("redis.value.error", msg)));
            }
        }).start();
    }

    /** DESTRUCTIVE 专用确认：取消为默认按钮（回车不会落到「删除」上）。 */
    private boolean confirmDestructive(String header, String content, String destructiveLabel) {
        return DestructiveConfirm.of(I18n.t("redis.value.title", ""), header, content,
                destructiveLabel).confirmed();
    }

    /** 断开底层会话（标签关闭时由 WorkspaceView.closeTab 调用）。 */
    void close() {
        RedisSession s = session;
        session = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // 关闭失败不影响 UI 释放。
            }
        }
    }
}
