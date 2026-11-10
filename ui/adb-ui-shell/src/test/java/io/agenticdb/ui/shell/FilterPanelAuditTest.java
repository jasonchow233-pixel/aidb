package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ N31 的门禁：网格内嵌 Filter & Sort 生成器。
 *
 * <p>钉住三件 Navicat §1.9 明确要求、且旧「弹窗构造器」做不到的事：
 * <ol>
 *   <li>工具条按钮的按下态 = 面板开合，面板在工具带与网格之间占高度；</li>
 *   <li>{@code Criteria Edited} 只在条件被改过后亮，按 Apply 之前**不执行查询**；</li>
 *   <li>条件行之间是固定的 {@code and} 连接词，排序是 Sort By 芯片，Apply 后
 *       才变成 chip 条与表头徽标。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class FilterPanelAuditTest {

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-filter-panel");
            thread.setDaemon(true);
            return thread;
        });
        page = new DataGridPage(session, new SchemaService(), "t", "test.db",
                noOpFeedback(), name -> { }, executor);
        Scene scene = new Scene(new StackPane(page), 1280, 700);
        scene.getStylesheets().add(getClass()
                .getResource("/io/agenticdb/ui/theme-light.css").toExternalForm());
        scene.getStylesheets().add(getClass()
                .getResource("/io/agenticdb/ui/app.css").toExternalForm());
        page.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void awaitLoaded() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> rowCount() == 3);
        WaitForAsyncUtils.waitForFxEvents(10);
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
    void theToolbarToggleOpensAndClosesTheInlinePanel() throws Exception {
        assertThat(hostVisible()).as("面板默认不占高度").isFalse();

        click("#data-filter");
        assertThat(hostVisible()).as("按下 Filter & Sort 后面板出现并占高度").isTrue();
        assertThat(selected("#data-filter")).as("按钮保持按下态").isTrue();
        assertThat(visible("#data-filter-panel")).as("面板本体可见").isTrue();

        click("#data-filter");
        assertThat(hostVisible()).isFalse();
        assertThat(selected("#data-filter")).isFalse();
    }

    @Test
    void criteriaEditedAppearsAfterAChangeAndApplyDefersTheQuery() throws Exception {
        click("#data-filter");
        assertThat(dirtyVisible()).as("刚打开、草稿 == 已应用条件，没有脏标记").isFalse();

        setFirstValue("2");
        assertThat(dirtyVisible()).as("改了条件才亮 Criteria Edited").isTrue();
        assertThat(rowCount()).as("⭐ Apply 之前不执行查询").isEqualTo(3);

        click("#data-filter-apply");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> rowCount() == 1);
        WaitForAsyncUtils.waitForFxEvents(10);

        assertThat(dirtyVisible()).as("应用后草稿归零，脏标记熄灭").isFalse();
        assertThat(WaitForAsyncUtils.asyncFx(() ->
                page.lookupAll(".adb-chip--filter").size()).get())
                .as("条件落成 chip 条").isEqualTo(1);
    }

    @Test
    void conditionsJoinWithAndAndSortsBecomeChips() throws Exception {
        click("#data-filter");
        assertThat(rowsInPanel()).isEqualTo(1);

        click("#data-filter-add");
        assertThat(rowsInPanel()).as("+ 加一行条件").isEqualTo(2);
        assertThat(connectors()).as("行间连接词固定 and（模型只有平铺 AND）")
                .contains(I18n.t("filter.panel.and"));

        click("#data-filter-add-sort");
        assertThat(WaitForAsyncUtils.asyncFx(() ->
                page.lookupAll(".adb-filter-builder__sort-chip").size()).get())
                .as("Sort By 芯片").isEqualTo(1);
        assertThat(dirtyVisible()).as("加排序键也是条件变更").isTrue();

        WaitForAsyncUtils.asyncFx(() -> {
            ((RadioButton) page.lookup("#data-filter-mode-text")).setSelected(true);
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(visible("#data-filter-builder")).as("Text 模式收起生成器").isFalse();
        String expression = WaitForAsyncUtils.asyncFx(() ->
                ((TextArea) page.lookup("#data-filter-text")).getText()).get();
        assertThat(expression).as("Text 模式是生成出来的表达式，含列名与排序")
                .contains("id").contains("ORDER BY");

        click("#data-filter-apply");
        WaitForAsyncUtils.waitForFxEvents(20);
        assertThat(WaitForAsyncUtils.asyncFx(() ->
                page.lookupAll(".adb-chip--sort").size()).get())
                .as("应用后排序键落成排序 chip").isEqualTo(1);
        assertThat(WaitForAsyncUtils.asyncFx(() ->
                page.lookupAll(".adb-grid__sort-badge").stream()
                        .filter(node -> node.isVisible()).count()).get())
                .as("表头排序徽标跟着亮").isEqualTo(1);
    }

    // ───────────────── fixtures ─────────────────

    /**
     * ⭐ B1：Ω（添加条件组）现在真正新增一个 OR 组，不再是「如实说明页」占位。
     * 端到端验证：组 0 设 id=2，Ω 加组 1 设 id=3，Apply 后命中两行（{@code id=2 OR id=3}），
     * 表达式里出现 {@code OR} —— 证明组间真的是 OR 而不是 AND。
     */
    @Test
    void orGroupsActuallyCombineConditionsWithOr() throws Exception {
        click("#data-filter");
        assertThat(hostVisible()).as("面板先开起来").isTrue();

        setFirstValue("2");                 // 组 0：id = 2
        click("#data-filter-add-group");    // 真实新增 OR 组
        assertThat(groupExists(1)).as("Ω 真正新增了一个 OR 组容器").isTrue();

        click("#data-filter-group-add-1");  // 给组 1 加一行
        setGroupValue(1, "3");              // 组 1：id = 3（与组 0 用 OR 连）

        switchToText();
        assertThat(expressionText())
                .as("表达式里出现 OR 连接两组").contains(" OR ");
        switchToBuilder();

        click("#data-filter-apply");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> rowCount() == 2);
        assertThat(rowCount()).as("id=2 OR id=3 命中两行").isEqualTo(2);
    }

    private void click(String selector) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            ((ButtonBase) page.lookup(selector)).fire();
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents(20);
    }

    private void setFirstValue(String text) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            TextField value = (TextField) page.lookupAll(".adb-filter-builder__value")
                    .stream().filter(node -> node.isVisible() && !node.isDisabled())
                    .findFirst().orElseThrow();
            value.setText(text);
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean hostVisible() throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                page.lookup("#data-filter-panel-host").isVisible()
                        && page.lookup("#data-filter-panel-host").isManaged()).get();
    }

    private boolean selected(String selector) throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                ((javafx.scene.control.ToggleButton) page.lookup(selector)).isSelected()).get();
    }

    private boolean visible(String selector) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> page.lookup(selector).isVisible()).get();
    }

    private boolean dirtyVisible() throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> {
            Label dirty = (Label) page.lookup("#data-filter-dirty");
            return dirty.isVisible() && dirty.isManaged();
        }).get();
    }

    private int rowsInPanel() throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                page.lookupAll(".adb-filter-builder__row").size()).get();
    }

    private List<String> connectors() throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> {
            List<String> labels = new ArrayList<>();
            for (javafx.scene.Node node : ((javafx.scene.Parent)
                    page.lookup("#data-filter-rows")).getChildrenUnmodifiable()) {
                if (node instanceof Label label && label.isVisible()) {
                    labels.add(label.getText());
                }
            }
            return labels;
        }).get();
    }

    private boolean groupExists(int index) throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                page.lookup("#data-filter-group-" + index) != null).get();
    }

    private void setGroupValue(int groupIndex, String text) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            javafx.scene.Node group = page.lookup("#data-filter-group-" + groupIndex);
            TextField value = (TextField) group.lookupAll(".adb-filter-builder__value").stream()
                    .filter(node -> node.isVisible() && !node.isDisabled())
                    .findFirst().orElseThrow();
            value.setText(text);
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void switchToText() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            ((RadioButton) page.lookup("#data-filter-mode-text")).setSelected(true);
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void switchToBuilder() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            ((RadioButton) page.lookup("#data-filter-mode-builder")).setSelected(true);
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String expressionText() throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                ((TextArea) page.lookup("#data-filter-text")).getText()).get();
    }

    private int rowCount() throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                ((TableView<?>) page.lookup("#data-table")).getItems().size()).get();
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
