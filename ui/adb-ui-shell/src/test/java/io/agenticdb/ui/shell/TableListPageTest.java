package io.agenticdb.ui.shell;

import io.agenticdb.introspect.TableMeta;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表清单页门禁。
 *
 * <p>本轮（N1–N5）把表清单从「只能看」补成「能操作」：
 * 新建 / 删除 / 设计工具条、右键菜单、多选批量删除、列表 / 网格视图、
 * 状态栏选中项文案。这里锁住这些行为，避免以后又缩回一张只读表格。
 */
@ExtendWith(ApplicationExtension.class)
class TableListPageTest {

    private TableListPage page;
    private final List<String> opened = new ArrayList<>();
    private final List<String> designed = new ArrayList<>();
    private final List<List<String>> dropped = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<String> selections = new ArrayList<>();
    /** 报给右侧「对象详情面板」的表名；{@code null} = 没有单一对象可看。 */
    private final List<String> inspected = new ArrayList<>();

    private final TableListPage.Host host = new TableListPage.Host() {
        @Override
        public void openTable(String name) {
            opened.add(name);
        }

        @Override
        public void designTable(String name) {
            designed.add(name);
        }

        @Override
        public void dropTables(List<String> names) {
            dropped.add(List.copyOf(names));
        }

        @Override
        public void selectionChanged(String text) {
            selections.add(text);
        }

        @Override
        public void inspectTable(String name) {
            inspected.add(name);
        }
    };

    @Start
    void start(Stage stage) {
        // 构造器里的后台读取丢弃（executor 不执行），具体数据由 render(...) 直接喂，
        // 避免竞态：真实 reload 完成时会把 render 的数据覆盖掉。
        // actions 传 null → 页面用本地动作（按钮 / 右键菜单仍是同一组 Action）。
        page = new TableListPage(null, null, command -> { }, opened::add, messages::add,
                null, host, "list", null);
        page.render(List.of(
                new TableMeta("users", 200L, 3072L, "InnoDB",
                        "2026-01-01 09:00:00", "2026-02-01 10:00:00",
                        "utf8mb4_0900_ai_ci", "用户表"),
                new TableMeta("logs", 1000L, 1024L * 1024L, "InnoDB",
                        "2026-01-02 09:00:00", null, null, null),
                new TableMeta("audit", null, null, null, null, null, null, null)));
        stage.setScene(new Scene(page, 1100, 640));
        stage.show();
    }

    @Test
    void showsTheEightNavicatColumns() {
        assertThat(page.table().getColumns().stream().map(column -> column.getText()).toList())
                .containsExactly("名称", "行", "数据长度", "引擎",
                        "创建日期", "修改日期", "排序规则", "注释");
    }

    @Test
    void searchFiltersByTableName(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.table().getItems()).hasSize(3);

        robot.interact(() -> page.searchField().setText("use"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.table().getItems())
                .as("搜索框要真的过滤清单，而不是只改占位文字")
                .extracting(TableMeta::name)
                .containsExactly("users");
    }

    @Test
    void enterOpensTheSelectedTable(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            page.table().getSelectionModel().select(0);
            page.table().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ENTER, false, false, false, false));
        });

        assertThat(opened).containsExactly(page.table().getItems().get(0).name());
    }

    // ─────────────────────── N1 工具条 ───────────────────────

    @Test
    void toolbarOffersNewDropAndDesignAndFollowsSelection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(button("#table-list-new").isDisable())
                .as("新建表不依赖行选择")
                .isFalse();
        assertThat(button("#table-list-drop").isDisable())
                .as("没选行时删除必须禁用")
                .isTrue();
        assertThat(button("#table-list-design").isDisable()).isTrue();

        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(button("#table-list-design").isDisable()).isFalse();
        assertThat(button("#table-list-drop").isDisable()).isFalse();

        robot.interact(() -> button("#table-list-design").fire());
        assertThat(designed).containsExactly("users");
    }

    // ─────────────────────── N2 右键菜单 ───────────────────────

    @Test
    void contextMenuComesFromTheSameActionSet(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        ContextMenu menu = page.contextMenuForTest();
        List<String> ids = menu.getItems().stream()
                .map(MenuItem::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(ids)
                .as("右键菜单必须与工具条共用同一组动作")
                .containsExactly("table-list.open", "table-list.design", "table-list.new",
                        "table-list.rename", "table-list.truncate", "table-list.drop",
                        "table-list.refresh", "table-list.select-all");
    }

    // ─────────────────────── N3 多选批量删除 ───────────────────────

    @Test
    void multipleSelectionEnablesBatchDropAndKeepsSingleItemActionsOff(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().selectAll());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(button("#table-list-open").isDisable())
                .as("多选时「打开」不知道该打开哪一张")
                .isTrue();
        assertThat(button("#table-list-drop").isDisable()).isFalse();

        robot.interact(() -> button("#table-list-drop").fire());
        assertThat(dropped).hasSize(1);
        assertThat(dropped.getFirst())
                .as("批量删除必须带上全部选中项")
                .containsExactly("users", "logs", "audit");
    }

    // ─────────────────────── N4 视图切换 ───────────────────────

    @Test
    void gridViewShowsCardsAndRemembersSelection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.isGridView()).isFalse();

        robot.interact(() -> page.table().getSelectionModel().select(0));
        robot.interact(() -> page.showView("grid"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.isGridView()).isTrue();
        assertThat(page.lookup("#table-card-users"))
                .as("网格视图要出现卡片")
                .isNotNull();
        assertThat(page.lookup("#table-card-users").getStyleClass())
                .as("切到网格后原来的选择要保留")
                .contains("adb-table-card--selected");

        robot.interact(() -> page.showView("list"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.isGridView()).isFalse();
        assertThat(page.table().getSelectionModel().getSelectedItems())
                .extracting(TableMeta::name)
                .containsExactly("users");
    }

    @Test
    void gridViewSelectionDrivesTheStatusText(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            page.showView("grid");
            page.selectAll();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(selections).last().asString().isEqualTo("已选 3 个项目");
    }

    // ─────────────────────── N5 状态栏文案 ───────────────────────

    @Test
    void singleSelectionReportsNameAndRowCount(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(selections).last().asString()
                .as("单表要带表名与行数（N5）")
                .isEqualTo("已选 users · 200 行");

        robot.interact(() -> {
            page.table().getSelectionModel().clearSelection();
            page.table().getSelectionModel().select(2);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(selections).last().asString()
                .as("行数未知时不要编一个 0")
                .isEqualTo("已选 audit");
    }

    // ─────────────────────── §6.3 详情面板联动 ───────────────────────

    @Test
    void selectingATableFeedsTheDetailsPanel(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inspected)
                .as("单选一张表时要把表名报给右侧详情面板（规格 §6.3）")
                .containsExactly("users");

        robot.interact(() -> page.table().getSelectionModel().selectAll());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inspected)
                .as("多选 = 没有单一对象可看，要报 null 让面板回空态")
                .containsNull();
    }

    @Test
    void theSameSelectionIsNotReportedTwice(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();
        int afterFirst = inspected.size();

        // 标签切回来 / 刷新都会重跑 updateSelectionState()。
        robot.interact(() -> page.refreshSelectionStatus());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inspected).hasSize(afterFirst)
                .as("同一个表名反复上报 = 每次都触发一次元数据查询 + 一次面板重绘");
    }

    private Button button(String selector) {
        return (Button) page.lookup(selector);
    }
}
