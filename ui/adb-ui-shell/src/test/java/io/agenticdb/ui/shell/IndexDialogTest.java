package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
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
 *   <li><b>计数与顺序来自真实数据</b> —— 「添加列（N）」写死、或加列后不重算，
 *       用户就不知道还剩什么可选；复合索引的列顺序一变，SQL 必须跟着变；</li>
 *   <li><b>预览 == 执行</b> —— 预览和真正下发的语句必须出自
 *       {@code StructureSqlBuilder} 同一处，且跟着名称 / 唯一 / 类型 / 顺序 / 方向同步；</li>
 *   <li><b>不虚构能力</b> —— 索引类型与每列 {@code ASC / DESC} 只在方言支持时出现，
 *       SQLite / MySQL 上必须一个都不摆（摆一个选了不生效的控件就是 §6.5.6 的假成功）；</li>
 *   <li><b>没得选就禁用</b>（U21 的单列表形态）—— 可用列加完后「添加列」禁用；
 *       移除只有行尾 × 与 {@code Delete} 两条路，且都必须真的回到可用列。</li>
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
    @DisplayName("标题栏的表名与方言来自真实上下文，不是写死的示例（N26：走系统标题栏）")
    void headerCarriesTheRealTableAndDialect() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        assertThat(dialog.dialogTitle())
                .as("标题串 = 「新建索引」+ 表名 + 方言 / 库（系统标题条没有 tooltip，一次说清）")
                .startsWith(I18n.t("structure.index.add"))
                .contains("users")
                .contains("SQLite");
        assertThat(dialog.dialogPane().lookup(".adb-dialog__context"))
                .as("N26：内容区不再画自绘抬头条")
                .isNull();
    }

    @Test
    @DisplayName("计数来自真实列表：加一列，「添加列」减一、「索引列」加一")
    void listCountsComeFromTheRealData() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        assertThat(dialog.addButton().getText())
                .isEqualTo(I18n.t("idx.add.count", 4));
        assertThat(dialog.chosenHeader().getText())
                .isEqualTo(I18n.t("idx.chosen.header", 0));
        // 下拉里就是可用列本身 —— 少了「可用列」列表，可选的东西必须在这一颗按钮里。
        assertThat(itemTexts(dialog)).containsExactly("id", "email", "first", "last");

        addColumn(dialog, "email");

        assertThat(dialog.addButton().getText()).isEqualTo(I18n.t("idx.add.count", 3));
        assertThat(dialog.chosenHeader().getText()).isEqualTo(I18n.t("idx.chosen.header", 1));
        assertThat(itemTexts(dialog)).containsExactly("id", "first", "last");
    }

    // ─────────────────────────── §6.5 / U21：添加、移除、排序 ───────────────────────────

    @Test
    @DisplayName("可用列加完后「添加列」禁用（没得选的按钮点了没反应，是最糟的反馈）")
    void addButtonIsDisabledWhenNothingIsLeftToPick() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        assertThat(dialog.addButton().isDisabled()).isFalse();

        for (String column : List.of("id", "email", "first", "last")) {
            addColumn(dialog, column);
        }

        assertThat(dialog.availableColumns()).isEmpty();
        assertThat(dialog.addButton().isDisabled()).as("4 列全进索引 → 下拉里没有东西可选").isTrue();
        assertThat(dialog.addButton().getText()).isEqualTo(I18n.t("idx.add.count", 0));
    }

    @Test
    @DisplayName("行尾 × 删的是它自己那一行，列退回可用列并保持表的原始顺序")
    void removingReturnsTheColumnToTheAvailableList() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        addColumn(dialog, "id");
        addColumn(dialog, "email");
        // 选中刻意停在第一行：× 跟着「选中」走就会删错行 —— 单元格是复用的。
        onFxRun(() -> dialog.chosenListView().getSelectionModel().select(0));

        fire(rowRemoveButton(dialog, "email"));

        assertThat(dialog.chosenColumns()).extracting(IndexDialog.PickedColumn::name)
                .containsExactly("id");
        assertThat(dialog.availableColumns()).containsExactly("email", "first", "last");
    }

    @Test
    @DisplayName("Delete 键与行尾 × 是同一条移除路径（键盘用户不该被漏掉）")
    void deleteKeyRemovesTheSelectedRowLikeTheCross() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        addColumn(dialog, "email");

        pressOnChosenList(dialog, KeyCode.DELETE);

        assertThat(dialog.chosenColumns()).isEmpty();
        assertThat(dialog.availableColumns()).containsExactly("id", "email", "first", "last");
    }

    @Test
    @DisplayName("每行是可拖拽的排序源：拖拽三个手势都接在线（U21 的鼠标路径）")
    void pickedRowsCarryTheDragHandlers() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        addColumn(dialog, "email");

        HBox row = onFx(() -> (HBox) firstCell(dialog).getGraphic());

        assertThat(row).isNotNull();
        assertThat(row.getOnDragDetected()).as("没有拖起手 = 排序只能靠键盘").isNotNull();
        assertThat(row.getOnDragOver()).as("没有接受落下 = 拖到哪都不生效").isNotNull();
        assertThat(row.getOnDragDropped()).as("落下不做事 = 拖拽是个假动作").isNotNull();
    }

    @Test
    @DisplayName("复合索引：列顺序就是 SQL 里的顺序，改顺序必须同步改预览")
    void compositeColumnOrderDrivesTheSql() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        onFxRun(() -> dialog.nameField().setText("idx_names"));
        addColumn(dialog, "first");
        addColumn(dialog, "last");
        assertThat(dialog.previewText()).endsWith("(\"first\", \"last\")");

        // 「last」现在是第 2 行，Alt+↑ 前移一位 → 它成为最左前缀。
        onFxRun(() -> dialog.chosenListView().getSelectionModel().select(1));
        pressOnChosenList(dialog, KeyCode.UP, true);

        assertThat(dialog.previewText()).endsWith("(\"last\", \"first\")");

        pressOnChosenList(dialog, KeyCode.DOWN, true);

        assertThat(dialog.previewText()).endsWith("(\"first\", \"last\")");
    }

    // ─────────────────────────── §6.6：预览与执行同源 ───────────────────────────

    @Test
    @DisplayName("预览跟着名称、唯一、列三者实时同步，且用方言的引号")
    void previewTracksNameUniqueAndColumns() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.MYSQL), table(List.of()));

        assertThat(dialog.previewText()).isEmpty();

        onFxRun(() -> dialog.nameField().setText("idx_email"));
        assertThat(dialog.previewText()).as("还没选列 → 没有可执行的语句").isEmpty();

        addColumn(dialog, "email");
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
        // ⭐ 折叠收的是整个编辑器（行号 + 文本 + D-97 起还有那两条滚动条），
        //    CodeArea 只是它里面的孙子。原来这里读的是 area.getParent()，等于假设
        //    「编辑器的盒子就是 CodeArea 的爸爸」—— D-97 给 CodeArea 外面加了一层
        //    滚动视口，那个假设拿到的就不再是编辑器了。按 id 找本体，别再数层。
        Node box = find(dialog, "#index-dialog-sql");
        assertThat(onFx(() -> box.isManaged()))
                .as("折叠后编辑器本体不再占位")
                .isFalse();
        assertThat(onFx(() -> box.isVisible()))
                .as("本体自己也要不可见 —— CodeArea 住在它里面，本体不画才轮到它不画"
                        + "（JavaFX 的 isVisible 是各自节点的属性，不随父件继承，别去读孩子）")
                .isFalse();
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
            addColumn(dialog, "email");

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
        addColumn(dialog, "email");
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
        addColumn(dialog, "email");

        assertThat(dialog.problem()).contains(I18n.t("idx.error.duplicate", "idx_email"));

        onFxRun(() -> dialog.nameField().setText("2bad"));
        assertThat(dialog.problem()).contains(I18n.t("idx.error.badName"));

        onFxRun(() -> dialog.nameField().setText(""));
        assertThat(dialog.problem()).isEqualTo(I18n.t("idx.error.noName"));
    }

    @Test
    @DisplayName("底部是取消 + 确定，取消挂 CANCEL_CLOSE（Escape 与关窗同一条出口）")
    void footerKeepsTheAppWideButtonContract() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));
        DialogPane pane = dialog.dialogPane();

        assertThat(pane.getButtonTypes()).hasSize(2);
        assertThat(buttonData(pane, I18n.t("common.ok")).isDefaultButton()).isTrue();
        assertThat(buttonData(pane, I18n.t("common.cancel")).isCancelButton()).isTrue();
    }

    @Test
    @DisplayName("宽度走 WIDE 档（U21 之后没有双列要并排）、高度落在 §6.1 的 560–620 区间")
    void dialogUsesTheWideSizeTier() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        assertThat(dialog.dialogPane().getPrefWidth()).isEqualTo(DialogSizes.WIDE);
        assertThat(dialog.dialogPane().getPrefHeight()).isBetween(560.0, 620.0);
        assertThat(dialog.dialogPane().getMinHeight()).isLessThanOrEqualTo(560.0);
    }

    @Test
    @DisplayName("截图摆态走的是点「添加列」下拉同一条路：真实前两列 + 名字 + 预览一起跟上")
    void poseForShotsFillsThePickerThroughTheClickPath() {
        IndexDialog dialog = mounted(FakeDbSession.of(DialectKind.SQLITE), table(List.of()));

        onFxRun(dialog::poseForShots);

        assertThat(dialog.chosenColumns()).extracting(IndexDialog.PickedColumn::name)
                .as("加的是这张表真实的前两列，不是写死的示例列名")
                .containsExactly("id", "email");
        assertThat(dialog.availableColumns()).containsExactly("first", "last");
        assertThat(dialog.addButton().getText()).isEqualTo(I18n.t("idx.add.count", 2));
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

    /** 点「添加列」下拉里的某一列 —— 与用户真的用鼠标点同一条路（不是往列表塞数据）。 */
    private static void addColumn(IndexDialog dialog, String column) {
        onFxRun(() -> items(dialog).stream()
                .filter(item -> column.equals(item.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("下拉里没有列 " + column))
                .fire());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static List<MenuItem> items(IndexDialog dialog) {
        return List.copyOf(dialog.addButton().getItems());
    }

    /** 下拉里的列名，按可见顺序 —— 「还剩什么可选」的唯一证据。 */
    private static List<String> itemTexts(IndexDialog dialog) {
        return items(dialog).stream().map(MenuItem::getText).toList();
    }

    /**
     * 某一行的行尾 ×（按列名定位）。
     *
     * <p>按列名而不是按位置取：门禁要证明的是「× 认的是自己那一行」，
     * 拿第一行删一行等于什么都没测 —— 单元格复用后按位置取反而会测到错的行。
     */
    private static Button rowRemoveButton(IndexDialog dialog, String column) {
        return (Button) onFx(() -> {
            for (Node node : dialog.chosenListView().lookupAll(".list-cell")) {
                if (!(node instanceof ListCell<?> cell)
                        || !(cell.getGraphic() instanceof HBox row)) {
                    continue;
                }
                boolean sameColumn = row.getChildren().stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .anyMatch(label -> column.equals(label.getText()));
                if (!sameColumn) {
                    continue;
                }
                return row.getChildren().stream()
                        .filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getStyleClass().contains("adb-icon-button"))
                        .findFirst().orElse(null);
            }
            return null;
        });
    }

    /** 第一个非空单元格 —— 拖拽手势挂在它的行上，空行没有行可拖。 */
    private static ListCell<?> firstCell(IndexDialog dialog) {
        for (Node node : dialog.chosenListView().lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && cell.getGraphic() != null) {
                return cell;
            }
        }
        throw new AssertionError("索引列列表里没有生成任何单元格");
    }

    /** 往列表本身发一个按键 —— 走的是 {@code setOnKeyPressed} 那一条真路径。 */
    private static void pressOnChosenList(IndexDialog dialog, KeyCode key) {
        pressOnChosenList(dialog, key, false);
    }

    private static void pressOnChosenList(IndexDialog dialog, KeyCode key, boolean alt) {
        onFxRun(() -> dialog.chosenListView().fireEvent(new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", key, false, false, alt, false)));
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
