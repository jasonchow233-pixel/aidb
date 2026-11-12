package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearMissingKeyLedger() {
        // 新增的只读定义体页用例会多读几个文案键 —— 清掉缺键记账，
        // 免得污染全仓 I18n 门禁那本全局账（与 RoutineViewerTest 同一条口径）。
        I18n.clearMissingKeys();
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

    /**
     * ⭐ T12：EVENT 行点开时把「这是事件」如实交给外壳。
     *
     * <p>外壳（{@code AppShell} 的例程清单 onOpen）据此把 scheduledEvent 位设真，
     * 开的是 {@code RoutinePage} 的<b>只读</b>定义体页，而不是可执行的函数页。
     *
     * <p>变异验证：把 {@code RoutineListPage.rowsOf} 里的
     * {@code || kind == ObjectKind.EVENT} 去掉，或把 {@code openSelected} 里
     * {@code row.kind()} 换成 {@code ObjectKind.FUNCTION}，本用例必须变红。
     */
    @Test
    void eventRowsHandTheirEventKindToTheShell() {
        AtomicReference<String> openedName = new AtomicReference<>();
        AtomicReference<ObjectKind> openedKind = new AtomicReference<>();
        RoutineListPage page = onFx(() -> new RoutineListPage(null, new SchemaService(),
                Runnable::run, (name, kind) -> {
                    openedName.set(name);
                    openedKind.set(kind);
                }, () -> List.of(
                        new SchemaObject("nightly", ObjectKind.EVENT))));

        onFxRun(() -> page.openRow(0));
        assertThat(openedName.get()).isEqualTo("nightly");
        assertThat(openedKind.get())
                .as("事件必须带自己的类型交给外壳 —— 外壳据此开只读定义体页")
                .isEqualTo(ObjectKind.EVENT);
    }

    /**
     * ⭐ T12：事件定义体页必须只读，且读不到定义体时如实说明、不伪造 DDL。
     *
     * <p>{@code scheduledEvent=true} 时 {@code RoutinePage} 走
     * {@code SchemaService.eventDefinition}；假会话读不到事件定义体（方言层返回空），
     * 页面给的是一句「读不到」的实话，不是一段编出来的 CREATE EVENT。
     *
     * <p>变异验证：把 {@code RoutinePage} 里
     * {@code editable = !scheduledEvent && RoutineSql.supported(dialectKind)} 的
     * {@code !scheduledEvent &&} 去掉 —— PostgreSQL 这张页会变成可编辑、Execute 也会亮，
     * 本用例「只读 / 禁执行」两条断言必须变红。
     */
    @Test
    void eventDefinitionPageIsReadOnlyAndReportsMissingBodyHonestly() {
        RoutinePage page = onFx(() -> new RoutinePage(
                "nightly", false, true,
                FakeDbSession.of(DialectKind.POSTGRESQL),
                new SchemaService(), Runnable::run, message -> { }));
        // load() 走 dbExecutor 后在 Platform.runLater 回写，额外刷一次 FX 队列等它落地。
        onFxRun(() -> { });

        TextArea body = (TextArea) onFx(() -> page.lookup("#routine-body"));
        Button execute = (Button) onFx(() -> page.lookup("#routine-execute"));
        Label error = (Label) onFx(() -> page.lookup("#routine-error"));

        assertThat(onFx(body::isEditable)).as("事件定义体只读，不许改").isFalse();
        assertThat(onFx(execute::isDisabled)).as("事件不许执行 / 重建").isTrue();
        assertThat(onFx(error::isVisible)).as("读不到定义体就说实话").isTrue();
        String definition = onFx(body::getText);
        assertThat(definition).as("不伪造 DDL").isEmpty();
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

    @Test
    void decliningRoutineRebuildPreviewsExactSqlAndDoesNotQueueWrites() {
        FakeDbSession session = FakeDbSession.of(DialectKind.POSTGRESQL);
        java.util.Queue<Runnable> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        AtomicReference<io.agenticdb.introspect.StructurePlan> approved = new AtomicReference<>();
        RoutinePage page = onFx(() -> new RoutinePage("add_one", false, false, session,
                new SchemaService(), tasks::add, text -> { }, plan -> {
                    approved.set(plan);
                    return false;
                }));
        tasks.remove().run();
        WaitForAsyncUtils.waitForFxEvents();
        String definition = "CREATE FUNCTION add_one(x integer) RETURNS integer LANGUAGE SQL AS 'SELECT x+1'";
        onFxRun(() -> {
            ((TextArea) page.lookup("#routine-body")).setText(definition);
            ((Button) page.lookup("#routine-execute")).fire();
        });

        assertThat(approved.get()).isNotNull();
        assertThat(approved.get().statements()).containsExactly(
                io.agenticdb.introspect.RoutineSql.preview("add_one", true, definition, DialectKind.POSTGRESQL));
        assertThat(approved.get().titleArgs()).containsExactly(session.label(), "add_one");
        assertThat(approved.get().danger()).isEqualTo(io.agenticdb.model.Danger.DESTRUCTIVE);
        assertThat(approved.get().approvalRequired()).isTrue();
        assertThat(tasks).isEmpty();
        assertThat(session.executed()).isEmpty();
        assertThat(onFx(() -> page.lookup("#routine-execute").isDisabled())).isFalse();
        assertThat(onFx(() -> ((TextArea) page.lookup("#routine-body")).isEditable())).isTrue();
    }

    @Test
    void confirmedRoutineExecutesSnapshotOnceWithDeclaredDangerAndReportsUnreadableReload() throws Exception {
        FakeDbSession delegate = FakeDbSession.of(DialectKind.POSTGRESQL);
        java.util.List<io.agenticdb.model.Danger> declarations = new java.util.ArrayList<>();
        io.agenticdb.jdbc.DbSession session = (io.agenticdb.jdbc.DbSession) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.agenticdb.jdbc.DbSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("execute")) {
                        if (method.getParameterCount() != 2) throw new AssertionError("Danger must be explicit");
                        declarations.add((io.agenticdb.model.Danger) args[1]);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        delegate.begin();
        java.util.Queue<Runnable> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.List<String> statuses = new java.util.ArrayList<>();
        AtomicReference<io.agenticdb.introspect.StructurePlan> approved = new AtomicReference<>();
        RoutinePage page = onFx(() -> new RoutinePage("add_one", false, false, session,
                new SchemaService(), tasks::add, statuses::add, plan -> {
                    approved.set(plan);
                    return true;
                }));
        tasks.remove().run();
        WaitForAsyncUtils.waitForFxEvents();
        onFxRun(() -> {
            ((TextArea) page.lookup("#routine-body")).setText(
                    "CREATE FUNCTION add_one(x integer) RETURNS integer LANGUAGE SQL AS 'SELECT x+1'");
            ((Button) page.lookup("#routine-execute")).fire();
            ((Button) page.lookup("#routine-execute")).fire();
        });
        assertThat(tasks).hasSize(1);
        assertThat(delegate.executed()).isEmpty();
        assertThat(onFx(() -> page.lookup("#routine-execute").isDisabled())).isTrue();
        assertThat(onFx(() -> ((TextArea) page.lookup("#routine-body")).isEditable())).isFalse();
        onFxRun(() -> ((TextArea) page.lookup("#routine-body")).setText("changed after confirmation"));
        tasks.remove().run();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(delegate.executed()).containsExactlyElementsOf(approved.get().statements());
        assertThat(declarations).containsExactly(approved.get().danger());
        assertThat(delegate.inTransaction()).isTrue();
        assertThat(onFx(() -> page.lookup("#routine-execute").isDisabled())).isFalse();
        assertThat(onFx(() -> ((Label) page.lookup("#routine-error")).getText()))
                .isEqualTo(I18n.t("routine.executedReadFailed", "add_one"));
        assertThat(statuses).doesNotContain(I18n.t("routine.executed", "add_one"));
    }

    @Test
    void routineFailureReleasesBusyStateWithoutClosingUserTransaction() throws Exception {
        FakeDbSession session = FakeDbSession.of(DialectKind.POSTGRESQL);
        session.begin();
        session.failOn("CREATE OR REPLACE");
        java.util.Queue<Runnable> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        RoutinePage page = onFx(() -> new RoutinePage("add_one", false, false, session,
                new SchemaService(), tasks::add, text -> { }, plan -> true));
        tasks.remove().run();
        WaitForAsyncUtils.waitForFxEvents();
        onFxRun(() -> {
            ((TextArea) page.lookup("#routine-body")).setText(
                    "CREATE FUNCTION add_one(x integer) RETURNS integer LANGUAGE SQL AS 'SELECT x+1'");
            ((Button) page.lookup("#routine-execute")).fire();
        });
        tasks.remove().run();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).hasSize(1);
        assertThat(session.inTransaction()).isTrue();
        assertThat(onFx(() -> page.lookup("#routine-execute").isDisabled())).isFalse();
        assertThat(onFx(() -> ((TextArea) page.lookup("#routine-body")).isEditable())).isTrue();
        assertThat(onFx(() -> ((Label) page.lookup("#routine-error")).getText()))
                .isEqualTo(I18n.t("routine.executeFailed", "模拟失败：CREATE OR REPLACE"));
    }

    private static void onFxRun(Runnable action) {
        onFx(() -> {
            action.run();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        try {
            return WaitForAsyncUtils.waitForAsyncFx(5_000, supplier::get);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
