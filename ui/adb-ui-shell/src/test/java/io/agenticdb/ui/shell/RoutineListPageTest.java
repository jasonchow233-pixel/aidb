package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 2026-11-10：例程清单页（{@link RoutineListPage}）的门禁。
 *
 * <p>这页是功能区「函数」格的真实落点（此前那格落说明页，而定义体早已交付）。
 * 门禁钉三件事：
 * <ol>
 *   <li><b>哪些对象算例程</b> —— 函数 / 存储过程 / 调度事件进清单，
 *       表与视图不进（那是对象清单页的事）；</li>
 *   <li><b>点行能打开定义体</b> —— 双击 / 回车把「名字 + 类型」交给外壳；</li>
 *   <li><b>搜索只作用本页</b>，且空清单时说实话而不是摆一张空表。</li>
 * </ol>
 *
 * <p>⚠️ 为什么用注入的数据源而不是真库：SQLite 的 JDBC 驱动
 * {@code getFunctions} 直接抛 {@code SQLFeatureNotSupportedException}
 * （实测过），真库也未必有例程 —— 那样这条门禁就永远只能验「空清单」，
 * 验不到筛选与点开这两段真正要紧的逻辑。
 */
@ExtendWith(ApplicationExtension.class)
class RoutineListPageTest {

    @Start
    void start(Stage stage) {
        stage.show();
    }

    /** 只有函数 / 过程 / 事件进清单；表与视图不进。 */
    @Test
    void onlyFunctionsProceduresAndEventsCountAsRoutines() {
        List<RoutineListPage.Row> rows = RoutineListPage.rowsOf(List.of(
                new SchemaObject("users", ObjectKind.TABLE, "public"),
                new SchemaObject("v_active", ObjectKind.VIEW, "public"),
                new SchemaObject("add_one", ObjectKind.FUNCTION, "public"),
                new SchemaObject("refresh_all", ObjectKind.PROCEDURE, "public"),
                new SchemaObject("nightly", ObjectKind.EVENT, "public"),
                new SchemaObject("users_trigger", ObjectKind.TRIGGER, "public")));

        assertThat(rows).extracting(RoutineListPage.Row::name)
                .as("表 / 视图 / 触发器不进例程清单")
                .containsExactly("add_one", "nightly", "refresh_all");
        assertThat(rows).extracting(RoutineListPage.Row::kind)
                .containsExactly(ObjectKind.FUNCTION, ObjectKind.EVENT, ObjectKind.PROCEDURE);
    }

    /** 按名字排序（不分大小写），与左侧树里的分组顺序一致。 */
    @Test
    void routinesAreSortedByNameIgnoringCase() {
        List<RoutineListPage.Row> rows = RoutineListPage.rowsOf(List.of(
                new SchemaObject("zeta", ObjectKind.FUNCTION),
                new SchemaObject("Alpha", ObjectKind.FUNCTION),
                new SchemaObject("beta", ObjectKind.PROCEDURE)));

        assertThat(rows).extracting(RoutineListPage.Row::name)
                .containsExactly("Alpha", "beta", "zeta");
    }

    /** 空清单时不摆一张空表，而是给出实话（图标 + 标题 + 说明 + 出口）。 */
    @Test
    void anEmptyLibrarySaysSoInsteadOfShowingAnEmptyTable() {
        RoutineListPage page = onFx(() -> new RoutineListPage(null, new SchemaService(),
                Runnable::run, (name, kind) -> { }, () -> List.of()));

        TableView<?> table = (TableView<?>) onFx(() -> page.lookup("#routine-list-table"));
        assertThat(table).as("清单页本身要在").isNotNull();
        assertThat(onFx(() -> table.getItems())).as("没有例程时表里一行都不该有").isEmpty();
        // ⚠️ U24：占位不许是裸 Label —— 一行灰字飘在白底上，用户只能猜是空、是坏了、
        //    还是没权限。必须是完整空态（图标 + 标题 + 说明 + 出口）。
        javafx.scene.Node placeholder = onFx(() -> table.getPlaceholder());
        assertThat(placeholder).as("空态要给出来").isNotNull();
        assertThat(onFx(() -> placeholder.lookupAll(".adb-empty"))).as("空态根节点").isNotEmpty();
        assertThat(onFx(() -> placeholder.lookupAll(".adb-empty__icon"))).as("必须有图标").isNotEmpty();
        assertThat(onFx(() -> placeholder.lookupAll(".adb-empty__title"))).as("必须有标题").isNotEmpty();
        assertThat(onFx(() -> placeholder.lookupAll(".adb-button--primary")))
                .as("空态必须给一个明确的下一步（清空筛选）")
                .isNotEmpty();
    }

    /** 清单要真列出来，且表名 / 类型 / 模式三列都在。 */
    @Test
    void theListingShowsNameKindAndSchema() {
        RoutineListPage page = onFx(() -> new RoutineListPage(null, new SchemaService(),
                Runnable::run, (name, kind) -> { }, () -> List.of(
                        new SchemaObject("add_one", ObjectKind.FUNCTION, "public"),
                        new SchemaObject("refresh_all", ObjectKind.PROCEDURE, "public"))));

        TableView<?> table = (TableView<?>) onFx(() -> page.lookup("#routine-list-table"));
        assertThat(onFx(() -> table.getItems())).hasSize(2);
        assertThat(onFx(() -> table.getColumns())).hasSize(3);
        assertThat(onFx(() -> table.getColumns().get(0).getText()))
                .isEqualTo(I18n.t("column.name"));
        assertThat(onFx(() -> table.getColumns().get(1).getText()))
                .isEqualTo(I18n.t("tool.routines.kind"));
        assertThat(onFx(() -> table.getColumns().get(2).getText()))
                .isEqualTo(I18n.t("tool.routines.schema"));
    }

    /** 点一行就把「名字 + 类型」交给外壳 —— 外壳据此开定义体标签页。 */
    @Test
    void selectingARowHandsTheNameAndKindToTheShell() {
        AtomicReference<String> openedName = new AtomicReference<>();
        AtomicReference<ObjectKind> openedKind = new AtomicReference<>();
        RoutineListPage page = onFx(() -> new RoutineListPage(null, new SchemaService(),
                Runnable::run, (name, kind) -> {
                    openedName.set(name);
                    openedKind.set(kind);
                }, () -> List.of(
                        new SchemaObject("add_one", ObjectKind.FUNCTION),
                        new SchemaObject("refresh_all", ObjectKind.PROCEDURE))));

        onFxRun(() -> page.openRow(1));
        assertThat(openedName.get()).isEqualTo("refresh_all");
        assertThat(openedKind.get()).isEqualTo(ObjectKind.PROCEDURE);
    }

    /** 搜索只作用本页：输入关键字后表里只剩匹配的行。 */
    @Test
    void theSearchBoxFiltersThisPageOnly() {
        RoutineListPage page = onFx(() -> new RoutineListPage(null, new SchemaService(),
                Runnable::run, (name, kind) -> { }, () -> List.of(
                        new SchemaObject("add_one", ObjectKind.FUNCTION),
                        new SchemaObject("refresh_all", ObjectKind.PROCEDURE),
                        new SchemaObject("nightly", ObjectKind.EVENT))));

        TextField search = (TextField) onFx(() -> page.lookup("#routine-list-search"));
        assertThat(search).as("搜索框要在").isNotNull();

        onFxRun(() -> search.setText("re"));
        assertThat(onFx(page::visibleRowCount))
                .as("只留名字里含「re」的行")
                .isEqualTo(1);

        onFxRun(() -> search.setText(""));
        assertThat(onFx(page::visibleRowCount)).as("清空搜索后全回来").isEqualTo(3);
    }

    /** 页面标题与副标题要说清这是「例程」页、副标题带上方言。 */
    @Test
    void thePageSaysWhatItIs() {
        RoutineListPage page = onFx(() -> new RoutineListPage(null, new SchemaService(),
                Runnable::run, (name, kind) -> { }, () -> List.of()));

        assertThat(onFx(() -> ((Label) page.lookup("#routine-list-title")).getText()))
                .isEqualTo(I18n.t("tool.routines.title"));
        assertThat(onFx(() -> ((Label) page.lookup("#routine-list-subtitle")).getText()))
                .isEqualTo(I18n.t("tool.routines.subtitle", "—"));
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
