package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import javafx.application.Platform;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务书 §六「新建索引弹窗」的门禁。
 *
 * <p>钉住四件最容易回退的事：
 * <ol>
 *   <li><b>计数与顺序来自真实数据</b> —— 「可用列（N）」写死、或加列后不重算，
 *       用户就不知道还剩什么可选；复合索引的列顺序一变，SQL 必须跟着变；</li>
 *   <li><b>预览 == 执行</b> —— 预览和真正下发的语句必须出自
 *       {@code StructureSqlBuilder} 同一处，且跟着名称 / 唯一 / 类型 / 顺序 / 方向同步；</li>
 *   <li><b>不虚构能力</b> —— 索引类型与每列 {@code ASC / DESC} 只在方言支持时出现，
 *       SQLite / MySQL 上必须一个都不摆（摆一个选了不生效的控件就是 §6.5.6 的假成功）；</li>
 *   <li><b>没选中就禁用</b> —— 添加 / 移除 / 上移 / 下移的可用性跟着选择走，
 *       而不是「点了没反应」。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class IndexDialogTest {

    private static Stage stage;

    @Start
    void start(Stage primaryStage) {
        stage = primaryStage;
        StackPane root = new StackPane();
        root.getStyleClass().add("theme-light");
        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.getStylesheets().add(resource("/io/agenticdb/ui/theme-light.css"));
        scene.getStylesheets().add(resource("/io/agenticdb/ui/app.css"));
        stage.setScene(scene);
        stage.show();
    }

    private static String resource(String path) {
        java.net.URL url = IndexDialogTest.class.getResource(path);
        assertThat(url).as("样式资源 %s", path).isNotNull();
        return url.toExternalForm();
    }

    // ─────────────────────────── A / C 区：上下文与计数 ───────────────────────────

    @Test
    @DisplayName("标题栏的表名与方言来自真实上下文，不是写死的示例")
    void headerCarriesTheRealTableAndDialect() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        assertThat(text(dialog, ".adb-dialog__context")).contains("users").contains("SQLite");
        assertThat(text(dialog, ".adb-dialog__title")).isEqualTo("新建索引");
    }

    @Test
    @DisplayName("可用列 / 索引列的计数跟着真实列表走，加一列就减一列")
    void listCountsComeFromTheRealData() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        assertThat(dialog.availableHeader().getText()).isEqualTo("可用列（4）");
        assertThat(dialog.chosenHeader().getText()).isEqualTo("索引列（0）");

        selectAvailable(dialog, "email");
        fire(dialog.addButton());

        assertThat(dialog.availableHeader().getText()).isEqualTo("可用列（3）");
        assertThat(dialog.chosenHeader().getText()).isEqualTo("索引列（1）");
    }

    // ─────────────────────────── §6.5：添加 / 移除 / 上下移 ───────────────────────────

    @Test
    @DisplayName("没选中任何列时四个动作按钮全部禁用（点了没反应是最糟的反馈）")
    void actionsAreDisabledUntilThereIsSomethingToDo() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        assertThat(dialog.addButton().isDisabled()).isTrue();
        assertThat(dialog.removeButton().isDisabled()).isTrue();
        assertThat(dialog.upButton().isDisabled()).isTrue();
        assertThat(dialog.downButton().isDisabled()).isTrue();

        selectAvailable(dialog, "email");
        assertThat(dialog.addButton().isDisabled()).isFalse();

        fire(dialog.addButton());
        // 第一行不能更靠上，最后一行不能更靠下。
        assertThat(dialog.removeButton().isDisabled()).isFalse();
        assertThat(dialog.upButton().isDisabled()).isTrue();
        assertThat(dialog.downButton().isDisabled()).isTrue();
    }

    @Test
    @DisplayName("移除后列回到可用列，且可用列保持表的原始顺序")
    void removingReturnsTheColumnToTheAvailableList() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        selectAvailable(dialog, "email");
        fire(dialog.addButton());
        assertThat(dialog.availableColumns()).doesNotContain("email");

        fire(dialog.removeButton());

        assertThat(dialog.availableColumns()).containsExactly("id", "email", "first", "last");
        assertThat(dialog.chosenColumns()).isEmpty();
    }

    @Test
    @DisplayName("复合索引：列顺序就是 SQL 里的顺序，上下移必须同步改预览")
    void compositeColumnOrderDrivesTheSql() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        onFxRun(() -> dialog.nameField().setText("idx_names"));
        selectAvailable(dialog, "first");
        fire(dialog.addButton());
        selectAvailable(dialog, "last");
        fire(dialog.addButton());
        assertThat(dialog.previewText()).endsWith("(\"first\", \"last\")");

        // 「last」现在是第 2 行，上移一位 → 它成为最左前缀。
        dialog.chosenListView().getSelectionModel().select(1);
        fire(dialog.upButton());

        assertThat(dialog.previewText()).endsWith("(\"last\", \"first\")");
    }

    // ─────────────────────────── §6.6：预览与执行同源 ───────────────────────────

    @Test
    @DisplayName("预览跟着名称、唯一、列三者实时同步，且用方言的引号")
    void previewTracksNameUniqueAndColumns() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.MYSQL), table(List.of()));

        assertThat(dialog.previewText()).isEmpty();

        onFxRun(() -> dialog.nameField().setText("idx_email"));
        assertThat(dialog.previewText()).as("还没选列 → 没有可执行的语句").isEmpty();

        selectAvailable(dialog, "email");
        fire(dialog.addButton());
        assertThat(dialog.previewText())
                .isEqualTo("CREATE INDEX `idx_email` ON `users` (`email`)");

        onFxRun(() -> dialog.uniqueBox().setSelected(true));
        assertThat(dialog.previewText()).startsWith("CREATE UNIQUE INDEX ");
    }

    @Test
    @DisplayName("SQL 预览是真编辑器（等宽 / 行号），可折叠")
    void sqlPreviewIsACodeAreaAndCanBeCollapsed() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        Node area = find(dialog, "#index-dialog-preview");
        assertThat(area).isNotNull();
        assertThat(area).isInstanceOf(org.fxmisc.richtext.CodeArea.class);
        assertThat(dialog.isSqlPreviewVisible()).isTrue();

        fire((Button) find(dialog, "#index-dialog-collapse-sql"));

        assertThat(dialog.isSqlPreviewVisible()).isFalse();
        // ⭐ 折叠收的是整个编辑器（行号 + 文本），CodeArea 只是它的孩子。
        assertThat(onFx(() -> area.getParent().isManaged())).isFalse();
    }

    // ─────────────────────────── 能力边界：不许虚构 ───────────────────────────

    @Test
    @DisplayName("SQLite / MySQL 不摆索引类型下拉，也永不下发 DESC")
    void dialectsWithoutTheCapabilityGetNoControls() {
        for (DialectKind kind : List.of(DialectKind.SQLITE, DialectKind.MYSQL)) {
            IndexDialog dialog = mounted(FakeDbSession.of(kind), table(List.of()));

            assertThat(dialog.typeBox().getItems()).as(kind + " 没有可选索引类型").isEmpty();
            assertThat(find(dialog, "#index-dialog-type"))
                    .as(kind + " 连那一行都不该存在").isNull();

            onFxRun(() -> dialog.nameField().setText("idx_email"));
            selectAvailable(dialog, "email");
            fire(dialog.addButton());

            assertThat(dialog.previewText()).doesNotContain("USING").doesNotContain("DESC");
            assertThat(cellDirection(dialog)).as(kind + " 不摆方向下拉").isNull();
        }
    }

    @Test
    @DisplayName("PostgreSQL 的类型与降序是真能力：选了就必须进 SQL")
    void postgresOffersTypesAndColumnDirection() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.POSTGRESQL), table(List.of()));

        assertThat(dialog.typeBox().getItems()).contains("BTREE", "HASH", "GIN");
        onFxRun(() -> dialog.nameField().setText("idx_email"));
        selectAvailable(dialog, "email");
        fire(dialog.addButton());
        onFxRun(() -> dialog.typeBox().setValue("HASH"));
        assertThat(dialog.previewText()).isEqualTo(
                "CREATE INDEX \"idx_email\" ON \"users\" USING hash (\"email\")");

        ComboBox<String> direction = cellDirection(dialog);
        assertThat(direction).isNotNull();
        onFxRun(() -> direction.getSelectionModel().select(1));

        assertThat(dialog.chosenColumns().getFirst().descending()).isTrue();
        assertThat(dialog.previewText()).endsWith("(\"email\" DESC)");
    }

    // ─────────────────────────── 校验与 E 区 ───────────────────────────

    @Test
    @DisplayName("重名索引被挡住，且说清为什么")
    void duplicateIndexNameIsRefusedWithAReason() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE),
                table(List.of(new IndexDef("idx_email", false, List.of("email"), ""))));
        onFxRun(() -> dialog.nameField().setText("idx_email"));
        selectAvailable(dialog, "email");

        assertThat(dialog.problem()).contains("已经有一个叫「idx_email」的索引");

        fire(dialog.addButton());
        onFxRun(() -> dialog.nameField().setText("2bad"));
        assertThat(dialog.problem()).contains("不能以数字开头");

        onFxRun(() -> dialog.nameField().setText(""));
        assertThat(dialog.problem()).isEqualTo("请填索引名");
    }

    @Test
    @DisplayName("底部是取消 + 确定，取消挂 CANCEL_CLOSE（Escape 与关窗同一条出口）")
    void footerKeepsTheAppWideButtonContract() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        DialogPane pane = dialog.dialogPane();

        assertThat(pane.getButtonTypes()).hasSize(2);
        assertThat(buttonData(pane, "确定").isDefaultButton()).isTrue();
        assertThat(buttonData(pane, "取消").isCancelButton()).isTrue();
    }

    @Test
    @DisplayName("宽度走 PICKER 档、高度落在 §6.1 的 560–620 区间")
    void dialogUsesThePickerSizeTier() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        assertThat(dialog.dialogPane().getPrefWidth()).isEqualTo(DialogSizes.PICKER);
        assertThat(dialog.dialogPane().getPrefHeight()).isBetween(560.0, 620.0);
        assertThat(dialog.dialogPane().getMinHeight()).isLessThanOrEqualTo(560.0);
    }

    @Test
    @DisplayName("截图摆态走的是点「添加」同一条路：真实前两列 + 名字 + 预览一起跟上")
    void poseForShotsFillsThePickerThroughTheClickPath() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        onFxRun(dialog::poseForShots);

        assertThat(dialog.chosenColumns()).extracting(IndexDialog.PickedColumn::name)
                .as("加的是这张表真实的前两列，不是写死的示例列名")
                .containsExactly("id", "email");
        assertThat(dialog.availableColumns()).containsExactly("first", "last");
        assertThat(dialog.previewText())
                .isEqualTo("CREATE INDEX \"idx_users_id\" ON \"users\" (\"id\", \"email\")");
        // 摆完得是「点得动确定」的态 —— 否则这一帧拍的还是空弹窗。
        assertThat(dialog.problem()).isNull();
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 建好弹窗并挂进测试场景 —— 列表单元格只有在图上才会生成，方向下拉要能查。 */
    private static IndexDialog mounted(FakeDbSession session, TableStructure table) {
        AtomicReference<IndexDialog> created = new AtomicReference<>();
        onFxRun(() -> {
            IndexDialog dialog = IndexDialog.create(session, table);
            StackPane root = (StackPane) stage.getScene().getRoot();
            root.getChildren().setAll(dialog.content());
            created.set(dialog);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return created.get();
    }

    private static void selectAvailable(IndexDialog dialog, String column) {
        onFxRun(() -> dialog.availableListView().getSelectionModel()
                .select(dialog.availableColumns().indexOf(column)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void fire(Button button) {
        onFxRun(button::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 已选列第一行里的方向下拉；方言不支持时为 {@code null}。 */
    @SuppressWarnings("unchecked")
    private static ComboBox<String> cellDirection(IndexDialog dialog) {
        return (ComboBox<String>) onFx(() -> dialog.chosenListView().lookup(".combo-box"));
    }

    private static javafx.scene.control.ButtonBar.ButtonData buttonData(
            DialogPane pane, String text) {
        return pane.getButtonTypes().stream()
                .filter(type -> text.equals(type.getText()))
                .map(type -> type.getButtonData())
                .findFirst().orElseThrow();
    }

    private static String text(IndexDialog dialog, String selector) {
        return onFx(() -> {
            Node found = dialog.content().lookup(selector);
            return found == null ? null
                    : ((javafx.scene.control.Labeled) found).getText();
        });
    }

    private static Node find(IndexDialog dialog, String selector) {
        return onFx(() -> dialog.content().lookup(selector));
    }

    private static TableStructure table(List<IndexDef> indexes) {
        return new TableStructure("users", false, List.of(
                column("id"), column("email"), column("first"), column("last")),
                List.of("id"), indexes, List.of(), List.of(), "");
    }

    private static ColumnDef column(String name) {
        return new ColumnDef(name, ColumnType.TEXT, true, false, null, null,
                null, null, null, null, false);
    }

    private static <T> T onFx(Callable<T> action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<T> value = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                value.set(action.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        if (failure.get() != null) {
            rethrow(failure.get());
        }
        return value.get();
    }

    private static void onFxRun(Runnable action) {
        onFx(() -> {
            action.run();
            return null;
        });
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(throwable);
    }
}
