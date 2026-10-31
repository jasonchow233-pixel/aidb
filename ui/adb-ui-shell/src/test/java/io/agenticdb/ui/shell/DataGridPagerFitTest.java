package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ N20 / D-1 的门禁：底栏形态与窄宽降级。
 *
 * <p>Navicat 的底栏是「左记录操作条 + 右五键翻页 / 页码框 / 齿轮 / 放大镜 / 两枚视图切换」
 * （§1.4），记录计数在状态栏，没有行数 / 页大小 / 「跳至页」文字控件。
 * 这里钉住四件事：
 * <ol>
 *   <li>底栏里不该再出现记录范围 / 记录导航 / 页大小 / 跳页这些旧控件；</li>
 *   <li>窄宽只从右往左整项收起视图切换与放大镜，五键 / 页码框 / 齿轮永不收；</li>
 *   <li>宽度回来时全部还原 —— 不能一窄就永久丢控件；</li>
 *   <li>D-1：详情浮层压住的那一截要从可用宽度里扣掉，不能按盒子宽度自欺。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class DataGridPagerFitTest {

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;
    private Stage stage;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-pager-fit");
            thread.setDaemon(true);
            return thread;
        });
        page = new DataGridPage(session, new SchemaService(), "t", "test.db",
                noOpFeedback(), name -> { }, executor);
        // ⭐ 页面必须包一层：Scene 把「场景根」直接 resize 成窗口尺寸，
        //     maxSize 对根无效（实测 setMaxSize(950) 后根宽还是 1280）。
        Scene scene = new Scene(new javafx.scene.layout.StackPane(page), 1280, 700);
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        page.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void awaitLoaded() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                page.lookup("#data-table") != null
                        && ((javafx.scene.control.TableView<?>) page.lookup("#data-table"))
                        .getItems().size() == 3);
        // 宽度监听器把拟合任务排在 runLater 里，排空再量。
        WaitForAsyncUtils.waitForFxEvents(30);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            WaitForAsyncUtils.asyncFx(page::close).get();
        }
        executor.shutdownNow();
        session.close();
    }

    @Test
    void thePagerIsTheNavKeysPageBoxGearMagnifierAndViewToggles() throws Exception {
        List<String> ids = WaitForAsyncUtils.asyncFx(() -> new ArrayList<>(
                ((HBox) page.lookup("#data-pager")).getChildren().stream()
                        .map(Node::getId)
                        .filter(java.util.Objects::nonNull)
                        .toList())).get();
        assertThat(ids)
                .as("底栏的项目集（分隔线 id 为 null 不在此列）")
                .contains("data-row-actions", "data-first", "data-prev", "data-page",
                        "data-next", "data-last", "data-page-options",
                        "data-find-toggle", "data-view-grid", "data-view-form");
        HBox pager = (HBox) page.lookup("#data-pager");
        for (String gone : List.of("#data-range", "#data-jump", "#data-jump-row",
                "#data-page-size", "#data-per-page", "#data-page-label", "#data-record-nav")) {
            assertThat(pager.lookup(gone))
                    .as("⭐ N20：%s 不该再出现在底栏（记录计数归状态栏，页大小归齿轮）", gone)
                    .isNull();
        }
    }

    @Test
    void narrowPagerFoldsOnlyTheRightEndAndRestoresIt() throws Exception {
        PagerState wide = feedAndRead(1e9);
        double full = wide.pref();
        assertThat(full).as("满配宽度得是个像样的数").isGreaterThan(200);
        assertThat(wide.allVisible()).as("够宽时右端全在").isTrue();

        PagerState oneShort = feedAndRead(full - 1);
        assertThat(oneShort.form())
                .as("只差 1px 时先收最右的表单切换")
                .isFalse();
        assertThat(oneShort.formManaged()).isFalse();
        assertThat(oneShort.grid()).as("一格之差不许连锁砍掉一串").isTrue();
        assertThat(oneShort.find()).isTrue();

        PagerState stage2 = feedAndRead(oneShort.pref() - 1);
        assertThat(stage2.grid()).as("第二档收网格切换").isFalse();
        assertThat(stage2.find()).as("放大镜是最后一档").isTrue();

        PagerState stage3 = feedAndRead(stage2.pref() - 1);
        assertThat(stage3.find()).as("第三档收放大镜").isFalse();

        PagerState tiny = feedAndRead(200);
        assertThat(tiny.allVisible()).as("极窄时三颗都收起").isFalse();
        assertThat(tiny.navAlways())
                .as("五键 / 页码框 / 齿轮永不清除 —— 否则翻不了页、改不了页大小")
                .isTrue();

        PagerState restored = feedAndRead(full + 40);
        assertThat(restored.allVisible()).as("变宽后必须还原，不能永久丢失").isTrue();
    }

    @Test
    void thePageBoxFitsAFourDigitPageNumber() throws Exception {
        TextField box = (TextField) page.lookup("#data-page");
        assertThat(box).isNotNull();
        assertThat(box.getText()).as("页码框显示当前页号").isEqualTo("1");
        double need = WaitForAsyncUtils.asyncFx(() -> {
            javafx.scene.text.Text probe = new javafx.scene.text.Text("9999");
            probe.setFont(box.getFont());
            return probe.getLayoutBounds().getWidth() + 16;
        }).get();
        assertThat(box.getWidth() + 0.5)
                .as("页码框得放得下四位页号（否则翻到 1000 页就显示成省略号）")
                .isGreaterThanOrEqualTo(need);
    }

    @Test
    void thePageBoxJumpsOnEnter() throws Exception {
        page.setPageSizeForTest(2);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
            TextField box = (TextField) page.lookup("#data-page");
            return box != null && "1".equals(box.getText())
                    && ((javafx.scene.control.TableView<?>) page.lookup("#data-table"))
                    .getItems().size() == 2;
        });
        WaitForAsyncUtils.asyncFx(() -> {
            page.jumpToPage("2");
            return null;
        }).get();
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
            TextField field = (TextField) page.lookup("#data-page");
            @SuppressWarnings("unchecked")
            javafx.scene.control.TableView<Map<String, Object>> table =
                    (javafx.scene.control.TableView<Map<String, Object>>) page.lookup("#data-table");
            return field != null && "2".equals(field.getText())
                    && table.getItems().size() == 1
                    && "3".equals(String.valueOf(table.getItems().get(0).get("id")));
        });
    }

    @Test
    void theGearMenuHoldsPageSizeAndJumpToRecord() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            ((javafx.scene.control.Button) page.lookup("#data-page-options")).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();
        ContextMenu menu = page.pageOptionsMenuForTest();
        List<String> ids = WaitForAsyncUtils.asyncFx(() ->
                menu.getItems().stream().map(MenuItem::getId)
                        .filter(java.util.Objects::nonNull).toList()).get();
        assertThat(ids)
                .as("⭐ N20：页大小与跳至记录都收进齿轮，底栏不再摆文字控件")
                .contains("data-page-jump-row")
                .anyMatch(id -> id.startsWith("data-page-size-"));
    }

    @Test
    void theMagnifierOpensAndClosesTheFindBar() {
        javafx.scene.control.ButtonBase toggle =
                (javafx.scene.control.ButtonBase) page.lookup("#data-find-toggle");
        WaitForAsyncUtils.asyncFx(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.findBarVisible()).as("放大镜打开查找条").isTrue();
        WaitForAsyncUtils.asyncFx(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.findBarVisible()).as("再按一次收起").isFalse();
    }

    @Test
    void theViewTogglesSwitchGridAndForm() {
        ToggleButton form = (ToggleButton) page.lookup("#data-view-form");
        ToggleButton grid = (ToggleButton) page.lookup("#data-view-grid");
        assertThat(grid.isSelected()).as("默认是表格视图").isTrue();
        WaitForAsyncUtils.asyncFx(form::fire);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(form.isSelected()).isTrue();
        assertThat(grid.isSelected()).isFalse();
        assertThat(page.lookup("#data-form-section").isVisible())
                .as("表单视图整块出现").isTrue();
        assertThat(page.lookup("#data-record-nav").isVisible())
                .as("网格里点一行就是导航；表单视图才需要另一套方向键")
                .isTrue();
        WaitForAsyncUtils.asyncFx(grid::fire);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(grid.isSelected()).isTrue();
        assertThat(page.lookup("#data-form-section").isVisible()).isFalse();
    }

    /**
     * ⭐ D-1：详情浮层向下溢出时会压在底栏右端（JavaFX 默认不裁边）。
     * 可用宽度必须把浮层左侧那一段扣掉，否则阶梯按盒子宽度判「放得下」，
     * 右端控件实际被盖住 —— p2-details 实拍里「每页」框露出半截就是这么来的。
     */
    @Test
    void theDetailsOverlayShrinksTheAvailablePagerWidth() throws Exception {
        double before = WaitForAsyncUtils.asyncFx(page::availablePagerWidthForTest).get();
        double pagerWidth = WaitForAsyncUtils.asyncFx(
                () -> page.lookup("#data-pager").getLayoutBounds().getWidth()).get();
        assertThat(before).as("没有浮层时可用宽度就是底栏自己的宽度")
                .isCloseTo(pagerWidth, org.assertj.core.data.Offset.offset(1.0));

        ColumnDef name = new SchemaService().describe(session, "t").columns().stream()
                .filter(column -> column.name().equals("name")).findFirst().orElseThrow();
        Map<String, Object> row = ((javafx.scene.control.TableView<Map<String, Object>>)
                page.lookup("#data-table")).getItems().get(0);
        WaitForAsyncUtils.asyncFx(() -> {
            page.showDetails(row, name);
            return null;
        }).get();
        forceLayout();
        WaitForAsyncUtils.waitForFxEvents(10);

        // ⭐ 当前抽屉由 StackPane 约束在 body 内，不会自己越界；D-1 防的是
        //    「浮层比 body 高、向下压住底栏」这种不受裁边的布局。这里用 translateY
        //    把浮层显式推到与底栏重叠，验证宽度计算走的是**场景坐标**而不是盒子宽度。
        WaitForAsyncUtils.asyncFx(() -> {
            Node drawer = page.lookup("#data-details");
            Node pager = page.lookup("#data-pager");
            double drawerMaxY = drawer.localToScene(drawer.getBoundsInLocal()).getMaxY();
            double pagerMinY = pager.localToScene(pager.getBoundsInLocal()).getMinY();
            drawer.setTranslateY(pagerMinY - drawerMaxY + pager.getLayoutBounds().getHeight() / 2);
            return null;
        }).get();
        forceLayout();

        double open = WaitForAsyncUtils.asyncFx(page::availablePagerWidthForTest).get();
        assertThat(open)
                .as("浮层压到底栏后可用宽度必须明显变窄（%.0f → %.0f）", before, open)
                .isLessThan(before - 100);
        assertThat(WaitForAsyncUtils.asyncFx(() ->
                page.lookup("#data-next").isVisible()).get())
                .as("扣掉浮层宽度后五键仍在 —— 降级只收视图切换")
                .isTrue();
    }

    /** 场景缩高后 JavaFX 的 pulse 不一定来得及，测试里显式排一次布局。 */
    private void forceLayout() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            if (stage.getScene() == null) {
                return null;
            }
            javafx.scene.Parent root = stage.getScene().getRoot();
            root.requestLayout();
            root.applyCss();
            root.layout();
            return null;
        }).get();
    }

    private record PagerState(boolean form, boolean formManaged, boolean grid, boolean find,
                              boolean navAlways, double pref) {
        boolean allVisible() {
            return form && grid && find;
        }
    }

    /**
     * 阶梯某一档的<b>一次原子读数</b>：同一条 FX 任务里喂完宽度立刻读，
     * 中间不插宽度监听器排下的旧任务。
     */
    private PagerState feedAndRead(double width) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> {
            page.fitPagerWidthForTest(width);
            return new PagerState(
                    shown("#data-view-form"), managed("#data-view-form"),
                    shown("#data-view-grid"), shown("#data-find-toggle"),
                    shown("#data-first") && shown("#data-prev") && shown("#data-page")
                            && shown("#data-next") && shown("#data-last")
                            && shown("#data-page-options"),
                    ((HBox) page.lookup("#data-pager")).prefWidth(-1));
        }).get();
    }

    private boolean shown(String id) {
        Node node = page.lookup(id);
        return node != null && node.isVisible();
    }

    private boolean managed(String id) {
        Node node = page.lookup(id);
        return node != null && node.isManaged();
    }

    private static DataGridPage.Feedback noOpFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }
}
