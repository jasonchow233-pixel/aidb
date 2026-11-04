package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.controlsfx.control.PopOver;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * ⭐ R57-a：查询页编辑器的补全控制器 —— 弹层 + 模型缓存 + 触发键。
 *
 * <p>候选的**规则**全在 {@link SqlCompletions}（纯逻辑），这里只管三件事：
 * ① 什么时候算一次（⌘Space 手动、打点自动、已开着时打字即筛）；
 * ② 模式数据从哪儿来（对象清单 + 表列，走 {@code dbExecutor} 后台读，
 *    缓存范式抄侧栏 S-3：执行过 DDL 就整份作废重读）；
 * ③ 弹层的形态（ControlsFX {@link PopOver}，与列头漏斗同一族；焦点留在编辑器，
 *    方向键 / Enter / Esc 由编辑器的按键过滤器接管）。
 *
 * <p>触发口径（如实）：只有 ⌘/Ctrl+Space 与「打完一个点」两处自动弹，
 * 普通打字**不**弹窗 —— 弹早了是碍事，已开着的时候才跟着筛。
 */
final class QueryCompletions implements SqlCompletions.Source, AutoCloseable {

    /** 执行文本里的 DDL 形状 —— 命中就把对象/列缓存整份作废（口径同侧栏 S-3）。 */
    private static final Pattern DDL = Pattern.compile(
            "(?i)\\b(CREATE|ALTER|DROP|TRUNCATE|RENAME)\\b");

    private final CodeArea area;
    private final DbSession session;
    private final Executor dbExecutor;
    private final SchemaService schemaService = new SchemaService();

    private final PopOver popover = new PopOver();
    private final ListView<SqlCompletions.Candidate> list = new ListView<>();
    private final Label loading = new Label(I18n.t("completion.loading"));
    private final VBox root = new VBox(list, loading);

    // ── 模型状态（全部只在 FX 线程读写；后台线程只负责查，结果 runLater 灌回） ──
    private List<SqlCompletions.Entry> objects = List.of();
    private final Map<String, List<String>> columnsByTable = new ConcurrentHashMap<>();
    private final Set<String> columnsLoading = ConcurrentHashMap.newKeySet();
    private boolean objectsReady;
    private boolean objectsLoading;
    private long modelGeneration;

    // ── 弹层状态 ──
    private volatile SqlCompletions.Proposal proposal;
    private volatile boolean showing;
    private boolean pendingAuto;
    private boolean textChanging;

    QueryCompletions(CodeArea area, DbSession session, Executor dbExecutor) {
        this.area = area;
        this.session = session;
        this.dbExecutor = dbExecutor;

        list.getStyleClass().add("adb-completion__list");
        list.setFocusTraversable(false);
        list.setPrefWidth(340);
        list.setMaxHeight(240);
        list.setCellFactory(view -> new CandidateCell());
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                acceptSelected();
            }
        });
        loading.getStyleClass().add("adb-completion__loading");
        loading.setVisible(false);
        loading.setManaged(false);
        root.getStyleClass().add("adb-completion");
        root.setPadding(new Insets(2));

        popover.setContentNode(root);
        popover.setHeaderAlwaysVisible(false);
        popover.setCloseButtonEnabled(false);
        popover.setAnimated(false);
        popover.setAutoHide(true);
        popover.setHideOnEscape(false);
        popover.setOnHidden(event -> showing = false);
    }

    /** 挂到编辑器上：由 QueryPage 在构建界面之后调一次。 */
    void install() {
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        area.textProperty().addListener((observable, oldText, newText) ->
                onTextChanged(oldText, newText));
        area.caretPositionProperty().addListener((observable, oldPosition, newPosition) -> {
            if (showing && !textChanging) {
                hide();
            }
        });
    }

    // ─────────────────────────── 触发 ───────────────────────────

    private void onKeyPressed(KeyEvent event) {
        if (showing) {
            switch (event.getCode()) {
                case UP -> {
                    moveSelection(-1);
                    event.consume();
                    return;
                }
                case DOWN -> {
                    moveSelection(1);
                    event.consume();
                    return;
                }
                case ENTER, TAB -> {
                    acceptSelected();
                    event.consume();
                    return;
                }
                case ESCAPE -> {
                    hide();
                    event.consume();
                    return;
                }
                default -> {
                    // 其它键照常落到编辑器：文本变化会带出「跟着筛」。
                }
            }
        }
        if (event.getCode() == KeyCode.SPACE
                && (event.isShortcutDown() || event.isControlDown())) {
            evaluate(true);
            event.consume();
        }
    }

    private void onTextChanged(String oldText, String newText) {
        textChanging = true;
        boolean dotTyped = isSingleCharInsert(oldText, newText, '.');
        boolean wasShowing = showing;
        Platform.runLater(() -> {
            textChanging = false;
            // runLater 时才求值：此刻文本已落定、光标也已挪到插入点之后。
            if (wasShowing || dotTyped) {
                evaluate(false);
            }
        });
    }

    /** 文本里只多了一个指定字符（插在任意位置）—— 「刚打出来一个点」的自动触发就认这个。 */
    private static boolean isSingleCharInsert(String oldText, String newText, char c) {
        if (oldText == null || newText == null || newText.length() != oldText.length() + 1) {
            return false;
        }
        int i = 0;
        while (i < oldText.length() && oldText.charAt(i) == newText.charAt(i)) {
            i++;
        }
        if (newText.charAt(i) != c) {
            return false;
        }
        return newText.regionMatches(i + 1, oldText, i, oldText.length() - i);
    }

    // ─────────────────────────── 求值与展示 ───────────────────────────

    private void evaluate(boolean force) {
        proposal = SqlCompletions.suggest(area.getText(), area.getCaretPosition(), this);
        ensureObjects();
        for (String table : proposal.missingTables()) {
            ensureColumns(table);
        }
        boolean hasModelWork = objectsLoading || !proposal.modelReady();
        if (!proposal.candidates().isEmpty()) {
            pendingAuto = false;
            if (!showing) {
                showPopup();
            }
            list.setItems(FXCollections.observableArrayList(proposal.candidates()));
            list.getSelectionModel().selectFirst();
            setLoadingRow(!proposal.modelReady());
        } else if (hasModelWork) {
            // 数据还在路上：手动按出来的话先给一行「正在读」，读回来会自动再算一次。
            pendingAuto = true;
            if (force && !showing) {
                showPopup();
                list.setItems(FXCollections.observableArrayList());
                setLoadingRow(true);
            }
        } else {
            pendingAuto = false;
            hide();
        }
    }

    private void showPopup() {
        double[] anchor = caretAnchor();
        popover.show(area, anchor[0], anchor[1]);
        showing = true;
        // 焦点必须留在编辑器：弹层只是展示，按键由编辑器的过滤器接管。
        area.requestFocus();
    }

    /**
     * 光标处：0.11.7 的 {@code PopupControl.show(ownerNode, x, y)} 要的是**屏幕**坐标
     * （ControlsFX 自己的 {@code show(target, offset)} 也是先 {@code localToScreen}
     * 再传进去 —— 反编译 11.2.6 的 {@code PopOver} 看过）。
     *
     * <p>此前这里翻成了编辑器局部坐标再传出去，于是弹层落在屏幕左上角而不是光标下面：
     * 实测光标在屏幕 (328, 102)，弹层的窗却开在 (132, -2)。行为门禁只看候选内容，
     * 量不到坐标，所以这个错位从 R57-a 交付起一直没被发现 —— 是补画面债时拍出来的。
     *
     * <p>拿不到光标包围盒（还没布局 / 光标在视口外）就贴编辑器左上角，不猜第二套坐标。
     */
    private double[] caretAnchor() {
        java.util.Optional<javafx.geometry.Bounds> onScreen = area.getCaretBoundsOnScreen(
                area.getCaretSelectionBind().getUnderlyingCaret());
        if (onScreen.isPresent()) {
            javafx.geometry.Bounds caret = onScreen.get();
            return new double[]{caret.getMinX(), caret.getMaxY() + 2};
        }
        javafx.geometry.Point2D corner = area.localToScreen(8, 24);
        if (corner != null) {
            return new double[]{corner.getX(), corner.getY()};
        }
        return new double[]{8, 24};
    }

    private void hide() {
        pendingAuto = false;
        if (showing) {
            popover.hide();
        }
        showing = false;
    }

    private void moveSelection(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int current = list.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(size - 1, current + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void acceptSelected() {
        SqlCompletions.Candidate candidate = list.getSelectionModel().getSelectedItem();
        if (candidate == null || proposal == null) {
            hide();
            return;
        }
        int start = proposal.replaceStart();
        hide();
        area.replaceText(start, proposal.replaceEnd(), candidate.insertText());
        area.moveTo(start + candidate.insertText().length());
        area.requestFollowCaret();
        area.requestFocus();
    }

    private void setLoadingRow(boolean visible) {
        loading.setVisible(visible);
        loading.setManaged(visible);
    }

    // ─────────────────────────── 模型读取（S-3 范式） ───────────────────────────

    private void ensureObjects() {
        if (objectsReady || objectsLoading) {
            return;
        }
        objectsLoading = true;
        long generation = modelGeneration;
        dbExecutor.execute(() -> {
            List<SqlCompletions.Entry> loaded;
            try {
                loaded = readObjects();
            } catch (Exception e) {
                loaded = null;
            }
            List<SqlCompletions.Entry> result = loaded;
            Platform.runLater(() -> {
                objectsLoading = false;
                if (result == null || generation != modelGeneration) {
                    if (result == null && showing) {
                        hide();
                    }
                    return;
                }
                objects = result;
                objectsReady = true;
                if (showing || pendingAuto) {
                    evaluate(false);
                }
            });
        });
    }

    private List<SqlCompletions.Entry> readObjects() throws java.sql.SQLException {
        List<SqlCompletions.Entry> entries = new ArrayList<>();
        for (SchemaService.SchemaObject object : schemaService.listObjects(session)) {
            SqlCompletions.Kind kind = switch (object.kind()) {
                case TABLE -> SqlCompletions.Kind.TABLE;
                case VIEW -> SqlCompletions.Kind.VIEW;
                case PROCEDURE, FUNCTION -> SqlCompletions.Kind.ROUTINE;
                // 触发器与事件不进补全：它们不是能在 FROM / 列位置写出来的东西。
                case TRIGGER, EVENT -> null;
            };
            if (kind != null) {
                entries.add(new SqlCompletions.Entry(object.name(), kind));
            }
        }
        return List.copyOf(entries);
    }

    private void ensureColumns(String table) {
        String key = table.toLowerCase(Locale.ROOT);
        if (columnsByTable.containsKey(key) || !columnsLoading.add(key)) {
            return;
        }
        long generation = modelGeneration;
        dbExecutor.execute(() -> {
            List<String> columns;
            try {
                columns = schemaService.describe(session, table).columns().stream()
                        .map(io.agenticdb.model.ColumnDef::name)
                        .toList();
            } catch (Exception e) {
                columns = null;
            }
            List<String> result = columns;
            Platform.runLater(() -> {
                columnsLoading.remove(key);
                if (result == null || generation != modelGeneration) {
                    return;
                }
                columnsByTable.put(key, result);
                if (showing || pendingAuto) {
                    evaluate(false);
                }
            });
        });
    }

    /** 查询页在**成功执行**过语句后调这里：执行过 DDL 就把对象与列缓存整份作废。 */
    void onStatementsExecuted(List<String> executedTexts) {
        for (String text : executedTexts) {
            if (DDL.matcher(text).find()) {
                markStale();
                return;
            }
        }
    }

    private void markStale() {
        modelGeneration++;
        objectsReady = false;
        columnsByTable.clear();
        columnsLoading.clear();
    }

    // ─────────────────────────── Source 出口 ───────────────────────────

    @Override
    public List<SqlCompletions.Entry> objects() {
        return objects;
    }

    @Override
    public boolean objectsReady() {
        return objectsReady;
    }

    @Override
    public List<String> columnsOf(String table) {
        return columnsByTable.getOrDefault(table.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public boolean columnsLoaded(String table) {
        return columnsByTable.containsKey(table.toLowerCase(Locale.ROOT));
    }

    @Override
    public Set<String> keywords() {
        return io.agenticdb.sql.SqlLexer.keywords();
    }

    @Override
    public String quote(String identifier) {
        return session.dialect().quote(identifier);
    }

    // ─────────────────────────── 门禁接缝 ───────────────────────────

    /**
     * 摆给快门：候选算出来了才算「这一格有东西可拍」。
     *
     * <p>对象清单是后台读的，读回来之前 {@code proposal} 里只有关键字 ——
     * 拿那张图当「补全跟着库上下文走」的证据是假的，所以宁可不拍。
     */
    boolean isPoseableForShots() {
        return objectsReady && proposal != null && !proposal.candidates().isEmpty();
    }

    /** 强制弹一次，并把浮层登记给截图会话（弹层在 PopupWindow 里，主场景快照拍不到）。 */
    void posePopupForShots() {
        if (!isHostWindowUp()) {
            // 宿主窗口已经不显示（关页 / 换测试类都会）：PopupWindow.show 会直接抛
            // 「owner node needs to be associated with a window」，而它落在 FX 动画线程上，
            // 会端给下一条测试 —— 摆态的计时器必须自己收口。
            hide();
            return;
        }
        evaluate(true);
        ScreenshotSession.setOverlayProvider(this::overlayInMainScene);
    }

    /** 编辑器还挂在一扇**显示中**的窗口上吗（节点有 scene 不等于那扇窗还在）。 */
    boolean isHostWindowUp() {
        javafx.scene.Scene scene = area.getScene();
        return scene != null && scene.getWindow() != null && scene.getWindow().isShowing();
    }

    /** 快门时刻现算：弹层那颗节点 + 它在主场景里的左上角。 */
    private ScreenshotSession.Overlay overlayInMainScene() {
        if (!showing || area.getScene() == null || area.getScene().getRoot() == null) {
            return null;
        }
        javafx.scene.control.Skin<?> skin = popover.getSkin();
        javafx.scene.Node chrome = skin == null ? null : skin.getNode();
        if (chrome == null || chrome.getScene() == null) {
            return null;
        }
        // layoutBounds 而不是 boundsInLocal：弹层外面那一圈箭头/阴影是绘制期的变换，
        // 直接拿局部 (0,0) 换算会连阴影一起算进去 —— 合成出来的框会整体偏。
        // localToScreen 传 null 就表示「这颗节点还没挂到窗口上」，此时放弃合成，不猜第二套坐标。
        javafx.geometry.Bounds onScreen = chrome.localToScreen(chrome.getLayoutBounds());
        if (onScreen == null) {
            return null;
        }
        javafx.geometry.Point2D corner = area.getScene().getRoot()
                .screenToLocal(onScreen.getMinX(), onScreen.getMinY());
        return new ScreenshotSession.Overlay(chrome, corner.getX(), corner.getY());
    }

    boolean isShowingForTest() {
        return showing;
    }

    List<String> candidateNamesForTest() {
        if (proposal == null) {
            return List.of();
        }
        return proposal.candidates().stream().map(SqlCompletions.Candidate::name).toList();
    }

    /** 让门禁直接触发一次求值（与按键走同一个入口，不另造逻辑）。 */
    void evaluateForTest(boolean force) {
        evaluate(force);
    }

    void acceptSelectedForTest() {
        acceptSelected();
    }

    @Override
    public void close() {
        hide();
    }

    /** 一行候选：图形 + 名字 + 右侧小字（列属于哪张表；关键字没有小字）。 */
    private static final class CandidateCell extends ListCell<SqlCompletions.Candidate> {
        @Override
        protected void updateItem(SqlCompletions.Candidate item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            if (empty || item == null) {
                return;
            }
            HBox row = new HBox(8);
            row.getStyleClass().add("adb-completion__item");
            Icons.Icon glyph = switch (item.kind()) {
                case TABLE -> Icons.Icon.TABLE;
                case VIEW -> Icons.Icon.VIEW;
                case ROUTINE -> Icons.Icon.FUNCTION;
                case COLUMN -> Icons.Icon.COLUMNS;
                case KEYWORD -> null;
            };
            if (glyph != null) {
                row.getChildren().add(Icons.of(glyph, Icons.SIZE_LABEL));
            }
            Label name = new Label(item.name());
            name.getStyleClass().add("adb-completion__name");
            if (item.kind() == SqlCompletions.Kind.KEYWORD) {
                name.getStyleClass().add("adb-completion__keyword");
            }
            row.getChildren().add(name);
            if (item.detail() != null) {
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.getChildren().add(spacer);
                Label detail = new Label(item.detail());
                detail.getStyleClass().add("adb-completion__detail");
                row.getChildren().add(detail);
            }
            setGraphic(row);
        }
    }
}
