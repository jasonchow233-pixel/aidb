package io.agenticdb.ui.shell;

import io.agenticdb.objectstore.ObjectEntry;
import io.agenticdb.objectstore.ObjectMeta;
import io.agenticdb.objectstore.ObjectPage;
import io.agenticdb.objectstore.ObjectStoreCapabilities;
import io.agenticdb.objectstore.ObjectStoreDanger;
import io.agenticdb.objectstore.ObjectStoreSession;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p><b>渐进加载</b>：列举一律走 {@code ObjectStoreSession.listObjectsPage}，
 * 首屏只取 {@code PAGE_SIZE}（200）行；还有下一页时列表下方才出现「加载更多」，
 * 逐页按 key 去重累加。旧的全量 {@code listObjects} 留给递归删前缀与后端契约，
 * 不再用来喂这张表（真 OSS 账号实测桶内 &gt;12000 对象）。
 *
 * <p>按 {@code (连接, bucket)} 一个实例并缓存（{@link WorkspaceView} 按 tab id 存），
 * 切标签不重连、不丢当前前缀；{@link #close()} 时断开会话。
 */
final class ObjectBrowserPage extends VBox {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    /**
     * 首屏 / 每次「加载更多」向服务端要的条数，与后端 {@code PageCursor.DEFAULT_PAGE_SIZE}
     * 同值。真 OSS 账号的桶实测 &gt;12000 对象，一次列完整层会把整桶拉回内存再交给表格。
     */
    private static final int PAGE_SIZE = 200;

    private static final Set<String> IMAGE_EXT = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tif", "tiff", "ico", "avif", "heic");
    private static final Set<String> TEXT_EXT = Set.of(
            "txt", "md", "log", "pdf", "doc", "docx", "rtf", "readme");
    private static final Set<String> CODE_EXT = Set.of(
            "json", "xml", "yaml", "yml", "sql", "csv", "tsv", "html", "css", "js", "ts",
            "java", "kt", "py", "go", "rs", "sh", "properties", "toml", "gradle", "ini");

    /**
     * 列表行首字形。<b>六档都有图标</b> —— 文件夹、已知类型和「认不出类型」都不许留空，
     * 否则密集列表里那一行看着像数据缺了一格。颜色只上在图标上，文字保持正文色。
     */
    enum ObjectVisual {
        FOLDER(Icons.Icon.FOLDER, "adb-objects__icon--folder", "objectstore.type.folder"),
        IMAGE(Icons.Icon.FILE_IMAGE, "adb-objects__icon--image", "objectstore.type.image"),
        TEXT(Icons.Icon.FILE_TEXT, "adb-objects__icon--text", "objectstore.type.text"),
        CODE(Icons.Icon.FILE_CODE, "adb-objects__icon--code", "objectstore.type.code"),
        UNKNOWN(Icons.Icon.FILE_UNKNOWN, "adb-objects__icon--unknown", "objectstore.type.unknown"),
        FILE(Icons.Icon.FILE, "adb-objects__icon--file", "objectstore.type.file");

        final Icons.Icon icon;
        final String styleClass;
        final String labelKey;

        ObjectVisual(Icons.Icon icon, String styleClass, String labelKey) {
            this.icon = icon;
            this.styleClass = styleClass;
            this.labelKey = labelKey;
        }
    }

    /**
     * 只按 key 的扩展名分类：{@link ObjectEntry} 里没有 MIME，列举结果也不该为了画图标
     * 再去 stat 一次。无扩展名 / 点结尾 / 以点开头没有后缀的 key 一律落 UNKNOWN，
     * 用问号文件图标明确告诉用户「这个类型没认出来」，而不是悄悄画成通用文件。
     */
    static ObjectVisual visualFor(ObjectEntry entry) {
        if (entry == null || entry.folder()) {
            return ObjectVisual.FOLDER;
        }
        String key = entry.key() == null ? "" : entry.key();
        String name = key.substring(key.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return ObjectVisual.UNKNOWN;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (IMAGE_EXT.contains(ext)) {
            return ObjectVisual.IMAGE;
        }
        if (TEXT_EXT.contains(ext)) {
            return ObjectVisual.TEXT;
        }
        if (CODE_EXT.contains(ext)) {
            return ObjectVisual.CODE;
        }
        return ObjectVisual.FILE;
    }

    private final ConnectionProfile profile;
    private final String bucket;
    private final Supplier<ObjectStoreSession> sessionFactory;

    private final TableView<ObjectEntry> table = new TableView<>();
    private final TextField prefixField = new TextField();
    private final Label statusLabel = new Label();
    private final Label breadcrumb = new Label();
    private final VBox details = new VBox(4);
    /** 页脚：只有确实还有下一页时才出现，小 bucket 不摆一个点了没反应的假按钮。 */
    private final HBox moreBar = new HBox();

    /** 已加载的行（按 key 去重：服务端 delimiter 折叠可能跨页回同一个文件夹）。 */
    private final Map<String, ObjectEntry> pageRows = new LinkedHashMap<>();

    private volatile ObjectStoreSession session;
    private volatile long listGeneration;
    private volatile boolean loadingMore;
    private boolean activated;
    private String prefix = "";
    private String nextCursor;
    private ObjectStoreCapabilities capabilities;

    /** 移动/重命名按钮：能力不支持时置灰（测试据此验证诚实性）。 */
    private javafx.scene.control.Button moveButton;
    private javafx.scene.control.Button moreButton;

    ObjectBrowserPage(TabItem tab, ConnectionProfile profile,
                      Supplier<ObjectStoreSession> sessionFactory) {
        this.profile = profile;
        this.bucket = tab.target() == null ? "" : tab.target();
        this.sessionFactory = sessionFactory;

        getStyleClass().add("adb-page");
        setSpacing(0);

        VBox header = buildHeader();
        header.getStyleClass().addAll("adb-page__header", "adb-objects__header");

        details.getStyleClass().add("adb-details");
        details.setVisible(false);
        details.setManaged(false);
        details.setMinWidth(300);
        details.setPrefWidth(340);
        HBox body = new HBox(table, details);
        body.getStyleClass().add("adb-page__body");
        HBox.setHgrow(table, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, new Separator(), body, buildMoreBar());
        configureTable();
        setDensity("standard");
        statusLabel.setText(I18n.t("objectstore.loading"));
    }

    /**
     * ⭐ 两行页头：第一行是「这是什么 / 到哪一层 / 当前状态」，第二行才放动作。
     *
     * <p>以前 11 颗带文字的图标按钮 + 标题 + 面包屑 + 过滤框 + 状态全挤在一行，
     * 1024px 的窗口里 HBox 会把按钮按最小宽压扁 —— 实拍 p10-object-store-real
     * 里 "Download" 只剩 "Do…"、"Temporary URL" 只剩 "Temporar…"，动作名等于丢失。
     * 现在：① 两行分担宽度；② 动作按钮一律 {@code minWidth = USE_PREF_SIZE}
     * （文字不许被压成省略号）；③ 整条工具带套一层横向滚动，窄窗口宁可滚，
     * 也不截断。短标签只管「一眼认出」，完整动作名留在 tooltip / accessibleText。
     */
    private VBox buildHeader() {
        VBox header = new VBox(4);

        HBox titleLine = new HBox(8);
        titleLine.setAlignment(Pos.CENTER_LEFT);
        titleLine.getStyleClass().add("adb-objects__line");

        Label title = new Label(I18n.t("objectstore.header", profile.name(), bucket));
        title.getStyleClass().add("adb-page__title");
        title.setMinWidth(Region.USE_PREF_SIZE);

        breadcrumb.getStyleClass().add("adb-muted");
        breadcrumb.setText("/");
        breadcrumb.setMinWidth(Region.USE_PREF_SIZE);

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        statusLabel.getStyleClass().add("adb-muted");
        statusLabel.setMinWidth(Region.USE_PREF_SIZE);
        titleLine.getChildren().addAll(title, breadcrumb, titleSpacer, statusLabel);

        HBox toolbar = new HBox(6);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("adb-objects__toolbar");
        // 工具带自己肯认输：最小宽归零，父容器才不会把超宽的一排居中裁掉。
        toolbar.setMinWidth(0);

        prefixField.setPromptText(I18n.t("objectstore.prefix"));
        prefixField.setPrefWidth(160);
        prefixField.setMinWidth(120);
        prefixField.setOnAction(event -> {
            prefix = prefixField.getText() == null ? "" : prefixField.getText();
            load();
        });

        var up = iconAction(Icons.Icon.PREVIOUS, I18n.t("objectstore.up"));
        up.setOnAction(event -> upOneLevel());

        var refresh = iconAction(Icons.Icon.REFRESH, I18n.t("objectstore.refresh"));
        refresh.setOnAction(event -> load());

        HBox filter = new HBox(4, up, prefixField, refresh);
        filter.getStyleClass().add("adb-toolbar__group");
        filter.setAlignment(Pos.CENTER_LEFT);
        filter.setMinWidth(Region.USE_PREF_SIZE);

        var upload = action(Icons.Icon.IMPORT,
                I18n.t("objectstore.upload"), I18n.t("objectstore.upload"));
        upload.setOnAction(event -> uploadFile());

        var download = action(Icons.Icon.EXPORT,
                I18n.t("objectstore.download"), I18n.t("objectstore.download"));
        download.setOnAction(event -> downloadSelected());

        var presign = action(Icons.Icon.OPEN,
                I18n.t("objectstore.action.presign"), I18n.t("objectstore.presign"));
        presign.setOnAction(event -> copyPresignedUrl());

        var metadata = action(Icons.Icon.LIST,
                I18n.t("objectstore.action.metadata"), I18n.t("objectstore.metadata"));
        metadata.setOnAction(event -> {
            ObjectEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.folder()) {
                showMetadata(selected.key());
            }
        });

        // ⭐ 能力差异的可视落点：七牛无服务端 move，按钮置灰 + tooltip 说明。
        moveButton = action(Icons.Icon.EDIT,
                I18n.t("objectstore.action.move"), I18n.t("objectstore.move"));
        moveButton.setOnAction(event -> moveSelected());

        var deleteOne = action(Icons.Icon.DELETE,
                I18n.t("objectstore.delete"), I18n.t("objectstore.delete"), true);
        deleteOne.setOnAction(event -> deleteSelected());

        var deletePrefix = action(Icons.Icon.DELETE,
                I18n.t("objectstore.action.deletePrefix"),
                I18n.t("objectstore.tip.deletePrefix"), true);
        deletePrefix.setOnAction(event -> deleteCurrentPrefix());

        HBox actions = new HBox(4, upload, download, presign, metadata, moveButton,
                deleteOne, deletePrefix);
        actions.getStyleClass().add("adb-toolbar__group");
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setMinWidth(Region.USE_PREF_SIZE);

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        toolbar.getChildren().addAll(filter, actionSpacer, actions);

        ScrollPane scroll = new ScrollPane(toolbar);
        scroll.getStyleClass().add("adb-objects__scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFitToHeight(true);
        scroll.setFitToWidth(false);
        scroll.setMinHeight(32);
        scroll.setPrefHeight(32);
        scroll.setMaxHeight(32);

        header.getChildren().addAll(titleLine, scroll);
        return header;
    }

    /** 图标 + 短文字的紧凑动作键：文字不许压缩，完整动作名进 tooltip。 */
    private Button action(Icons.Icon icon, String label, String hint) {
        return action(icon, label, hint, false);
    }

    /**
     * ⚠️ 变体类（--danger）必须和基础类在<b>同一次</b> {@code addAll} 里挂上：
     * `UnifiedControlTest.everyVariantCarriesItsBaseClass` 按调用逐条查，
     * 单独再 add 一次会被判成「拿不到统一尺寸与五态」。
     */
    private Button action(Icons.Icon icon, String label, String hint, boolean danger) {
        Button button = IconButtons.of(icon, label);
        if (danger) {
            button.getStyleClass().addAll("adb-button", "adb-objects__action",
                    "adb-button--danger");
        } else {
            button.getStyleClass().addAll("adb-button", "adb-objects__action");
        }
        button.setGraphicTextGap(4);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip(hint));
        button.setAccessibleText(hint);
        return button;
    }

    /** 只读导航键（返回上一级 / 刷新）：纯图标，提示与无障碍读数不缺。 */
    private Button iconAction(Icons.Icon icon, String hint) {
        Button button = IconButtons.icon(icon, hint);
        // 纯图标键的宽度下限由 .adb-button--icon 的 30px 守住，不需要再钉 minWidth。
        button.getStyleClass().addAll("adb-button", "adb-button--icon", "adb-objects__action");
        return button;
    }

    private void configureTable() {
        // ⭐ 专用样式类：没有它这张表吃的是 Modena 默认（13px 正文 + 大内边距），
        //    而侧栏对象树是 12px / 21px 行 —— 同一屏里两套密度。
        table.getStyleClass().add("adb-objects__table");
        TableColumn<ObjectEntry, String> keyCol = new TableColumn<>(I18n.t("objectstore.column.key"));
        // ⭐ 行首字形取代了以前的 "Folder  " 文字前缀：一屏里同样的信息用图标更省宽度，
        //    文件夹与「未见类型」也终于有明确的图形，不再靠读文字猜这一行是什么。
        keyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().key()));
        keyCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String key, boolean empty) {
                super.updateItem(key, empty);
                ObjectEntry entry = empty || getTableRow() == null
                        ? null : (ObjectEntry) getTableRow().getItem();
                if (key == null || entry == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    setAccessibleText(null);
                    return;
                }
                ObjectVisual visual = visualFor(entry);
                setText(key);
                SVGPath glyph = Icons.of(visual.icon, Icons.SIZE_LABEL);
                glyph.getStyleClass().addAll("adb-objects__icon", visual.styleClass);
                setGraphic(glyph);
                setGraphicTextGap(6);
                String hint = I18n.t(visual.labelKey);
                setTooltip(new Tooltip(hint));
                setAccessibleText(hint);
            }
        });
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
        // ⭐ 列宽按 pref 比例铺满表格：UNCONSTRAINED 会把剩下的一两百像素留成
        //    一条没有列头的空白带，实拍里右侧看着像「列表没画完」。
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
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

    /**
     * 页脚「加载更多」。默认隐藏 —— 没有更多数据时留一颗灰按钮等于摆假控件。
     * 它也不进那条已经很挤的工具带，避免把上一轮刚修好的短标签再压成省略号。
     */
    private HBox buildMoreBar() {
        // ⚠️ 不挂 .adb-objects__action：那个类是「工具带动作键」的专属标记，
        //    ObjectBrowserDensityTest 按它数 9 颗并逐颗量宽度。页脚这颗属于另一个区域，
        //    复用同一套紧凑尺寸（见 app.css 的 .adb-objects__more-button），但不混进工具带。
        String hint = I18n.t("objectstore.loadMore");
        moreButton = IconButtons.of(Icons.Icon.MORE, I18n.t("objectstore.action.loadMore"));
        moreButton.getStyleClass().addAll("adb-button", "adb-objects__more-button");
        moreButton.setGraphicTextGap(4);
        moreButton.setMinWidth(Region.USE_PREF_SIZE);
        moreButton.setTooltip(new Tooltip(hint));
        moreButton.setAccessibleText(hint);
        moreButton.setOnAction(event -> loadMore());
        moreBar.getChildren().add(moreButton);
        moreBar.setAlignment(Pos.CENTER_LEFT);
        moreBar.getStyleClass().add("adb-objects__more");
        moreBar.setVisible(false);
        moreBar.setManaged(false);
        return moreBar;
    }

    /**
     * ⭐ 首屏只取一页。所有列举都经 {@link ObjectStoreSession#listObjectsPage}，
     * <b>不再</b>调一次列完整层的 {@code listObjects} 再交给表格切片 ——
     * 真 OSS 账号的桶 &gt;12000 对象，那条老路会把整桶拉回来。
     *
     * <p>{@code listGeneration} 负责丢弃过期结果：用户改前缀 / 进下一层时上一页
     * 可能还在后台飞，回来后不许把旧层的行盖到新层上。
     */
    private void load() {
        final long gen = ++listGeneration;
        final String p = prefix;
        nextCursor = null;
        loadingMore = false;
        moreBar.setVisible(false);
        moreBar.setManaged(false);
        statusLabel.setText(I18n.t("objectstore.loading"));
        breadcrumb.setText("/" + p);
        new Thread(() -> {
            try {
                ObjectStoreSession s = ensureSession();
                ObjectPage page = s.listObjectsPage(bucket, p, "/", null, PAGE_SIZE);
                ObjectStoreCapabilities caps = s.capabilities();
                Platform.runLater(() -> {
                    if (gen != listGeneration) {
                        return;
                    }
                    pageRows.clear();
                    appendPage(page);
                    nextCursor = page.nextToken();
                    capabilities = caps;
                    applyCapabilities();
                    updateMoreBar();
                    statusLabel.setText(statusFor(pageRows.size(), page.hasMore()));
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> {
                    if (gen != listGeneration) {
                        return;
                    }
                    statusLabel.setText(I18n.t("objectstore.error", msg));
                    moreBar.setVisible(false);
                    moreBar.setManaged(false);
                });
            }
        }).start();
    }

    /** 追加下一页；同一 key 只留一行（文件夹折叠可能跨页重复）。 */
    private void loadMore() {
        if (loadingMore || nextCursor == null || nextCursor.isBlank()) {
            return;
        }
        loadingMore = true;
        final long gen = listGeneration;
        final String cursor = nextCursor;
        final String p = prefix;
        moreButton.setDisable(true);
        statusLabel.setText(I18n.t("objectstore.loading"));
        new Thread(() -> {
            try {
                ObjectPage page = ensureSession()
                        .listObjectsPage(bucket, p, "/", cursor, PAGE_SIZE);
                Platform.runLater(() -> {
                    loadingMore = false;
                    if (gen != listGeneration) {
                        return; // 期间换了前缀 / 层级，这一页作废
                    }
                    appendPage(page);
                    nextCursor = page.nextToken();
                    updateMoreBar();
                    statusLabel.setText(statusFor(pageRows.size(), page.hasMore()));
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Platform.runLater(() -> {
                    loadingMore = false;
                    if (gen != listGeneration) {
                        return;
                    }
                    statusLabel.setText(I18n.t("objectstore.error", msg));
                    updateMoreBar();
                });
            }
        }).start();
    }

    private void appendPage(ObjectPage page) {
        for (ObjectEntry entry : page.entries()) {
            pageRows.putIfAbsent(entry.key(), entry);
        }
        table.getItems().setAll(pageRows.values());
    }

    private void updateMoreBar() {
        boolean more = nextCursor != null && !nextCursor.isBlank();
        moreBar.setVisible(more);
        moreBar.setManaged(more);
        moreButton.setDisable(loadingMore);
    }

    private String statusFor(int count, boolean more) {
        return more ? I18n.tPlural("objectstore.count.more", count, count)
                : I18n.tPlural("objectstore.count", count, count);
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

    /** 门禁用：分页页脚 / 加载更多键 / 列表 / 状态栏 / 前缀框。 */
    HBox moreBarForTest() {
        return moreBar;
    }

    javafx.scene.control.Button moreButtonForTest() {
        return moreButton;
    }

    TableView<ObjectEntry> tableForTest() {
        return table;
    }

    Label statusLabelForTest() {
        return statusLabel;
    }

    TextField prefixFieldForTest() {
        return prefixField;
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

    /**
     * 会话按需建立一次（后台线程调用）。{@code synchronized} 是为了切前缀时
     * 两次加载并发进来也只建一个会话 —— 分页放宽了加载并发，旧的单线程假设不再成立。
     */
    private synchronized ObjectStoreSession ensureSession() {
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

    /**
     * 密度档位：直接改行高（与 RedisKeysPage 同口径）。
     *
     * <p>⭐ 默认 32 → 24：侧栏对象树在 normal 档是 21px、正文 12px，
     * 对象表以前是 32px 行高 + 13px 字，实拍里 3 条数据铺了半屏空白。
     * 现在四档与树同量级（21 / 24 / 32），字号由 .adb-objects__table 钉成 12px。
     */
    void setDensity(String density) {
        double h = switch (density) {
            case "compact" -> 21;
            case "loose" -> 32;
            default -> 24;
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
