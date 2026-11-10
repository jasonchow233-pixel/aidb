package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ G4 的门禁：表单视图（单记录纵向编辑）。
 *
 * <p>这一轮要证的是<b>语义</b>，不是「面板在不在」：
 * <ol>
 *   <li>切换是整块换渲染 —— 网格 / 冻结区让位，表单与转置互斥；</li>
 *   <li>表单永远跟着「网格当前选中行」—— 记录导航翻行，表单跟着翻；</li>
 *   <li>表单里改值走的是<b>同一个编辑缓冲</b>（脏标记 / 撤销 / 保存路径自动成立），
 *       不许绕开缓冲直接改 map；</li>
 *   <li>解析失败 / NOT NULL 违反要打回原值并播报，不静默吞输入；</li>
 *   <li>只读表（视图）的字段全部禁用，但值仍要显示出来；</li>
 *   <li>NULL 显示为占位提示，不是字面上的 "null"。</li>
 * </ol>
 *
 * <p>数据是固定三行的小表：每行值都能逐个念出来，断言写硬值。
 */
@ExtendWith(ApplicationExtension.class)
class DataGridFormViewTest {

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;
    /** 状态栏最后一次播报 —— 断言的主要落点（超时时也是唯一线索）。 */
    private final AtomicReference<String> lastStatus = new AtomicReference<>("<无>");

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT NOT NULL,"
                + " note TEXT, amount INTEGER NOT NULL, active BOOLEAN NOT NULL DEFAULT 1)");
        session.execute("INSERT INTO people VALUES"
                + " (1, 'Alice', NULL, 10, 1),"
                + " (2, 'Bob', 'second', 20, 0),"
                + " (3, 'Cara', 'third', 30, 1)");
        session.execute("CREATE VIEW v_people AS SELECT id, name FROM people");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-form");
            thread.setDaemon(true);
            return thread;
        });
        page = new DataGridPage(session, new SchemaService(), "people", "test.db",
                recordingFeedback(), name -> { }, executor);
        stage.setScene(styled(new Scene(page, 1100, 700)));
        stage.show();
        // ⚠️ 等待挪到 @BeforeEach（测试线程）：@Start 跑在 FX 线程上，
        //    加载完成的回调也要回 FX 线程，在这儿 waitFor 等于自己等自己。
    }

    @BeforeEach
    void awaitLoaded() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                table().getItems().size() == 3);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            interact(page::close);
        }
        executor.shutdownNow();
        session.close();
    }

    // ─────────────────── ① 整块切换 ───────────────────

    @Test
    void turningTheFormOnSwapsTheGridForTheForm() throws Exception {
        selectRow(0);
        interact(() -> page.setFormView(true));

        ScrollPane form = formPane();
        assertThat(form.isVisible())
                .as("开表单视图后表单必须占位")
                .isTrue();
        assertThat(form.isManaged()).isTrue();
        assertThat(table().isVisible())
                .as("同一层是整块换渲染：网格必须让位，不是盖一层")
                .isFalse();
        assertThat(table().isManaged()).isFalse();
        assertThat(formPosition().getText())
                .as("表单头必须说清当前看的是第几条")
                .isEqualTo(I18n.t("grid.form.position", 1, 3));
        assertThat(field("name").getText()).isEqualTo("Alice");
        assertThat(field("id").isDisabled())
                .as("主键列与格子内联编辑同一口径：不许改")
                .isTrue();
        assertThat(field("name").isDisabled())
                .as("可编辑表的普通列必须能写")
                .isFalse();

        interact(() -> page.setFormView(false));
        assertThat(formPane().isVisible()).isFalse();
        assertThat(table().isVisible())
                .as("关掉表单，网格必须原样回来")
                .isTrue();
        assertThat(page.dirtyCount())
                .as("来回切视图不许伪造脏数据")
                .isZero();
    }

    @Test
    void formAndTransposeStayMutuallyExclusive() throws Exception {
        selectRow(0);
        interact(() -> page.setTranspose(true));
        interact(() -> page.setFormView(true));
        assertThat(formPane().isVisible())
                .as("后开的表单赢：它必须占位")
                .isTrue();
        assertThat(transposeTable().isVisible())
                .as("先前开着的转置必须退场 —— 两个整块视图同时开着没法说清值落在哪")
                .isFalse();

        // 反向：表单开着时开转置，表单退场。
        interact(() -> page.setTranspose(true));
        assertThat(formPane().isVisible()).isFalse();
        assertThat(transposeTable().isVisible()).isTrue();
    }

    // ─────────────────── ② 记录导航联动 ───────────────────

    @Test
    void recordNavigationFlipsTheFormWithTheSelectedRow() throws Exception {
        selectRow(0);
        interact(() -> page.setFormView(true));

        click("#data-record-next");
        assertThat(formPosition().getText())
                .as("下一行：表单头与字段必须一起翻")
                .isEqualTo(I18n.t("grid.form.position", 2, 3));
        assertThat(field("name").getText()).isEqualTo("Bob");

        click("#data-record-last");
        assertThat(formPosition().getText()).isEqualTo(I18n.t("grid.form.position", 3, 3));
        assertThat(field("name").getText()).isEqualTo("Cara");

        click("#data-record-first");
        assertThat(formPosition().getText()).isEqualTo(I18n.t("grid.form.position", 1, 3));
        assertThat(field("name").getText()).isEqualTo("Alice");

        click("#data-record-prev");
        assertThat(formPosition().getText())
                .as("已到第一条，再往回不许跑到 0 / 3 或 -1")
                .isEqualTo(I18n.t("grid.form.position", 1, 3));
    }

    @Test
    void aNullValueReadsAsAPlaceholderInsteadOfTheWordNull() throws Exception {
        selectRow(0);
        interact(() -> page.setFormView(true));
        TextField note = field("note");
        assertThat(note.getText())
                .as("NULL 不许写成字面量 \"null\"")
                .isEmpty();
        assertThat(note.getPromptText())
                .as("空输入框要能看出这里是 NULL，而不是空串")
                .isEqualTo(io.agenticdb.ui.I18n.t("grid.form.nullPrompt"));
        assertThat(note.getPromptText())
                .as("占位符不能是 ⚠key（键没配到资源包里时界面会长出这个）")
                .doesNotStartWith("⚠");

        selectRow(1);
        assertThat(note.getText()).isEqualTo("second");
        assertThat(note.getPromptText())
                .as("有值的字段不许再挂 NULL 占位")
                .isEmpty();
    }

    // ─────────────────── ③ 走同一个编辑缓冲 ───────────────────

    @Test
    void editingAFormFieldGoesThroughTheEditBuffer() throws Exception {
        selectRow(0);
        interact(() -> page.setFormView(true));

        TextField name = field("name");
        interact(() -> {
            name.setText("Alicia");
            name.fireEvent(new ActionEvent());
        });

        assertThat(page.dirtyCount())
                .as("表单提交必须落到编辑缓冲（脏计数是缓冲唯一的对外口径）")
                .isEqualTo(1);
        assertThat(displayedCellText(0, "name"))
                .as("显示口径里已经是新值，但库里还没动")
                .isEqualTo("Alicia");
        Button saveButton = node("#data-save");
        assertThat(saveButton.isDisabled())
                .as("有改动，「保存」必须亮起来")
                .isFalse();

        // 撤销只认缓冲 —— 能撤回去就证明它确实走的是缓冲，不是直接改 map。
        interact(page::undo);
        assertThat(page.dirtyCount()).isZero();
        assertThat(displayedCellText(0, "name")).isEqualTo("Alice");
        assertThat(field("name").getText())
                .as("撤销后表单必须跟着回滚（读到的是缓冲合并后的值）")
                .isEqualTo("Alice");
    }

    @Test
    void theBooleanFieldCommitsThroughTheSameBuffer() throws Exception {
        selectRow(0);
        interact(() -> page.setFormView(true));
        CheckBox active = formCheck("active");
        assertThat(active.isSelected())
                .as("第一行 active = 1，表单复选框必须勾上")
                .isTrue();

        interact(() -> active.setSelected(false));
        assertThat(page.dirtyCount()).isEqualTo(1);
        interact(page::undo);
        assertThat(page.dirtyCount()).isZero();
        assertThat(formCheck("active").isSelected())
                .as("撤销后复选框必须回到库里那条的值")
                .isTrue();
    }

    @Test
    void invalidInputIsRejectedAndTheOldValueComesBack() throws Exception {
        selectRow(0);
        interact(() -> page.setFormView(true));
        TextField amount = field("amount");
        assertThat(amount.getText()).isEqualTo("10");

        interact(() -> {
            amount.setText("abc");
            amount.fireEvent(new ActionEvent());
        });
        assertThat(page.dirtyCount())
                .as("解析失败不许偷偷写进缓冲")
                .isZero();
        assertThat(lastStatus.get())
                .as("要给用户一句人话，而不是静默吞掉输入")
                .startsWith(I18n.t("grid.msg.invalidValue", "").trim());
        assertThat(field("amount").getText())
                .as("打回原值 —— 输入框不能停在一个永远不会被保存的字符串上")
                .isEqualTo("10");

        TextField again = field("amount");
        interact(() -> {
            again.setText("");
            again.fireEvent(new ActionEvent());
        });
        assertThat(page.dirtyCount()).isZero();
        assertThat(lastStatus.get())
                .as("NOT NULL 列清空要指名道姓地说不允许 NULL")
                .isEqualTo(I18n.t("grid.msg.invalidNotNull", "amount"));
        assertThat(field("amount").getText()).isEqualTo("10");
    }

    // ─────────────────── ④ 只读表 ───────────────────

    @Test
    @SuppressWarnings("unchecked")
    void readOnlyViewsShowValuesButDisableEveryField() throws Exception {
        AtomicReference<DataGridPage> pageRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        try {
            interact(() -> {
                DataGridPage view = new DataGridPage(session, new SchemaService(),
                        "v_people", "test.db", recordingFeedback(), name -> { }, executor);
                // ⚠️ 必须真的挂上舞台：ScrollPane 的皮肤在 CSS/布局时才建，
                //    没进场景的话 lookup 连字段子树都遍历不到（第一版就栽在这）。
                Stage stage = new Stage();
                stage.setScene(new Scene(view, 1000, 600));
                stage.show();
                pageRef.set(view);
                stageRef.set(stage);
            });
            DataGridPage view = pageRef.get();
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> tableHasRows(view, 3));

            interact(() -> {
                ((TableView<Map<String, Object>>) view.lookup("#data-table"))
                        .getSelectionModel().clearAndSelect(0);
                view.setFormView(true);
            });

            ScrollPane form = fxNode(view, "#data-form");
            assertThat(form.isVisible()).isTrue();
            TextField name = fxNode(view, "#data-form-name");
            assertThat(name).as("只读表也必须建出表单字段").isNotNull();
            assertThat(name.getText())
                    .as("只读不等于空白：值必须照常显示")
                    .isEqualTo("Alice");
            assertThat(name.isDisabled())
                    .as("视图没有主键 → 不可编辑 —— 字段必须禁用，而不是让人改完才发现保存不了")
                    .isTrue();
            Button addKey = fxNode(view, "#data-add");
            assertThat(addKey.isDisabled())
                    .as("同一口径：工具条的「新增」也是灰的")
                    .isTrue();
        } finally {
            if (pageRef.get() != null) {
                DataGridPage closing = pageRef.get();
                Stage stage = stageRef.get();
                interact(() -> {
                    closing.close();
                    if (stage != null) {
                        stage.close();
                    }
                });
            }
        }
    }

    // ─────────────────── ⑤ 工具条与表单同屏 ───────────────────

    /**
     * 表单视图 + 选中一行 = 工具条最宽的状态（多一颗「删除记录」）。
     *
     * <p>N18/N19 之后合法的降级只有一种：放不下的具名键从右往左折进 »，
     * <b>文字不许被压成省略号</b>，» 与每一颗可见键都必须留在窗内。
     * 旧实拍（{@code p2-form}）踩的是「整排收字 → 第二次重判又把文字涨回来 →
     * 最右的搜索框被裁成一个字」；收字逻辑已经换成折叠之后，
     * 这条门禁改为连「选中 → 重判 → 再选中」的时序一起钉住：
     * 折叠集合在两帧之间不许抖动，也不许出现半截文字。
     */
    @Test
    @SuppressWarnings("unchecked")
    void formViewWithSelectionKeepsTheToolbarInsideTheWindow() throws Exception {
        // 1154 − 240 侧栏 ≈ 910px 可用：12 个具名键必然有几颗要折进 »。
        final int width = 1154;
        AtomicReference<DataGridPage> pageRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        try {
            interact(() -> {
                DataGridPage view = new DataGridPage(session, new SchemaService(),
                        "people", "test.db", recordingFeedback(), name -> { }, executor);
                // ⚠️ 场景里要有 240px 侧栏：工具条只分到窗口剩下的那部分，
                //    这正是截图帧里「带文字放不下」的真实可用宽度。
                Region sidebar = new Region();
                sidebar.setMinWidth(240);
                sidebar.setPrefWidth(240);
                sidebar.setMaxWidth(240);
                HBox root = new HBox(sidebar, view);
                HBox.setHgrow(view, Priority.ALWAYS);
                view.setMaxWidth(Double.MAX_VALUE);
                Stage stage = new Stage();
                stage.setScene(styled(new Scene(root, width, 800)));
                stage.show();
                pageRef.set(view);
                stageRef.set(stage);
            });
            DataGridPage view = pageRef.get();
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> tableHasRows(view, 3));
            WaitForAsyncUtils.waitForFxEvents(4);

            TableView<Map<String, Object>> table = fxNode(view, "#data-table");
            Region toolbar = fxNode(view, "#data-toolbar");
            assertThat(toolbar).as("找不到 #data-toolbar").isNotNull();
            assertToolbarIsHonest(view, toolbar, width);
            assertThat(WaitForAsyncUtils.asyncFx(view::foldedToolbarKeysForTest).get())
                    .as("910px 可用宽度下 12 个具名键不可能全放得下（前置条件：这条门禁在验折叠）")
                    .isNotEmpty();

            // 第一次：选中行 + 表单视图 —— 删除记录出现在左下条里，顶部宽度不变。
            interact(() -> {
                table.getSelectionModel().select(0);
                view.setFormView(true);
            });
            WaitForAsyncUtils.waitForFxEvents(3);
            assertToolbarIsHonest(view, toolbar, width);
            List<String> foldedAfterFirst = ids(
                    WaitForAsyncUtils.asyncFx(view::foldedToolbarKeysForTest).get());

            // 第二次重判：选中另一行 —— 折叠集合不许抖。
            interact(() -> table.getSelectionModel().select(1));
            WaitForAsyncUtils.waitForFxEvents(3);
            assertThat(ids(WaitForAsyncUtils.asyncFx(view::foldedToolbarKeysForTest).get()))
                    .as("收拢后的第二次重判把折叠集合改了 —— 按钮会在两帧之间来回跳")
                    .isEqualTo(foldedAfterFirst);

            // 第三次：再选一行，仍是同一集合。
            interact(() -> table.getSelectionModel().select(2));
            WaitForAsyncUtils.waitForFxEvents(3);
            assertThat(ids(WaitForAsyncUtils.asyncFx(view::foldedToolbarKeysForTest).get()))
                    .as("第三次重判又改折叠集合")
                    .isEqualTo(foldedAfterFirst);
            assertToolbarIsHonest(view, toolbar, width);
        } finally {
            if (pageRef.get() != null) {
                DataGridPage closing = pageRef.get();
                Stage stage = stageRef.get();
                interact(() -> {
                    closing.close();
                    if (stage != null) {
                        stage.close();
                    }
                });
            }
        }
    }

    /**
     * 工具条的「诚实状态」：可见的具名键文字完整、被折进 » 的不占位、
     * » 在需要时出现，整排（含 »）不出窗。
     */
    private static void assertToolbarIsHonest(DataGridPage view, Region toolbar, int width)
            throws Exception {
        List<javafx.scene.control.ButtonBase> named =
                WaitForAsyncUtils.asyncFx(view::namedToolbarKeysForTest).get();
        List<javafx.scene.control.ButtonBase> folded =
                WaitForAsyncUtils.asyncFx(view::foldedToolbarKeysForTest).get();
        assertThat(named).as("12 个具名键").hasSize(12);

        double sceneRight = toolbar.getScene().getWidth();
        double toolbarRight = toolbar.localToScene(toolbar.getBoundsInLocal()).getMaxX();
        assertThat(toolbarRight)
                .as("%dpx 窗口里工具条被撑出去（%.0f > %.0f）", width, toolbarRight, sceneRight)
                .isLessThanOrEqualTo(sceneRight + 0.5);
        for (javafx.scene.control.ButtonBase key : named) {
            if (folded.contains(key)) {
                assertThat(key.isManaged())
                        .as("#%s 已经折进 » 却还占着位", key.getId()).isFalse();
                continue;
            }
            assertThat(key.isVisible()).as("#%s 既没显示也没折叠 —— 动作丢了", key.getId()).isTrue();
            Labeled labeled = (Labeled) key;
            assertThat(labeled.getText())
                    .as("#%s 可见时必须是完整文字，不许收成空", key.getId()).isNotBlank();
            assertThat(((javafx.scene.control.Control) key).getWidth())
                    .as("#%s 的文字被压扁了（实际 %.0f < 需要 %.0f）", key.getId(),
                            ((javafx.scene.control.Control) key).getWidth(), labeled.prefWidth(-1))
                    .isGreaterThanOrEqualTo(labeled.prefWidth(-1) - 0.5);
            assertThat(key.localToScene(key.getBoundsInLocal()).getMaxX())
                    .as("#%s 越出工具条右边缘", key.getId())
                    .isLessThanOrEqualTo(toolbarRight + 0.5);
        }
        Node overflow = fxNode(view, "#data-toolbar-overflow");
        assertThat(overflow.isManaged())
                .as("有键被折叠时 » 必须出现；没有折叠时必须让位")
                .isEqualTo(!folded.isEmpty());
        if (overflow.isManaged()) {
            assertThat(overflow.localToScene(overflow.getBoundsInLocal()).getMaxX())
                    .as("» 自己被挤出了窗口")
                    .isLessThanOrEqualTo(sceneRight + 0.5);
        }
    }

    private static List<String> ids(List<javafx.scene.control.ButtonBase> keys) {
        return keys.stream().map(Node::getId).toList();
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    /**
     * 挂上主题与 app.css。
     *
     * <p>⚠️ 不挂样式表，`adb-button--icon` 的 30px 钉宽、密度档、按钮内边距一条都不生效 ——
     * 工具条宽度会算出一个「哪档都放得下」的假数字，收字判据的门禁必然假绿。
     */
    private Scene styled(Scene scene) {
        java.net.URL dark = getClass().getResource("/io/agenticdb/ui/theme-dark.css");
        java.net.URL light = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        java.net.URL app = getClass().getResource("/io/agenticdb/ui/app.css");
        scene.getStylesheets().addAll(dark.toExternalForm(), light.toExternalForm(),
                app.toExternalForm());
        scene.getRoot().getStyleClass().removeAll("theme-dark", "theme-light");
        scene.getRoot().getStyleClass().add("theme-light");
        return scene;
    }

    /**
     * ⚠️ 读节点必须回 FX 线程 —— 与 {@link #click(String)} 上面那条注释同一个坑，
     * 只是它当时只修了按钮那一条路。全量套件里 {@code fitPagerToAvailableWidth} /
     * {@code updateToolbarOverflow} 这些 {@code Platform.runLater} 还在改子树，
     * 测试线程直接 {@code lookup} 就是在并发遍历一个正在被清空的
     * {@code getChildrenUnmodifiable()}，随机炸成
     * 「Index 15 out of bounds for length 0」（{@code Parent.lookup} 不是线程安全的）。
     * 单独跑永远绿，只有满负载才复现 —— 所以这条规则只能写在取节点的唯一入口上。
     */
    @SuppressWarnings("unchecked")
    private static <T extends javafx.scene.Node> T fxNode(javafx.scene.Parent root,
                                                          String selector) throws Exception {
        return (T) WaitForAsyncUtils.waitForAsyncFx(5_000, () -> root.lookup(selector));
    }

    private <T extends javafx.scene.Node> T node(String selector) throws Exception {
        return fxNode(page, selector);
    }

    /** 行数据是否已经到达 —— 轮询条件比一次性 lookup 更容易撞上重建，所以同样走 FX。 */
    private static boolean tableHasRows(DataGridPage view, int expected) throws Exception {
        Integer size = WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            TableView<?> table = (TableView<?>) view.lookup("#data-table");
            return table == null ? -1 : table.getItems().size();
        });
        return size == expected;
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> table() throws Exception {
        return node("#data-table");
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> transposeTable() throws Exception {
        return node("#data-transpose");
    }

    private ScrollPane formPane() throws Exception {
        ScrollPane form = node("#data-form");
        assertThat(form).as("找不到 #data-form").isNotNull();
        return form;
    }

    private Label formPosition() throws Exception {
        Label position = node("#data-form-position");
        assertThat(position).as("找不到 #data-form-position").isNotNull();
        return position;
    }

    private TextField field(String column) throws Exception {
        TextField field = node("#data-form-" + column);
        assertThat(field).as("找不到表单字段 #data-form-%s", column).isNotNull();
        return field;
    }

    private CheckBox formCheck(String column) throws Exception {
        CheckBox box = node("#data-form-" + column);
        assertThat(box).as("找不到表单复选框 #data-form-%s", column).isNotNull();
        return box;
    }

    private void selectRow(int index) throws Exception {
        TableView<Map<String, Object>> target = table();
        interact(() -> target.getSelectionModel().clearAndSelect(index));
    }

    private String displayedCellText(int row, String column) {
        AtomicReference<String> result = new AtomicReference<>();
        interact(() -> result.set(page.displayedCellText(row, column)));
        return result.get();
    }

    private void click(String id) {
        // ⚠️ lookup 必须回 FX 线程：全量套件里加载回调仍在改子树时，测试线程
        //    直接遍历会撞上「Index 14 out of bounds for length 0」（Parent.lookup 非线程安全）。
        Button button = WaitForAsyncUtils.waitForAsyncFx(5_000, () -> (Button) page.lookup(id));
        assertThat(button).as("找不到按钮 %s", id).isNotNull();
        interact(button::fire);
    }

    private DataGridPage.Feedback recordingFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
                lastStatus.set(message);
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
    }
}
