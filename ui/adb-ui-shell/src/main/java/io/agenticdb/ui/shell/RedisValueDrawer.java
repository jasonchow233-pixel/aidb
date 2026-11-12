package io.agenticdb.ui.shell;

import io.agenticdb.model.Danger;
import io.agenticdb.redis.RedisSession;
import io.agenticdb.redis.RedisValue;
import io.agenticdb.redis.RedisString;
import io.agenticdb.redis.RedisList;
import io.agenticdb.redis.RedisSet;
import io.agenticdb.redis.RedisHash;
import io.agenticdb.redis.RedisZSet;
import io.agenticdb.redis.RedisStream;
import io.agenticdb.redis.ScoredMember;
import io.agenticdb.redis.StreamEntry;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ⭐ EXT（P1-6）：Redis 值查看 / 编辑抽屉。
 *
 * <p>挂在 {@link RedisKeysPage} 右侧，双击键行时打开。按类型渲染不同的编辑器：
 * string 用多行文本、list/set 用可增删的元素列表、hash 用字段表、zset 带分值、
 * stream 只读（XADD 追加不在本页，诚实标灰）。
 *
 * <p>写安全：所有写操作（改值 / 增删元素 / 设 TTL / 删键）都走 SQL 已有的三级门禁语义 ——
 * 调用前按 {@link RedisDanger} 的等级弹确认框（{@link DestructiveConfirm}），
 * DEL / FLUSH 是 {@link Danger#DESTRUCTIVE}，其余写是 {@link Danger#MUTATING}。
 * 抽屉本身不持有会话，从 {@code sessionSupplier} 现取（后台线程里取，避免卡 UI）。
 */
final class RedisValueDrawer extends VBox {

    private final int db;
    private final Supplier<RedisSession> sessionSupplier;
    private final Runnable onChanged;
    private final Consumer<String> onStatus;

    private final Label title = new Label();
    private final Label typeLabel = new Label();
    private final Label ttlLabel = new Label();
    private final VBox body = new VBox(10);
    private final Button delete = IconButtons.of(Icons.Icon.DELETE, I18n.t("redis.value.delete"));
    private final Button close = IconButtons.icon(Icons.Icon.CLOSE, I18n.t("redis.value.close"));

    private String currentKey;

    RedisValueDrawer(int db, Supplier<RedisSession> sessionSupplier,
                     Runnable onChanged, Consumer<String> onStatus) {
        this.db = db;
        this.sessionSupplier = sessionSupplier;
        this.onChanged = onChanged == null ? () -> { } : onChanged;
        this.onStatus = onStatus == null ? m -> { } : onStatus;

        getStyleClass().add("adb-details");
        setId("redis-value");
        setVisible(false);
        setManaged(false);
        setMinWidth(360);
        setPrefWidth(440);
        setMaxWidth(560);

        title.getStyleClass().add("adb-details__title");
        typeLabel.getStyleClass().add("adb-muted");
        ttlLabel.getStyleClass().add("adb-muted");

        delete.getStyleClass().addAll("adb-button", "adb-button--danger");
        delete.setOnAction(event -> deleteCurrent());
        close.getStyleClass().addAll("adb-icon-button");
        close.setTooltip(new javafx.scene.control.Tooltip(I18n.t("redis.value.close")));
        close.setOnAction(event -> hideDrawer());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, delete, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("adb-details__header");

        VBox meta = new VBox(4, typeLabel, ttlLabel);
        meta.setPadding(new Insets(0, 0, 8, 0));

        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, meta, body);
        setSpacing(8);
    }

    /** 打开并异步加载某个 key 的值。 */
    void open(String key) {
        this.currentKey = key;
        setVisible(true);
        setManaged(true);
        title.setText(I18n.t("redis.value.title", key));
        body.getChildren().setAll(new Label(I18n.t("redis.keys.loading")));
        typeLabel.setText("");
        ttlLabel.setText("");

        new Thread(() -> {
            try {
                RedisSession s = sessionSupplier.get();
                if (s == null) {
                    throw new IllegalStateException("no session");
                }
                RedisValue value = s.get(db, key);
                long ttl = s.ttl(db, key);
                Platform.runLater(() -> render(value, ttl));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> {
                    body.getChildren().setAll(new Label(I18n.t("redis.value.error", msg)));
                    typeLabel.setText("");
                    ttlLabel.setText("");
                });
            }
        }).start();
    }

    void hideDrawer() {
        setVisible(false);
        setManaged(false);
        currentKey = null;
    }

    private void render(RedisValue value, long ttl) {
        if (value == null) {
            body.getChildren().setAll(new Label(I18n.t("redis.value.error", "key not found")));
            typeLabel.setText("");
            ttlLabel.setText("");
            return;
        }
        typeLabel.setText(I18n.t("redis.value.type") + ": " + value.type());
        ttlLabel.setText(I18n.t("redis.value.ttl") + ": "
                + (ttl < 0 ? I18n.t("redis.value.ttlForever") : ttl + " " + I18n.t("redis.value.ttlMs")));

        renderTtlBar(value.type());
        renderEditor(value);
    }

    // ── TTL 工具条：设置 / 清除（均 MUTATING，走确认）──

    private void renderTtlBar(String type) {
        TextField ttlInput = new TextField();
        ttlInput.setPromptText(I18n.t("redis.value.ttlPrompt"));
        ttlInput.setPrefWidth(160);
        Button setTtl = IconButtons.of(Icons.Icon.SAVE, I18n.t("redis.value.ttlSet"));
        setTtl.getStyleClass().addAll("adb-button");
        setTtl.setOnAction(event -> {
            String raw = ttlInput.getText().trim();
            if (raw.isEmpty()) {
                return;
            }
            long ms;
            try {
                ms = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                onStatus.accept(I18n.t("redis.value.error", "TTL must be a number"));
                return;
            }
            if (!confirm(Danger.MUTATING, I18n.t("redis.value.ttlSet"),
                    I18n.t("redis.value.ttl"), I18n.t("redis.value.ttlSet"))) {
                return;
            }
            mutate(() -> sessionSupplier.get().expire(db, currentKey, ms),
                    () -> {
                        ttlLabel.setText(I18n.t("redis.value.ttl") + ": " + ms + " " + I18n.t("redis.value.ttlMs"));
                        onStatus.accept(I18n.t("redis.value.ttlUpdated"));
                    });
        });

        Button clearTtl = IconButtons.of(Icons.Icon.REFRESH, I18n.t("redis.value.ttlClear"));
        clearTtl.getStyleClass().addAll("adb-button", "adb-button--ghost");
        clearTtl.setOnAction(event -> {
            if (!confirm(Danger.MUTATING, I18n.t("redis.value.ttlClear"),
                    I18n.t("redis.value.ttl"), I18n.t("redis.value.ttlClear"))) {
                return;
            }
            mutate(() -> sessionSupplier.get().persist(db, currentKey),
                    () -> {
                        ttlLabel.setText(I18n.t("redis.value.ttl") + ": " + I18n.t("redis.value.ttlForever"));
                        onStatus.accept(I18n.t("redis.value.ttlUpdated"));
                    });
        });

        HBox ttlBar = new HBox(8, new Label(I18n.t("redis.value.ttl")), ttlInput, setTtl, clearTtl);
        ttlBar.setAlignment(Pos.CENTER_LEFT);
        ttlBar.getStyleClass().add("adb-toolbar__group");
        body.getChildren().setAll(ttlBar);
    }

    // ── 按类型渲染编辑器（string/list/set/hash/zset/stream）──

    private void renderEditor(RedisValue value) {
        switch (value) {
            case RedisString v -> renderString(v);
            case RedisList v -> renderList(v);
            case RedisSet v -> renderSet(v);
            case RedisHash v -> renderHash(v);
            case RedisZSet v -> renderZSet(v);
            case RedisStream v -> renderStream(v);
            default -> body.getChildren().add(new Label("unsupported type"));
        }
    }

    private void renderString(RedisString v) {
        TextArea area = new TextArea(v.value());
        area.setWrapText(true);
        VBox.setVgrow(area, Priority.ALWAYS);
        Button save = IconButtons.of(Icons.Icon.SAVE, I18n.t("redis.value.save"));
        save.getStyleClass().addAll("adb-button", "adb-button--primary");
        save.setOnAction(event -> {
            if (!confirm(Danger.MUTATING, I18n.t("redis.value.save"),
                    I18n.t("redis.value.string"), I18n.t("redis.value.save"))) {
                return;
            }
            String text = area.getText();
            mutate(() -> sessionSupplier.get().set(db, currentKey, text),
                    () -> onStatus.accept(I18n.t("redis.value.stringSaved")));
        });
        body.getChildren().addAll(new Label(I18n.t("redis.value.string")), area, save);
    }

    private void renderList(RedisList v) {
        ListView<String> list = new ListView<>();
        list.getItems().setAll(v.elements());
        body.getChildren().add(new Label(I18n.t("redis.value.list")));
        body.getChildren().add(list);
        body.getChildren().add(elementEditor(
                () -> new TextField(),
                input -> mutate(() -> sessionSupplier.get().listAppend(db, currentKey, input.getText()),
                        () -> { list.getItems().add(input.getText()); onStatus.accept(I18n.t("redis.value.added")); }),
                item -> mutate(() -> sessionSupplier.get().listRemove(db, currentKey, item),
                        () -> { list.getItems().remove(item); onStatus.accept(I18n.t("redis.value.removed")); }),
                list));
    }

    private void renderSet(RedisSet v) {
        ListView<String> list = new ListView<>();
        list.getItems().setAll(v.members());
        body.getChildren().add(new Label(I18n.t("redis.value.set")));
        body.getChildren().add(list);
        body.getChildren().add(elementEditor(
                () -> new TextField(),
                input -> mutate(() -> sessionSupplier.get().setAdd(db, currentKey, input.getText()),
                        () -> { list.getItems().add(input.getText()); onStatus.accept(I18n.t("redis.value.added")); }),
                item -> mutate(() -> sessionSupplier.get().setRemove(db, currentKey, item),
                        () -> { list.getItems().remove(item); onStatus.accept(I18n.t("redis.value.removed")); }),
                list));
    }

    private void renderHash(RedisHash v) {
        TableView<Map.Entry<String, String>> table = new TableView<>();
        List<Map.Entry<String, String>> rows = new ArrayList<>(v.fields().entrySet());
        table.getItems().setAll(rows);

        TableColumn<Map.Entry<String, String>, String> fCol = new TableColumn<>(I18n.t("redis.value.field"));
        fCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getKey()));
        fCol.setPrefWidth(140);
        TableColumn<Map.Entry<String, String>, String> vCol = new TableColumn<>(I18n.t("redis.value.value"));
        vCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getValue()));
        vCol.setPrefWidth(200);
        table.getColumns().setAll(fCol, vCol,
                actionsColumn((Map.Entry<String, String> item) -> mutate(
                        () -> sessionSupplier.get().hashRemove(db, currentKey, item.getKey()),
                        () -> { table.getItems().remove(item); onStatus.accept(I18n.t("redis.value.removed")); })));
        VBox.setVgrow(table, Priority.ALWAYS);

        body.getChildren().add(new Label(I18n.t("redis.value.hash")));
        body.getChildren().add(table);

        // 添加字段：field + value 两个输入。
        TextField fieldInput = new TextField();
        fieldInput.setPromptText(I18n.t("redis.value.field"));
        TextField valueInput = new TextField();
        valueInput.setPromptText(I18n.t("redis.value.value"));
        Button add = IconButtons.of(Icons.Icon.ADD, I18n.t("redis.value.add"));
        add.getStyleClass().addAll("adb-button");
        add.setOnAction(event -> {
            String f = fieldInput.getText().trim();
            if (f.isEmpty()) {
                return;
            }
            String val = valueInput.getText();
            mutate(() -> sessionSupplier.get().hashPut(db, currentKey, f, val),
                    () -> { table.getItems().add(Map.entry(f, val)); onStatus.accept(I18n.t("redis.value.added")); });
        });

        HBox addBar = new HBox(8, fieldInput, valueInput, add);
        addBar.setAlignment(Pos.CENTER_LEFT);
        addBar.getStyleClass().add("adb-toolbar__group");
        body.getChildren().add(addBar);
    }

    private void renderZSet(RedisZSet v) {
        TableView<ScoredMember> table = new TableView<>();
        table.getItems().setAll(v.members());
        TableColumn<ScoredMember, String> mCol = new TableColumn<>(I18n.t("redis.value.element"));
        mCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().member()));
        mCol.setPrefWidth(160);
        TableColumn<ScoredMember, Number> sCol = new TableColumn<>(I18n.t("redis.value.score"));
        sCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue().score()));
        sCol.setPrefWidth(120);
        table.getColumns().setAll(mCol, sCol,
                actionsColumn((ScoredMember item) -> mutate(
                        () -> sessionSupplier.get().zRemove(db, currentKey, item.member()),
                        () -> { table.getItems().remove(item); onStatus.accept(I18n.t("redis.value.removed")); })));
        VBox.setVgrow(table, Priority.ALWAYS);

        body.getChildren().add(new Label(I18n.t("redis.value.zset")));
        body.getChildren().add(table);

        TextField memberInput = new TextField();
        memberInput.setPromptText(I18n.t("redis.value.element"));
        TextField scoreInput = new TextField();
        scoreInput.setPromptText(I18n.t("redis.value.score"));
        Button add = IconButtons.of(Icons.Icon.ADD, I18n.t("redis.value.add"));
        add.getStyleClass().addAll("adb-button");
        add.setOnAction(event -> {
            String m = memberInput.getText().trim();
            if (m.isEmpty()) {
                return;
            }
            double score;
            try {
                score = Double.parseDouble(scoreInput.getText().trim().isEmpty()
                        ? "0" : scoreInput.getText().trim());
            } catch (NumberFormatException e) {
                onStatus.accept(I18n.t("redis.value.error", "score must be a number"));
                return;
            }
            mutate(() -> sessionSupplier.get().zAdd(db, currentKey, score, m),
                    () -> { table.getItems().add(new ScoredMember(m, score)); onStatus.accept(I18n.t("redis.value.added")); });
        });

        HBox addBar = new HBox(8, memberInput, scoreInput, add);
        addBar.setAlignment(Pos.CENTER_LEFT);
        addBar.getStyleClass().add("adb-toolbar__group");
        body.getChildren().add(addBar);
    }

    /** 表格末列的「删除」按钮列（泛型，按行项目回调删除）。 */
    private <T> TableColumn<T, Void> actionsColumn(java.util.function.Consumer<T> onRemove) {
        TableColumn<T, Void> col = new TableColumn<>("");
        col.setPrefWidth(48);
        col.setSortable(false);
        col.setCellFactory(tc -> new javafx.scene.control.TableCell<>() {
            private final Button btn = IconButtons.icon(Icons.Icon.DELETE, I18n.t("redis.value.remove"));
            {
                btn.getStyleClass().addAll("adb-icon-button", "adb-button--danger");
                btn.setOnAction(e -> {
                    T item = getTableView().getItems().get(getIndex());
                    if (item != null) {
                        onRemove.accept(item);
                    }
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });
        return col;
    }

    private void renderStream(RedisStream v) {
        ListView<String> list = new ListView<>();
        List<String> entries = new ArrayList<>();
        for (StreamEntry e : v.entries()) {
            entries.add(e.id() + " → " + e.fields());
        }
        list.getItems().setAll(entries);
        body.getChildren().add(new Label(I18n.t("redis.value.stream")));
        body.getChildren().add(list);
        Label note = new Label(I18n.t("redis.value.stream.readonly"));
        note.getStyleClass().add("adb-muted");
        body.getChildren().add(note);
    }

    /** list/set 的「输入 + 添加 + 逐行删除」通用编辑器。 */
    private <T> HBox elementEditor(java.util.function.Supplier<TextField> inputFactory,
                                   java.util.function.Consumer<TextField> onAdd,
                                   java.util.function.Consumer<T> onRemove,
                                   ListView<T> list) {
        TextField input = inputFactory.get();
        input.setPromptText(I18n.t("redis.value.elementPrompt"));
        input.setPrefWidth(200);
        Button add = IconButtons.of(Icons.Icon.ADD, I18n.t("redis.value.add"));
        add.getStyleClass().addAll("adb-button");
        add.setOnAction(event -> {
            if (input.getText().trim().isEmpty()) {
                return;
            }
            onAdd.accept(input);
            input.clear();
        });
        HBox bar = new HBox(8, input, add);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("adb-toolbar__group");

        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(String.valueOf(item));
                Button rm = IconButtons.icon(Icons.Icon.DELETE, I18n.t("redis.value.remove"));
                rm.getStyleClass().addAll("adb-icon-button", "adb-button--danger");
                rm.setOnAction(e -> onRemove.accept(item));
                setGraphic(rm);
            }
        });
        return bar;
    }

    private void deleteCurrent() {
        if (currentKey == null) {
            return;
        }
        if (!confirm(Danger.DESTRUCTIVE, I18n.t("redis.confirm.deleteKey.header", currentKey),
                I18n.t("redis.confirm.deleteKey.content"), I18n.t("redis.value.delete"))) {
            return;
        }
        String key = currentKey;
        mutate(() -> sessionSupplier.get().del(db, key),
                () -> {
                    onStatus.accept(I18n.t("redis.value.deleted"));
                    hideDrawer();
                    onChanged.run();
                });
    }

    /** 在后台线程执行写操作，成功后在 FX 线程跑 onOk。失败回显到状态条。 */
    private void mutate(Runnable op, Runnable onOk) {
        new Thread(() -> {
            try {
                op.run();
                Platform.runLater(onOk);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> onStatus.accept(I18n.t("redis.value.error", msg)));
            }
        }).start();
    }

    /** 按危险等级弹确认框：READ_ONLY 直接过；其余用 DestructiveConfirm（取消默认）。 */
    private boolean confirm(Danger danger, String header, String content, String destructiveLabel) {
        if (!danger.requiresConfirm()) {
            return true;
        }
        DestructiveConfirm confirm = DestructiveConfirm.of(
                I18n.t("redis.value.title", currentKey == null ? "" : currentKey),
                header, content, destructiveLabel);
        // ⭐ §七：DestructiveConfirm 自己只造 Alert，主题类得由调用方抄过去，
        //    否则这颗确认框会退回 Modena 灰皮肤（DialogStylesTest 按文件计数守着）。
        DialogStyles.apply(confirm.alert(), getScene() == null ? null : getScene().getWindow());
        return confirm.confirmed();
    }
}
