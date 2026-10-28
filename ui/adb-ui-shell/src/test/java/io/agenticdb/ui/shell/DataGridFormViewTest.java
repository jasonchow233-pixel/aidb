package io.agenticdb.ui.shell;

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
                .isEqualTo("记录 1 / 3");
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
                .isEqualTo("记录 2 / 3");
        assertThat(field("name").getText()).isEqualTo("Bob");

        click("#data-record-last");
        assertThat(formPosition().getText()).isEqualTo("记录 3 / 3");
        assertThat(field("name").getText()).isEqualTo("Cara");

        click("#data-record-first");
        assertThat(formPosition().getText()).isEqualTo("记录 1 / 3");
        assertThat(field("name").getText()).isEqualTo("Alice");

        click("#data-record-prev");
        assertThat(formPosition().getText())
                .as("已到第一条，再往回不许跑到 0 / 3 或 -1")
                .isEqualTo("记录 1 / 3");
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
                .isEqualTo("（NULL）");

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
        assertThat(((Button) page.lookup("#data-save")).isDisabled())
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
                .contains("无效值");
        assertThat(field("amount").getText())
                .as("打回原值 —— 输入框不能停在一个永远不会被保存的字符串上")
                .isEqualTo("10");

        interact(() -> {
            field("amount").setText("");
            field("amount").fireEvent(new ActionEvent());
        });
        assertThat(page.dirtyCount()).isZero();
        assertThat(lastStatus.get())
                .as("NOT NULL 列清空要指名道姓地说不允许 NULL")
                .contains("不允许 NULL");
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
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                    view.lookup("#data-table") != null
                            && ((TableView<?>) view.lookup("#data-table"))
                            .getItems().size() == 3);

            interact(() -> {
                ((TableView<Map<String, Object>>) view.lookup("#data-table"))
                        .getSelectionModel().clearAndSelect(0);
                view.setFormView(true);
            });

            assertThat(((ScrollPane) view.lookup("#data-form")).isVisible()).isTrue();
            TextField name = (TextField) view.lookup("#data-form-name");
            assertThat(name).as("只读表也必须建出表单字段").isNotNull();
            assertThat(name.getText())
                    .as("只读不等于空白：值必须照常显示")
                    .isEqualTo("Alice");
            assertThat(name.isDisabled())
                    .as("视图没有主键 → 不可编辑 —— 字段必须禁用，而不是让人改完才发现保存不了")
                    .isTrue();
            assertThat(((Button) view.lookup("#data-add")).isDisabled())
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
     * <p>实拍踩过（`p2-form` 帧）：窗口 1280、侧栏 240、工具条可用 1040，
     * 而选中行之后带文字的需求是 1093px —— 这一排收成图标本来是对的，但紧接着的<b>第二次重判</b>
     * 把需求宽量成 556px：收起时只装回了文字、没摘掉 `adb-button--icon`
     * （它把 minWidth 钉成 30px），而 CSS 的 min-width 不同步重算，读到的还是 30px。
     * 556 < 1040 于是「放得下」，文字全涨回来，最右的搜索框被裁到只剩一个「本」字。
     *
     * <p>这里连「收字 → 再判一次」这个时序一起钉住：选中第一行让这一排收字，
     * 再选中第二行触发第二次重判 —— 文字不许自己涨回来，框也不许出窗。
     */
    @Test
    @SuppressWarnings("unchecked")
    void formViewWithSelectionKeepsTheToolbarInsideTheWindow() throws Exception {
        // 与实拍帧同一套几何：1280 - 240(侧栏) = 1040px 可用。
        // 没有选中行时（需求 994）放得下，选中行多出「删除记录」（需求 1093）
        // 就放不下 —— 前提可控，判据才有意义。
        final int width = 1280;
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
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                    view.lookup("#data-table") != null
                            && ((TableView<?>) view.lookup("#data-table")).getItems().size() == 3);
            WaitForAsyncUtils.waitForFxEvents(4);

            TableView<Map<String, Object>> table =
                    (TableView<Map<String, Object>>) view.lookup("#data-table");
            Region toolbar = (Region) view.lookup("#data-toolbar");
            Node search = view.lookup("#data-search");
            assertThat(toolbar).as("找不到 #data-toolbar").isNotNull();
            assertThat(search).as("找不到 #data-search").isNotNull();
            assertThat(((Labeled) view.lookup("#data-save")).getText())
                    .as("前置条件：没有选中行时这一排应该带文字（否则这条门禁证不了收字）")
                    .isNotBlank();

            // 第一次：选中行 → 多一颗「删除记录」→ 放不下 → 收字。
            interact(() -> {
                table.getSelectionModel().select(0);
                view.setFormView(true);
            });
            WaitForAsyncUtils.waitForFxEvents(3);
            assertThat(((Labeled) view.lookup("#data-save")).getText())
                    .as("表单视图 + 选中行放不下文字时应收成图标").isBlank();

            // 第二次重判：选中另一行，重走一次性判断 —— 文字不许涨回来。
            interact(() -> table.getSelectionModel().select(1));
            WaitForAsyncUtils.waitForFxEvents(3);
            assertThat(((Labeled) view.lookup("#data-save")).getText())
                    .as("收字后的第二次重判把文字又放回来了（需求宽被量成了图标档）")
                    .isBlank();

            // 第三次：再选一行，仍是「收着字」的状态下重判 ——
            // 这一趟这才真正走到「收起时去量需求宽」那条路（不上一步会先合法展开）。
            interact(() -> table.getSelectionModel().select(2));
            WaitForAsyncUtils.waitForFxEvents(3);
            assertThat(((Labeled) view.lookup("#data-save")).getText())
                    .as("收起状态下重判时需求宽被量成图标档（556px），文字又涨回来了")
                    .isBlank();

            double sceneRight = stageRef.get().getScene().getWidth();
            assertThat(toolbar.localToScene(toolbar.getBoundsInLocal()).getMaxX())
                    .as("表单视图 + 选中行把工具条撑出了窗口（%d 场景）—— 它不肯收字", width)
                    .isLessThanOrEqualTo(sceneRight + 0.5);
            assertThat(search.localToScene(search.getBoundsInLocal()).getMaxX())
                    .as("最右的搜索框被裁到窗外（它必须整颗在框内）")
                    .isLessThanOrEqualTo(sceneRight + 0.5);
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

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> table() {
        return (TableView<Map<String, Object>>) page.lookup("#data-table");
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> transposeTable() {
        return (TableView<Map<String, Object>>) page.lookup("#data-transpose");
    }

    private ScrollPane formPane() {
        ScrollPane form = (ScrollPane) page.lookup("#data-form");
        assertThat(form).as("找不到 #data-form").isNotNull();
        return form;
    }

    private Label formPosition() {
        Label position = (Label) page.lookup("#data-form-position");
        assertThat(position).as("找不到 #data-form-position").isNotNull();
        return position;
    }

    private TextField field(String column) {
        TextField field = (TextField) page.lookup("#data-form-" + column);
        assertThat(field).as("找不到表单字段 #data-form-%s", column).isNotNull();
        return field;
    }

    private CheckBox formCheck(String column) {
        CheckBox box = (CheckBox) page.lookup("#data-form-" + column);
        assertThat(box).as("找不到表单复选框 #data-form-%s", column).isNotNull();
        return box;
    }

    private void selectRow(int index) {
        interact(() -> table().getSelectionModel().clearAndSelect(index));
    }

    private String displayedCellText(int row, String column) {
        AtomicReference<String> result = new AtomicReference<>();
        interact(() -> result.set(page.displayedCellText(row, column)));
        return result.get();
    }

    private void click(String id) {
        Button button = (Button) page.lookup(id);
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
