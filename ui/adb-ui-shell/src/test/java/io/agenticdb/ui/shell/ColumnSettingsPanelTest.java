package io.agenticdb.ui.shell;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.ui.I18n;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
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
 * ⭐ 列设置弹层门禁（§6.5）。
 *
 * <p>列设置要能：显示/隐藏列、恢复默认、**按字段名搜索列**。
 * 搜索这一条以前完全没有 —— 表有 40 列时，一串勾选项的菜单没法用。
 *
 * <p>门禁断言：搜索真的过滤、勾选真的回调、计数真的更新。
 */
@ExtendWith(ApplicationExtension.class)
class ColumnSettingsPanelTest {

    private ColumnSettingsPanel panel;
    private final List<String> toggled = new ArrayList<>();
    private int showAllClicks;
    private int resetClicks;

    @Start
    void start(Stage stage) {
        List<ColumnDef> columns = List.of(
                column("id"), column("user_id"), column("user_name"),
                column("created_at"), column("updated_at"));

        panel = new ColumnSettingsPanel(columns,
                (definition, visible) -> toggled.add(definition.name() + "=" + visible),
                definition -> !"hidden_col".equals(definition.name()),
                () -> showAllClicks++,
                () -> resetClicks++);

        stage.setScene(new javafx.scene.Scene(panel, 400, 500));
        stage.show();
    }

    private static ColumnDef column(String name) {
        return new ColumnDef(name, ColumnType.TEXT, true, false, null, null, null,
                null, null, null, false, "TEXT");
    }

    @Test
    void everyColumnIsListedByDefault(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#columns-count").queryAs(javafx.scene.control.Label.class)
                .getText())
                .isEqualTo(I18n.t("colset.count", 5, 5));
    }

    @Test
    void searchingFiltersTheList(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> ((TextField) panel.lookup("#columns-search")).setText("user"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup("#columns-count").queryAs(javafx.scene.control.Label.class)
                .getText())
                .as("搜索必须真的过滤列表")
                .isEqualTo(I18n.t("colset.count", 2, 5));
    }

    @Test
    void clearingTheSearchRestoresEveryColumn(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> ((TextField) panel.lookup("#columns-search")).setText("user"));
        robot.interact(() -> ((TextField) panel.lookup("#columns-search")).clear());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup("#columns-count").queryAs(javafx.scene.control.Label.class)
                .getText())
                .isEqualTo(I18n.t("colset.count", 5, 5));
    }

    @Test
    void togglingAColumnFiresTheCallback(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        java.util.Set<CheckBox> boxes = robot.lookup("#columns-toggle").queryAll();
        assertThat(boxes).isNotEmpty();
        robot.interact(() -> {
            CheckBox box = boxes.iterator().next();
            box.setSelected(!box.isSelected());
            box.fire();
        });
        assertThat(toggled)
                .as("勾选必须真的回调出去（否则界面在骗人）")
                .hasSize(1);
        assertThat(toggled.getFirst()).contains("=");
    }

    @Test
    void showAllAndResetAreRealActions(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn("#columns-show-all");
        robot.clickOn("#columns-reset");
        assertThat(showAllClicks).isEqualTo(1);
        assertThat(resetClicks).isEqualTo(1);
    }

    @Test
    void thePanelIsCompactEnoughForAToolbarPopup(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(panel.getPrefWidth())
                .as("列设置是工具栏弹层 —— 太宽会盖住表格")
                .isLessThanOrEqualTo(320);
    }
}
