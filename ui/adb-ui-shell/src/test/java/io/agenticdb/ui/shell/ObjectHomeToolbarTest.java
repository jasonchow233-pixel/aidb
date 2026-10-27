package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象首页的**对象级工具条**（第二十轮）。
 *
 * <p>Navicat 的对象清单顶部有一条对象级工具条（打开 / 设计 / 新建 / 删除 /
 * 刷新）。AIDB 的对象首页此前只有双击打开 —— 默认落地页上不能设计、不能
 * 删除、不能建表，必须先绕到表清单页或侧栏右键。
 *
 * <p>这里守住三件事：
 * <ol>
 *   <li>五个按钮**真的在场景图里**（本项目的老毛病：建了没挂）；</li>
 *   <li>启用态跟着**选择类型**走（选函数时「删除」不能亮）；</li>
 *   <li>点下去**真的到宿主**（不是只改了个状态）。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class ObjectHomeToolbarTest {

    @TempDir
    Path temp;

    private DbSession session;
    private ObjectHomePage page;
    private final List<String> opened = new ArrayList<>();
    private final List<String> designed = new ArrayList<>();
    private final List<String> dropped = new ArrayList<>();
    private final List<String> inspected = new ArrayList<>();
    private int newTableCalls;

    @BeforeEach
    void openDatabase() throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("toolbar.db"))));
    }

    @AfterEach
    void closeDatabase() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    @Start
    void start(Stage stage) {
        page = new ObjectHomePage(() -> session, new SchemaService(), Runnable::run,
                object -> opened.add(object.name()),
                message -> { },
                () -> { },
                new ObjectHomePage.Host() {
                    @Override
                    public void newTable() {
                        newTableCalls++;
                    }

                    @Override
                    public void design(SchemaService.SchemaObject object) {
                        designed.add(object.name());
                    }

                    @Override
                    public void dropTables(List<String> tables) {
                        dropped.addAll(tables);
                    }

                    @Override
                    public void inspect(SchemaService.SchemaObject object) {
                        inspected.add(object == null ? "<none>" : object.name());
                    }
                });
        stage.setScene(new Scene(page, 900, 600));
        stage.show();
    }

    @Test
    void toolbarIsActuallyInTheScene() {
        for (String id : List.of("object-home-toolbar", "object-home-open",
                "object-home-design", "object-home-new", "object-home-drop",
                "object-home-refresh")) {
            assertThat(page.lookup("#" + id))
                    .as("%s 必须在场景图里 —— 建了控件却没挂上去是旧毛病", id)
                    .isNotNull();
        }
        assertThat(page.lookup("#object-home-toolbar"))
                .isInstanceOf(javafx.scene.layout.HBox.class);
    }

    @Test
    void selectionDecidesWhichActionsAreAvailable(FxRobot robot) {
        render(robot);

        // 没选：打开 / 设计 / 删除都禁用，新建与刷新始终可用。
        assertThat(open().isDisabled()).as("空选时不能打开").isTrue();
        assertThat(design().isDisabled()).as("空选时不能设计").isTrue();
        assertThat(drop().isDisabled()).as("空选时不能删除").isTrue();
        assertThat(newButton().isDisabled()).as("新建表不依赖选择").isFalse();

        select(robot, "users");
        assertThat(open().isDisabled()).isFalse();
        assertThat(design().isDisabled()).as("表可以设计").isFalse();
        assertThat(drop().isDisabled()).as("表可以删除").isFalse();

        select(robot, "v_active_users");
        assertThat(design().isDisabled()).as("视图可以打开结构页（只读）").isFalse();
        assertThat(drop().isDisabled()).as("删除表动作不该删视图").isTrue();

        select(robot, "trg_users_audit");
        assertThat(open().isDisabled()).as("触发器可以在结构页附近看到").isFalse();
        assertThat(design().isDisabled()).as("触发器不属于结构页设计对象").isTrue();
        assertThat(drop().isDisabled()).as("触发器不能被删表动作误删").isTrue();
    }

    @Test
    void buttonsReachTheHost(FxRobot robot) {
        render(robot);

        robot.interact(() -> newButton().fire());
        assertThat(newTableCalls).as("新建表要真的交给宿主（不是只改按钮状态）").isEqualTo(1);

        select(robot, "users");
        robot.interact(() -> design().fire());
        assertThat(designed).containsExactly("users");

        robot.interact(() -> drop().fire());
        assertThat(dropped).containsExactly("users");

        robot.interact(() -> open().fire());
        assertThat(opened).containsExactly("users");
    }

    @Test
    void detailsPanelFollowsSelection(FxRobot robot) {
        render(robot);
        inspected.clear();

        select(robot, "users");
        assertThat(inspected).last().isEqualTo("users");

        robot.interact(() -> page.table().getSelectionModel().clearSelection());
        assertThat(inspected).last().isEqualTo("<none>");
    }

    /**
     * ⭐ UX-3：四颗主操作必须「图标 + 文字 + tooltip + accessibleText」四样齐全。
     *
     * <p>这一排里有一颗会删表 —— 纯图标意味着用户要先认图标、再确认自己没认错。
     */
    @Test
    void mainOperationsAreLabeledAndGrouped() {
        for (Button button : List.of(open(), design(), newButton(), drop())) {
            assertThat(button.getText()).as("%s 必须有文字", button.getId()).isNotBlank();
            assertThat(button.getGraphic()).as("%s 必须有图标", button.getId()).isNotNull();
            assertThat(button.getTooltip()).as("%s 必须有 tooltip", button.getId()).isNotNull();
            assertThat(button.getTooltip().getText()).isNotBlank();
            assertThat(button.getAccessibleText())
                    .as("%s 必须有 accessibleText", button.getId()).isNotBlank();
            assertThat(button.getStyleClass())
                    .as("%s 必须走统一的 adb-button 规格（以前四颗是裸 Modena 按钮）",
                            button.getId())
                    .contains("adb-button");
        }
        assertThat(drop().getStyleClass())
                .as("删表动作保持 danger 色")
                .contains("adb-button--danger");
        assertThat(page.lookupAll(".adb-toolbar__separator"))
                .as("看对象的一组、改对象的一组，中间要有竖线")
                .isNotEmpty();
    }

    private void render(FxRobot robot) {
        robot.interact(() -> page.render(List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE),
                new SchemaService.SchemaObject("v_active_users", SchemaService.ObjectKind.VIEW),
                new SchemaService.SchemaObject("trg_users_audit",
                        SchemaService.ObjectKind.TRIGGER))));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void select(FxRobot robot, String name) {
        robot.interact(() -> {
            for (int i = 0; i < page.table().getItems().size(); i++) {
                if (page.table().getItems().get(i).object().name().equals(name)) {
                    page.table().getSelectionModel().clearAndSelect(i);
                    return;
                }
            }
            throw new AssertionError("清单里没有 " + name);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Button open() {
        return (Button) page.lookup("#object-home-open");
    }

    private Button design() {
        return (Button) page.lookup("#object-home-design");
    }

    private Button newButton() {
        return (Button) page.lookup("#object-home-new");
    }

    private Button drop() {
        return (Button) page.lookup("#object-home-drop");
    }
}
