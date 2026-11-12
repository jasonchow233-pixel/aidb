package io.agenticdb.ui.shell;

import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.RowHeights;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 连接管理页：搜索、收藏、打开 / 编辑 / 复制 / 删除，全部操作经回调交给 AppShell。
 *
 * <p>⭐ <b>用表格而不是列表</b>（Navicat 形态）：
 * 以前每个连接占一行，名称与目标挤在两行文字里 —— 用户要点开每个连接才知道连的什么。
 * 现在拆成 <b>名称 / 类型 / 主机 / 端口 / 数据库 / 用户 / 状态</b> 七列，
 * 每列独立、**可排序、可拖列宽**，一眼扫完。
 */
public final class ConnectionsPage extends VBox implements AutoCloseable {

    private final ObservableList<ConnectionProfile> profiles;
    private final FilteredList<ConnectionProfile> visible;
    private final TableView<ConnectionProfile> table = new TableView<>();
    /** 密度档位 → 行高（不写死）。 */
    private int rowHeight = RowHeights.of("normal");
    private final TextField search = new TextField();
    private final CheckBox favoritesOnly = new CheckBox(I18n.t("sidebar.onlyFavorites"));
    private final Label status = new Label("—");
    private final Button open = new Button(I18n.t("sidebar.menuOpen"));
    private final Button edit = new Button(I18n.t("menu.edit"));
    private final Button duplicate = new Button(I18n.t("action.edit.copy"));
    private final Button rename = new Button(I18n.t("structure.field.rename"));
    private final Button group = new Button(I18n.t("conn.action.group"));
    private final Button delete = new Button(I18n.t("common.delete"));
    private final Button refresh = IconButtons.of(Icons.Icon.REFRESH, I18n.t("action.refresh"));
    /** ⭐ SSH known_hosts 管理入口：连接页是「信任过哪些跳板机」最该被找到的地方。 */
    private final Button sshHosts = new Button(I18n.t("conn.ssh.hosts"));

    private Predicate<ConnectionProfile> deleteConfirmation = this::confirmDelete;
    private Consumer<ConnectionProfile> onOpen;
    private Consumer<ConnectionProfile> onEdit;
    private Consumer<ConnectionProfile> onDuplicate;
    private Consumer<ConnectionProfile> onDelete;
    private Runnable onNew;
    private Predicate<ConnectionProfile> connected = profile -> false;
    /** ⭐ P5-1：改显示名 / 换分组 —— 真正的落盘由外壳做。 */
    private BiConsumer<ConnectionProfile, String> onRename = (profile, name) -> { };
    private BiConsumer<ConnectionProfile, String> onGroup = (profile, group) -> { };

    public ConnectionsPage(ObservableList<ConnectionProfile> profiles,
                           Runnable onNew,
                           Consumer<ConnectionProfile> onOpen,
                           Consumer<ConnectionProfile> onEdit,
                           Consumer<ConnectionProfile> onDuplicate,
                           Consumer<ConnectionProfile> onDelete,
                           Predicate<ConnectionProfile> connected,
                           DataGridPage.Feedback feedback) {
        this.profiles = profiles;
        this.visible = new FilteredList<>(profiles, profile -> true);
        this.onNew = onNew == null ? () -> { } : onNew;
        this.onOpen = onOpen == null ? profile -> { } : onOpen;
        this.onEdit = onEdit == null ? profile -> { } : onEdit;
        this.onDuplicate = onDuplicate == null ? profile -> { } : onDuplicate;
        this.onDelete = onDelete == null ? profile -> { } : onDelete;
        this.connected = connected == null ? profile -> false : connected;

        getStyleClass().addAll("adb-page", "adb-connections");
        buildUi();
        bind();
        refresh();
    }

    private void buildUi() {
        Label title = new Label(I18n.t("conn.title"));
        title.getStyleClass().add("adb-page__title");
        Label badge = new Label(I18n.t("ribbon.newConnection"));
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Label context = new Label(I18n.t("conn.context.credentials"));
        context.getStyleClass().add("adb-muted");
        // §2.8：安全提示要看着像安全提示 —— 纯文字容易被当成页脚噪音，配一把锁。
        context.setGraphic(Icons.of(Icons.Icon.LOCK, Icons.SIZE_BADGE));
        context.setGraphicTextGap(6);

        HBox header = new HBox(10, title, badge, headerSpacer, context);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        Button newConnection = IconButtons.of(Icons.Icon.ADD, I18n.t("object.newConnection"));
        newConnection.setId("connections-new");
        // ⚠️ 变体类必须和基础类一起挂：只写 adb-button--primary 的话，
        //    按钮拿不到统一的 28px 行高 / 12px 内边距 / 悬停与按下反馈 ——
        //    「新建连接」就成了全应用唯一一颗尺寸不同的主按钮（任务书 §七）。
        newConnection.getStyleClass().addAll("adb-button", "adb-button--primary");
        newConnection.setOnAction(event -> onNew.run());

        open.getStyleClass().add("adb-button");
        open.setId("connections-open");
        open.setOnAction(event -> selected().ifPresent(onOpen));
        edit.getStyleClass().add("adb-button");
        edit.setId("connections-edit");
        edit.setOnAction(event -> selected().ifPresent(onEdit));
        duplicate.getStyleClass().add("adb-button");
        duplicate.setId("connections-duplicate");
        duplicate.setOnAction(event -> selected().ifPresent(onDuplicate));
        rename.getStyleClass().add("adb-button");
        rename.setId("connections-rename");
        rename.setOnAction(event -> renameSelected());
        group.getStyleClass().add("adb-button");
        group.setId("connections-group");
        group.setOnAction(event -> groupSelected());
        delete.getStyleClass().addAll("adb-button", "adb-button--danger");
        delete.setId("connections-delete");
        delete.setOnAction(event -> selected().ifPresent(this::delete));
        refresh.getStyleClass().add("adb-button");
        refresh.setId("connections-refresh");
        refresh.setOnAction(event -> refresh());
        sshHosts.getStyleClass().add("adb-button");
        sshHosts.setId("connections-ssh-hosts");
        sshHosts.setOnAction(event -> showSshHostKeys());

        HBox toolbar = new HBox(6, newConnection, open, edit, rename, group, duplicate, delete,
                refresh);
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        search.setId("connections-search");
        search.getStyleClass().add("adb-connections__search");
        search.setPromptText(I18n.t("conn.search.prompt"));
        favoritesOnly.getStyleClass().add("adb-connections__favorites");
        Region filterSpacer = new Region();
        HBox.setHgrow(filterSpacer, Priority.ALWAYS);
        HBox filter = new HBox(10, search, favoritesOnly, filterSpacer, sshHosts);
        filter.setAlignment(Pos.CENTER_LEFT);

        buildColumns();

        table.setId("connections-table");
        table.getStyleClass().addAll("adb-grid", "adb-connections__table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // ⭐ 同一句话要同时cover「搜不到」和「压根没建过」两种情况，
        //    所以说明里把两条出路都讲了。
        table.setPlaceholder(EmptyState.of(Icons.Icon.CONNECT, I18n.t("conn.empty.title"),
                I18n.t("conn.empty.note"),
                I18n.t("object.newConnection"), onNew));
        table.setFixedCellSize(rowHeight);
        VBox.setVgrow(table, Priority.ALWAYS);

        // 双击一行 = 打开（Navicat 的习惯）。
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                selected().ifPresent(onOpen);
            }
        });

        status.getStyleClass().add("adb-muted");
        VBox body = new VBox(8, toolbar, filter, table, status);
        body.getStyleClass().add("adb-page__body");
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(header, body);
    }

    /** ⭐ 已知 SSH 主机面板：非阻塞对话框，关闭按钮用项目既有的 common.close 文案。 */
    private void showSshHostKeys() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("ssh.hosts.title"));
        dialog.setHeaderText(I18n.t("ssh.hosts.title"));
        // 空态的「新建连接」出口：关掉面板、回到连接向导。
        dialog.getDialogPane().setContent(new SshHostKeysPane(() -> {
            dialog.close();
            onNew.run();
        }));
        dialog.getDialogPane().getButtonTypes().setAll(new javafx.scene.control.ButtonType(
                I18n.t("common.close"),
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setResizable(true);
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        // ⭐ §七：每颗弹窗都得套应用主题，否则会弹成 Modena 灰皮肤。
        DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
        dialog.show();
    }

    /** 七列：名称 / 类型 / 主机 / 端口 / 数据库 / 用户 / 状态。 */
    private void buildColumns() {
        TableColumn<ConnectionProfile, String> name = column(I18n.t("column.name"),
                "name", 200);
        name.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().name()));
        // 收藏标记放进「名称」列的前面 —— 一列一个信息，不另开一列。
        name.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                if (getTableRow() != null && getTableRow().getItem() != null
                        && getTableRow().getItem().favorite()) {
                    SVGPath star = Icons.of(Icons.Icon.STAR_FILLED, Icons.SIZE_BADGE);
                    star.setAccessibleText(I18n.t("conn.star.favorited"));
                    setGraphic(star);
                    setGraphicTextGap(4);
                } else {
                    setGraphic(null);
                }
            }
        });

        // ⭐ R54：加了图形就要重新量这一列 —— 纯文字时它是 90，加上图形 + 5px 间距后
        //   实拍里「POSTGRESQL」被折成「POSTGRE…」（中途试过 112，仍然差一截），
        //   最终按最长的那个名字（PostgreSQL）留到 140。
        TableColumn<ConnectionProfile, String> kind = column(I18n.t("detail.type"),
                "kind", 140);
        kind.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().kindLabel()));
        // ⭐ R54：这一列以前<b>只有文字</b>（p5-connections 实拍确认：写着 "SQLITE"，一个图形都没有）。
        //    多连接同屏时用户扫这列要回答的是「哪一行是哪家库」，而六种家名长短不一、
        //    首字母还都撞车（MySQL / MariaDB、PostgreSQL / Oracle 的 P/O）。图形与文字是两条线索，
        //    留一条等于让另一条替它读字。图形取自登记表（与树、磁贴同一枚、同一个品牌色）。
        kind.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                Object row = getTableRow() == null ? null : getTableRow().getItem();
                setGraphic(row instanceof ConnectionProfile profile
                        ? ConnectionCatalog.graphic(profile.kind(), Icons.SIZE_LABEL)
                        : null);
                setGraphicTextGap(5);
            }
        });

        // ⭐ P5-1：分组是展示层元数据，单独一列 —— 不塞进名称列（名字要能直接改名）。
        TableColumn<ConnectionProfile, String> groupColumn = column(I18n.t("conn.col.group"),
                "group", 120);
        groupColumn.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                displayOrDash(data.getValue().group())));

        TableColumn<ConnectionProfile, String> host = column(I18n.t("wizard.host"),
                "host", 180);
        host.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                displayOrDash(data.getValue().host())));

        // ⭐ M3：端口列以前直接绑原值 —— SQLite 明明没有端口概念，却显示「0」，
        //    和真的端口 0（异常值）长得一模一样。
        //    「这一族有没有端口」是**注册表层的事**（{@code AssetKind.hasHostPort()}：
        //    SQL 族问方言的 {@code usesFilePath()}，Redis 有真端口，对象存储走 endpoint 没有端口）
        //    —— 这里不能按具体方言写分支，否则就破了 A1 的承诺（加一种数据库只改 Dialect 实现）。
        //    ⚠️ 这一格曾经直接调 {@code kind().dialect()}，而对象存储 / Redis 没有方言 ——
        //    档案列表里只要有这两种连接，打开连接管理就抛 IllegalStateException（真实崩溃）。
        TableColumn<ConnectionProfile, String> port = column(I18n.t("wizard.port"),
                "port", 80);
        port.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().kind().hasHostPort()
                        ? String.valueOf(data.getValue().port())
                        : "—"));

        TableColumn<ConnectionProfile, String> database = column(I18n.t("category.database"),
                "database", 180);
        database.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                displayOrDash(data.getValue().database())));

        TableColumn<ConnectionProfile, String> user = column(I18n.t("conn.col.user"),
                "user", 130);
        user.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                displayOrDash(data.getValue().username())));

        TableColumn<ConnectionProfile, String> state = column(I18n.t("conn.col.state"),
                "state", 100);
        state.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                connected.test(data.getValue()) ? I18n.t("detail.connected") : "—"));

        table.getColumns().addAll(name, kind, groupColumn, host, port, database, user, state);
    }

    /** 空值 / 空白 → 「—」。空白单元格看起来像「渲染坏了」，横线才读得出是「没有」。 */
    private static String displayOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static <T> TableColumn<ConnectionProfile, T> column(String title, String id,
                                                                  double width) {
        TableColumn<ConnectionProfile, T> column = new TableColumn<>(title);
        column.setId("connections-column-" + id);
        column.setPrefWidth(width);
        column.setSortable(true);
        return column;
    }

    private void bind() {
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        favoritesOnly.selectedProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        profiles.addListener((javafx.collections.ListChangeListener<ConnectionProfile>) change -> {
            applyFilter();
            updateButtons();
        });
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons());
        // ⭐ U19：这排按钮在没选中行时**六个一起变灰**，第一眼像整个面板坏了。
        // 变灰本身是对的（动作确实不可用），缺的是「为什么灰、怎么做才亮」——
        // 把原因绑在 tooltip 上，选择一变就自动同步，不会出现按钮能点了还写着「先选中」。
        bindSelectionAction(open, I18n.t("conn.hint.open"));
        bindSelectionAction(edit, I18n.t("conn.hint.edit"));
        bindSelectionAction(duplicate, I18n.t("conn.hint.duplicate"));
        bindSelectionAction(rename, I18n.t("conn.hint.rename"));
        bindSelectionAction(group, I18n.t("conn.hint.group"));
        bindSelectionAction(delete, I18n.t("conn.hint.delete"));
    }

    /**
     * 「未选中连接 → 禁用」这条规则的一次成型：禁用位与 tooltip 原因绑同一个条件。
     *
     * <p>用 {@code Bindings.when} 而不是选择监听里手写文本：初始状态（开场没有选中行）
     * 也走同一条表达式，不需要再补一次「开场刷新」。
     */
    private void bindSelectionAction(Button button, String enabledHint) {
        var noneSelected = Bindings.isEmpty(table.getSelectionModel().getSelectedItems());
        button.disableProperty().bind(noneSelected);
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(Bindings.when(noneSelected)
                .then(I18n.t("conn.hint.noneSelected", enabledHint))
                .otherwise(enabledHint));
        button.setTooltip(tooltip);
    }

    public void refresh() {
        applyFilter();
        status.setText(I18n.tPlural("conn.status", profiles.size(), profiles.size(), visible.size()));
        updateButtons();
    }

    private void applyFilter() {
        String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        visible.setPredicate(profile -> {
            if (favoritesOnly.isSelected() && !profile.favorite()) {
                return false;
            }
            if (needle.isEmpty()) {
                return true;
            }
            return (profile.name() + " " + profile.host() + " " + profile.database() + " "
                    + profile.username() + " " + profile.group()).toLowerCase().contains(needle);
        });
        table.setItems(visible);
    }

    private void updateButtons() {
        // 打开/编辑/复制/删除的可用性由 selection binding 控制；
        // 这里不重复刷新，避免与 refresh() 互相调用。
    }

    /** 密度档位。 */
    public void setDensity(String density) {
        rowHeight = RowHeights.of(density);
        table.setFixedCellSize(rowHeight);
    }

    /** 连接表格（供门禁断言列数与排序）。 */
    TableView<ConnectionProfile> table() {
        return table;
    }

    private Optional<ConnectionProfile> selected() {
        return Optional.ofNullable(table.getSelectionModel().getSelectedItem());
    }

    public void setDeleteConfirmation(Predicate<ConnectionProfile> confirmation) {
        this.deleteConfirmation = confirmation == null ? profile -> true : confirmation;
    }

    /** ⭐ P5-1：重命名回调（外壳负责改内存列表 + 落盘）。 */
    public void setRenameHandler(BiConsumer<ConnectionProfile, String> handler) {
        this.onRename = handler == null ? (profile, name) -> { } : handler;
    }

    /** ⭐ P5-1：换分组回调（空串 = 移出分组）。 */
    public void setGroupHandler(BiConsumer<ConnectionProfile, String> handler) {
        this.onGroup = handler == null ? (profile, group) -> { } : handler;
    }

    /**
     * ⭐ P5-1：把选中连接改成新名字。
     *
     * <p>包级可见：门禁直接调它，绕开模态弹窗（{@code showAndWait()} 会卡住
     * FX 线程，拿不到断言时机）。
     */
    void renameSelectedTo(String newName) {
        Optional<ConnectionProfile> profile = selected();
        if (profile.isEmpty()) {
            return;
        }
        onRename.accept(profile.get(), newName);
        refreshAfterEdit();
    }

    /** ⭐ P5-1：把选中连接移到某个分组（空串 = 移出分组）。 */
    void groupSelectedAs(String groupName) {
        Optional<ConnectionProfile> profile = selected();
        if (profile.isEmpty()) {
            return;
        }
        onGroup.accept(profile.get(), groupName);
        refreshAfterEdit();
    }

    private void refreshAfterEdit() {
        applyFilter();
        status.setText(I18n.tPlural("conn.status", profiles.size(), profiles.size(), visible.size()));
    }

    private void renameSelected() {
        selected().ifPresent(profile -> {
            TextInputDialog dialog = new TextInputDialog(profile.name());
            dialog.setTitle(I18n.t("conn.rename.title"));
            dialog.setHeaderText(I18n.t("conn.rename.header", profile.name()));
            dialog.setContentText(I18n.t("column.name"));
            dialog.initOwner(getScene() == null ? null : getScene().getWindow());
            DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
            dialog.showAndWait()
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .ifPresent(this::renameSelectedTo);
        });
    }

    private void groupSelected() {
        selected().ifPresent(profile -> {
            Dialog<String> dialog = new Dialog<>();
            dialog.setTitle(I18n.t("conn.group.title"));
            dialog.setHeaderText(I18n.t("conn.group.header", profile.name()));
            ComboBox<String> input = new ComboBox<>();
            input.setId("connection-group-name");
            input.setEditable(true);
            input.getItems().setAll(existingGroups());
            input.setValue(profile.group());
            input.setPrefWidth(240);
            Label hint = new Label(I18n.t("conn.group.hint"));
            hint.getStyleClass().add("adb-muted");
            VBox content = new VBox(8, new Label(I18n.t("conn.col.group")), input, hint);
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().getButtonTypes().setAll(
                    javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            dialog.setResultConverter(button -> button == javafx.scene.control.ButtonType.OK
                    ? (input.getEditor().getText() == null ? "" : input.getEditor().getText().trim())
                    : null);
            dialog.initOwner(getScene() == null ? null : getScene().getWindow());
            DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
            dialog.showAndWait().ifPresent(this::groupSelectedAs);
        });
    }

    /** 已有分组（去重排序）—— 给「分组」下拉做候选。 */
    private List<String> existingGroups() {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        profiles.stream()
                .map(ConnectionProfile::group)
                .filter(value -> !value.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(groups::add);
        return List.copyOf(groups);
    }

    private void delete(ConnectionProfile profile) {
        if (!deleteConfirmation.test(profile)) {
            return;
        }
        onDelete.accept(profile);
        refresh();
    }

    private boolean confirmDelete(ConnectionProfile profile) {
        // ⛔ 同 AppShell：破坏性按钮不能是默认按钮（回车即删），统一走 DestructiveConfirm。
        DestructiveConfirm confirm = DestructiveConfirm.of(
                I18n.t("conn.delete.title"),
                I18n.t("conn.delete.header", profile.name()),
                I18n.t("conn.delete.note"),
                I18n.t("common.delete"));
        Alert alert = confirm.alert();
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        DialogStyles.apply(alert, getScene() == null ? null : getScene().getWindow());
        return confirm.confirmed();
    }

    public void focusSearch() {
        search.requestFocus();
    }

    @Override
    public void close() {
    }
}
