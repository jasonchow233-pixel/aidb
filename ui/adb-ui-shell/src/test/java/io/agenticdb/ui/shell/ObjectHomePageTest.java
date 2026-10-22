package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象首页的**呈现门禁 + 对象分派门禁**。
 *
 * <p>本类补的是截图审计（{@code docs/UI-AUDIT.md} §2 / §7）暴露的两类漏洞：
 *
 * <ol>
 *   <li><b>控件被创建了、事件也绑了，但没有加入场景图</b> —— 行为测试发现不了，
 *       因为它根本不渲染；443 条门禁全绿而界面上没有那个按钮（S2）。</li>
 *   <li><b>点「函数 / 过程 / 触发器」必然报错</b> —— 对象页只把名字传给外壳，
 *       外壳无条件开数据页，于是去 SELECT 一张根本不存在的表（D1）。</li>
 * </ol>
 *
 * <p>这两类都不是"逻辑算错了"，而是"做出来的东西和用户看到的不一致"。
 * 所以这里既断言"它在界面上"，也断言"点它传出去的东西是对的"。
 */
@ExtendWith(ApplicationExtension.class)
class ObjectHomePageTest {

    private ObjectHomePage page;
    /** 记录 onOpen 收到的东西 —— 用来断言 kind 没有被丢掉。 */
    private final List<SchemaService.SchemaObject> opened = new ArrayList<>();

    @Start
    void start(Stage stage) {
        // session 恒为 null → reload() 走「未连接」分支，不触碰任何数据库。
        page = new ObjectHomePage(() -> null, null, Runnable::run,
                opened::add, message -> { }, () -> { });
        page.reload();
        stage.setScene(new Scene(page, 900, 600));
        stage.show();
    }

    @Test
    void refreshButtonIsActuallyInTheScene() {
        assertThat(page.lookup("#object-home-refresh"))
                .as("刷新按钮必须真的挂在场景图里 —— "
                        + "它曾经只被 new 出来、绑了动作、设了 id，却从未加入 header（UI-AUDIT S2）")
                .isNotNull();
    }

    @Test
    void disconnectionStateIsNotABlankPage() {
        // 无连接时至少要有一句可读的提示，而不是空白区域。
        assertThat(page.lookup("#object-home-summary"))
                .as("未连接时要给出可见提示")
                .isNotNull();
        // ⭐ X2：未连接空态必须带「新建连接」这个出口，而不只是陈述事实。
        assertThat(page.lookupAll(".adb-empty")).as("未连接要显示空态卡片").isNotEmpty();
    }

    @Test
    void rowPassesTheWholeObjectSoCallerCanDispatchOnKind(FxRobot robot) {
        robot.interact(() -> page.render(List.of(
                new SchemaService.SchemaObject("total_amount",
                        SchemaService.ObjectKind.FUNCTION),
                new SchemaService.SchemaObject("users",
                        SchemaService.ObjectKind.TABLE))));
        WaitForAsyncUtils.waitForFxEvents();

        AtomicReference<TableView<ObjectHomePage.ObjectRow>> list = new AtomicReference<>();
        AtomicReference<Object> row = new AtomicReference<>();
        robot.interact(() -> {
            @SuppressWarnings("unchecked")
            TableView<ObjectHomePage.ObjectRow> found =
                    (TableView<ObjectHomePage.ObjectRow>) page.lookup("#object-list");
            list.set(found);
            // ⭐ 呈现门禁：清单必须真的渲染出来了，而且每行能按 id 定位。
            //    对象首页曾经是「一堵 chip 墙」—— 现在改成带元数据的清单，
            //    类型分派语义不变（D1），但呈现方式变了，所以门禁也要跟着盯呈现。
            row.set(page.lookup("#object-row-function-total_amount"));
        });
        assertThat(list.get()).as("对象清单（TableView）要挂在场景图里").isNotNull();
        assertThat(row.get())
                .as("函数那一行要能被按 id 找到 —— 否则没法验证点它的行为")
                .isNotNull();

        // 选中函数行 + 回车 → 等于用户双击打开
        robot.interact(() -> {
            TableView<ObjectHomePage.ObjectRow> table = list.get();
            int index = -1;
            for (int i = 0; i < table.getItems().size(); i++) {
                if (table.getItems().get(i).object().kind()
                        == SchemaService.ObjectKind.FUNCTION) {
                    index = i;
                }
            }
            assertThat(index).as("清单里要有函数那一行").isNotNegative();
            table.getSelectionModel().select(index);
            table.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ENTER, false, false, false, false));
        });

        assertThat(opened).hasSize(1);
        // ⭐ 关键：传出去的是整个对象，kind 还在。
        //    以前只传 String name，外壳无从判断它是不是一张表（UI-AUDIT D1）。
        assertThat(opened.get(0).kind()).isEqualTo(SchemaService.ObjectKind.FUNCTION);
        assertThat(opened.get(0).name()).isEqualTo("total_amount");
    }

    @Test
    void theListShowsTableMetadataNotJustNames(FxRobot robot) {
        robot.interact(() -> page.render(
                List.of(new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE)),
                List.of(new TableMeta("users", 200L, 3 * 1024L * 1024L, "InnoDB",
                        "2026-01-01 09:00:00"))));
        WaitForAsyncUtils.waitForFxEvents();

        AtomicReference<List<String>> texts = new AtomicReference<>();
        robot.interact(() -> {
            @SuppressWarnings("unchecked")
            TableView<ObjectHomePage.ObjectRow> table =
                    (TableView<ObjectHomePage.ObjectRow>) page.lookup("#object-list");
            texts.set(table.getColumns().stream()
                    .map(column -> column.getText())
                    .toList());
        });
        // ⭐ 这是与 Navicat 的最大呈现差距：清单要能回答「这张表多大、多少人用过」，
        //    而不是只有名字。列集合就是这条契约 —— 与 Navicat 表清单的 8 列对齐
        //    （名称 / 类型 / 行 / 数据长度 / 引擎 / 创建时间 / 修改时间 / 排序规则 / 注释）。
        assertThat(texts.get())
                .containsExactly("名称", "类型", "行数", "数据长度", "引擎", "创建时间",
                        "修改时间", "排序规则", "注释");
    }

    @Test
    void densityChangesTheObjectListRowHeight(FxRobot robot) {
        robot.interact(() -> {
            page.setDensity("comfort");
            double comfort = page.table().getFixedCellSize();
            page.setDensity("compact");
            double compact = page.table().getFixedCellSize();
            assertThat(compact)
                    .as("对象清单的行高要跟密度走（紧凑 < 宽松）")
                    .isLessThan(comfort);
            assertThat(compact).isEqualTo(io.agenticdb.ui.RowHeights.of("compact"));
        });
    }

    @Test
    void onlyTablesAndViewsAreBrowsableAsRows() {
        assertThat(ObjectHomePage.unsupportedReason(SchemaService.ObjectKind.TABLE, "users"))
                .isNull();
        assertThat(ObjectHomePage.unsupportedReason(SchemaService.ObjectKind.VIEW, "v_users"))
                .isNull();

        // ⭐ 函数 / 过程：O2 之后**能看定义体**了 —— 但**仍然不能当表浏览**。
        //    「能开定义体页」与「能开数据页」是两件事，这里守住后者。
        for (SchemaService.ObjectKind kind : List.of(
                SchemaService.ObjectKind.FUNCTION,
                SchemaService.ObjectKind.PROCEDURE)) {
            assertThat(ObjectHomePage.unsupportedReason(kind, "x"))
                    .as(kind + " 不该被当成表去 SELECT（那会报「表不存在」）")
                    .isNull();
        }

        // 触发器仍然不在这条路径上（去结构页的「触发器」页签看）。
        assertThat(ObjectHomePage.unsupportedReason(SchemaService.ObjectKind.TRIGGER, "x"))
                .as("触发器要给出「去哪里看」的指引")
                .isNotNull()
                .contains("结构页");
    }

    @Test
    void emptyConnectionShowsAnEmptyStateNotJustWords(FxRobot robot) {
        AtomicReference<java.util.Set<javafx.scene.Node>> empties = new AtomicReference<>();
        robot.interact(() -> {
            page.render(List.of());
            empties.set(page.lookupAll(".adb-empty"));
        });
        assertThat(empties.get())
                .as("连上了但一个对象都没有时，也要有图标+说明+出口")
                .isNotEmpty();
    }
}
