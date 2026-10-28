package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 对象标题栏门禁（§6.1）与对象切面门禁（§6.2）。
 *
 * <p>这两个组件是 2026-10 重构新增的，门禁要钉住：
 * <ol>
 *   <li>标题栏显示的是**类型图标 + 名字 + 类型 + 行数**，行数拿不到时明说「行数未知」，
 *       绝不编一个数字；</li>
 *   <li>切面条能加、能选、能回调，当前项用 {@code adb-object-tab--active} 标出；</li>
 *   <li>动作按钮真的挂在标题栏上（不是建好了没加进去）。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class ObjectHeaderAndTabsTest {

    private ObjectHeader header;
    private ObjectTabBar tabs;
    /** 记录被选中的切面。 */
    private String selected = "";

    @Start
    void start(Stage stage) {
        header = new ObjectHeader(Icons.Icon.TABLE, "users", "表");
        tabs = new ObjectTabBar();
        tabs.addTab("data", "数据");
        tabs.addTab("columns", "结构");
        tabs.addTab("sql", "SQL");
        tabs.setOnSelect(id -> selected = id);

        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(header, tabs);
        stage.setScene(new Scene(root, 900, 400));
        stage.show();
    }

    @Test
    void theHeaderShowsIconNameAndType(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup(".adb-object-header__title").queryAs(Label.class).getText())
                .isEqualTo("users");
        assertThat(robot.lookup(".adb-object-header__type").queryAs(Label.class).getText())
                .isEqualTo("表");
        assertThat(robot.lookup(".adb-object-header__icon"))
                .as("类型图标必须是矢量图标")
                .isNotNull();
    }

    @Test
    void anUnknownRowCountIsSaysSoInsteadOfBeingFaked(FxRobot robot) {
        // ⭐ 编一个数字比不显示更糟 —— 用户会拿它做判断。
        robot.interact(() -> {
            header.setCount(null);
            assertThat(((Label) header.lookup(".adb-object-header__count")).getText())
                    .isEqualTo("行数未知");
            header.setCount("200 行");
            assertThat(((Label) header.lookup(".adb-object-header__count")).getText())
                    .isEqualTo("200 行");
        });
    }

    @Test
    void theRowCountCanBeHiddenForPagesThatHaveNoRows(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        header.hideCount();
        assertThat(robot.lookup(".adb-object-header__count").queryAs(Label.class).isVisible())
                .as("结构页没有「行数」这回事 —— 显示「行数未知」像坏了")
                .isFalse();
    }

    @Test
    void theTypeCanBeUpdatedAfterIntrospection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> header.setType("视图"));
        assertThat(robot.lookup(".adb-object-header__type").queryAs(Label.class).getText())
                .isEqualTo("视图");
    }

    @Test
    void actionButtonsAreReallyAttachedToTheHeader(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            Button refresh = new Button("刷新");
            refresh.setId("header-refresh");
            header.addAction(refresh);
        });
        assertThat(robot.lookup("#header-refresh").tryQuery())
                .as("动作按钮必须真的挂在标题栏上")
                .isPresent();
    }

    @Test
    void theTabBarMarksTheActiveSection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        tabs.setSelected("columns");
        assertThat(tabs.selected()).isEqualTo("columns");
        assertThat(robot.lookup("#object-tab-columns")
                .queryAs(Label.class).getStyleClass())
                .as("当前切面必须有明确的视觉标记")
                .contains("adb-object-tab--active");
        assertThat(robot.lookup("#object-tab-data")
                .queryAs(Label.class).getStyleClass())
                .doesNotContain("adb-object-tab--active");
    }

    @Test
    void clickingASectionFiresTheCallback(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn("#object-tab-sql");
        assertThat(selected).isEqualTo("sql");
        assertThat(tabs.selected()).isEqualTo("sql");
    }

    @Test
    void everySectionHasAnAccessibleName(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        for (String id : List.of("#object-tab-data", "#object-tab-columns", "#object-tab-sql")) {
            assertThat(robot.lookup(id).queryAs(Label.class).getAccessibleText())
                    .as(id + " 的 accessibleText")
                    .isNotBlank();
        }
    }

    /**
     * ⭐ 窄宽标题栏（1280 窗口 + 右侧详情抽屉）只允许辅助信息让位。
     *
     * <p>实测缺陷：抽屉一开，HBox 把对象名、类型、行数、七个切面一起按比例
     * 压成「u…」「20…」「触…」—— 主信息全废。规则：切面/类型/行数永不缩写，
     * 对象名保底，只有「连接名 · 读写」可以变省略号。
     */
    @Test
    void aNarrowHeaderAbbreviatesOnlyTheContextAndNeverTheTabsOrTitle(FxRobot robot) {
        ObjectHeader squeezed = new ObjectHeader(Icons.Icon.TABLE, "users", "表");
        squeezed.setCount("200 行");
        squeezed.setContext("agenticdb-demo.db · 可写");
        ObjectTabBar bar = new ObjectTabBar();
        for (String[] tab : new String[][]{
                {"data", "数据"}, {"overview", "概览"}, {"columns", "结构"},
                {"indexes", "索引"}, {"fks", "外键"}, {"triggers", "触发器"}, {"sql", "SQL"}}) {
            bar.addTab(tab[0], tab[1]);
        }
        squeezed.setTabs(bar);
        squeezed.addActions(new Button("刷新"), new Button("结构"));

        java.util.concurrent.atomic.AtomicReference<Stage> narrowRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        robot.interact(() -> {
            // ⚠️ 不能给**已显示的**主舞台换 Scene 再 sizeToScene：换 Scene 时场景会先被
            //    撑回原窗口的 900 宽，窄宽条件根本不成立（第一版就是假绿）。
            Stage narrow = new Stage();
            narrow.setScene(new Scene(new javafx.scene.layout.VBox(squeezed), 600, 200));
            narrow.show();
            narrowRef.set(narrow);
        });
        try {
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(narrowRef.get().getScene().getWidth())
                    .as("前置条件：场景真的被压到 600 宽以内")
                    .isLessThan(700);

            Label title = (Label) squeezed.lookup(".adb-object-header__title");
            assertThat(title.getWidth())
                    .as("对象名不能被压成「u…」")
                    .isGreaterThanOrEqualTo(title.prefWidth(-1) - 0.5);
            for (javafx.scene.Node tab : squeezed.lookupAll(".adb-object-tab")) {
                assertThat(tab.getLayoutBounds().getWidth())
                        .as("切面「" + ((Label) tab).getText() + "」不能被压缩成省略号")
                        .isGreaterThanOrEqualTo(tab.prefWidth(-1) - 0.5);
            }
            Label context = (Label) squeezed.lookup(".adb-object-header__context");
            assertThat(context.getWidth())
                    .as("让位的必须是辅助信息")
                    .isLessThan(context.prefWidth(-1));
        } finally {
            robot.interact(() -> narrowRef.get().close());
        }
    }
}
