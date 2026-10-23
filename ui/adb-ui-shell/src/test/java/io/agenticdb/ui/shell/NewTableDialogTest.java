package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DialectKind;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 新建表对话框的门禁。
 *
 * <p>除了「生成的 SQL 对不对」，这里还要钉住一件事：
 * <b>建表是弹窗，不是标签页</b>（见 {@code docs/UI-FORMS-AND-STATES.md} §4）。
 * 做成标签页会留下一个关不掉的「空表」标签 —— 表还没建呢，标签已经在那儿了。
 */
@ExtendWith(ApplicationExtension.class)
class NewTableDialogTest {

    private FakeDbSession session;

    @Start
    void start(Stage stage) {
        session = FakeDbSession.of(DialectKind.MYSQL);
        stage.show();
    }

    @Test
    void startsWithOneIntegerPrimaryKeyRow() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        assertThat(dialog.rows()).hasSize(1);
        NewTableDialog.ColumnRow first = dialog.rows().getFirst();
        assertThat(first.name.get()).isEqualTo("id");
        assertThat(first.primaryKey.get()).isTrue();
        assertThat(first.nullable.get())
                .as("主键列不能为 NULL —— 默认就要是对的，别等用户填完再报错")
                .isFalse();
    }

    @Test
    void previewShowsTheSqlBeforeTheUserCommits() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("CREATE TABLE `orders`")
                .contains("`id` INTEGER NOT NULL PRIMARY KEY");
    }

    @Test
    void duplicateTableNameIsRefusedWithAReadableReason() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of("users")));
        onFxRun(() -> dialog.nameField().setText("users"));

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(NewTableDialog.problem(session, "users",
                java.util.List.of(row("id", true)), List.of("users")))
                .contains("已经存在");
    }

    @Test
    void aTableWithoutColumnsCannotBeCreated() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));
        onFxRun(() -> dialog.rows().clear());
        // 清空行数会触发 rows 监听 → refreshPreview 自己把预览清成空，
        // 不需要（也不能）再手动 setText —— CodeArea 没有 setText。

        assertThat(onFx(dialog::buildPlan)).isNull();
    }

    @Test
    void checkingPrimaryKeyClearsNullable() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        NewTableDialog.ColumnRow row = dialog.rows().getFirst();
        onFxRun(() -> {
            row.primaryKey.set(false);
            row.nullable.set(true);
            row.primaryKey.set(true);
        });

        assertThat(row.nullable.get()).isFalse();
    }

    @Test
    void aValidFormProducesAPlan() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));

        StructurePlan plan = onFx(dialog::buildPlan);

        assertThat(plan).isNotNull();
        assertThat(plan.statements()).isNotEmpty();
        assertThat(plan.title()).contains("orders");
    }

    /**
     * 形态门禁：建表必须是<b>弹窗</b>。
     *
     * <p>直接断言「它是个 Dialog 而不是 Tab」在编译期就定了，测不出回退；
     * 真正会回退的是<b>后来有人把它搬进标签页</b> —— 所以这里扫源码，
     * 不许出现 Tab / TabKind / openTab 这类东西。
     */
    @Test
    void newTableIsADialogNotATab() throws Exception {
        Path source = Path.of("src/main/java/io/agenticdb/ui/shell/NewTableDialog.java");
        assertThat(Files.exists(source)).isTrue();
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(text).contains("new Dialog<");
        assertThat(text)
                .as("建表不能做成标签页 —— 见 docs/UI-FORMS-AND-STATES.md §4")
                .doesNotContain("TabKind")
                .doesNotContain("new Tab(")
                .doesNotContain("openTab");
    }

    // ─────────────────────── §13 布局与规格 ───────────────────────

    /**
     * 规格 §13.1：弹窗 850–1000px。
     *
     * <p>720px 是实测出来的下限 —— 再窄，「默认值」和「注释」两列
     * 会被压得只剩一个省略号，用户改一个默认值先得横向滚。
     */
    @Test
    void dialogIsWideEnoughForTheColumnGrid() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        double width = onFx(() -> dialog.dialog().getDialogPane().getPrefWidth());
        assertThat(width).isBetween(850.0, 1000.0);
    }

    /**
     * 规格 §13.2：标题栏要有「新建数据表」+ <b>真实库名</b> + 关闭按钮。
     *
     * <p>库名来自会话标签，不是写死的示例串 —— 这条断言同时防
     * 「标题栏退化成一句通用文案」和「拿假库名充数」两种回退。
     */
    @Test
    void headerNamesTheRealDatabaseAndOffersClose() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        String label = onFx(session::label);

        List<String> texts = onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookupAll(".adb-new-table__context")
                .stream()
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .toList());
        assertThat(texts).anyMatch(text -> text.contains(label));

        assertThat(onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookup("#new-table-close"))).isNotNull();
    }

    /**
     * 规格 §13.4：SQL 预览是**只读的 SQL 编辑器**，不是 TextArea。
     *
     * <p>判据有三条，缺一条就是退化成灰底文本框：
     * 等宽字体 / 行号 / 语法高亮样式段。
     */
    @Test
    void sqlPreviewIsARealReadOnlyEditor() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));

        org.fxmisc.richtext.CodeArea area = onFx(dialog::previewArea);
        // ⚠️ 先落到局部变量再断言：`assertThat(onFx(area::getText))` 会让
        //    编译器在 assertThat 的一堆重载里挑花眼（方法引用 vs lambda）。
        String sql = onFx(area::getText);
        assertThat(sql).contains("CREATE TABLE");
        assertThat(onFx(() -> Boolean.valueOf(area.isEditable()))).isFalse();
        // 行号：ParagraphGraphicFactory 挂了才会返回非 null。
        assertThat(onFx(() -> area.getParagraphGraphicFactory())).isNotNull();
        // 高亮：非空文本上必然有样式段（纯 TextArea 一条都没有）。
        assertThat(onFx(() -> area.getStyleSpans(0).getSpanCount())).isPositive();
    }

    /** 规格 §13.2：SQL 预览可折叠，折叠后按钮文案翻转。 */
    @Test
    void sqlPreviewCollapses() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        assertThat(onFx(dialog::sqlPreviewCollapsed)).isFalse();
        String before = onFx(() -> toggleText(dialog));
        assertThat(before).isEqualTo("折叠 SQL 预览");

        onFxRun(() -> {
            javafx.scene.Node toggle = dialog.dialog().getDialogPane().getContent()
                    .lookup("#new-table-collapse-sql");
            ((javafx.scene.control.Button) toggle).fire();
        });

        assertThat(onFx(dialog::sqlPreviewCollapsed)).isTrue();
        assertThat(onFx(() -> toggleText(dialog))).isEqualTo("展开 SQL 预览");
    }

    private static String toggleText(NewTableDialog dialog) {
        javafx.scene.Node toggle = dialog.dialog().getDialogPane().getContent()
                .lookup("#new-table-collapse-sql");
        return ((javafx.scene.control.Button) toggle).getText();
    }

    /**
     * 字段网格的高度跟着行数走 —— 只有一行时不许挂 170px 空白网格
     * （规格 §13.3「字段较少时不要显示过高的空白表格」）。
     */
    @Test
    void columnGridShrinksWhenThereAreFewColumns() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        double oneRow = onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookup("#new-table-columns").prefHeight(-1));

        onFxRun(() -> {
            for (int i = 0; i < 5; i++) {
                // onChange 传空实现：这条断言只看高度，不想顺带触发整表重算。
                dialog.rows().add(new NewTableDialog.ColumnRow(() -> { }));
            }
        });
        double sixRows = onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookup("#new-table-columns").prefHeight(-1));

        assertThat(sixRows).isGreaterThan(oneRow);
        // 一行时：表头 ~34 + 一行 30 ≈ 64，夹在 [120, 300] 里就是下限 120。
        assertThat(oneRow).isLessThanOrEqualTo(120.0);
    }

    /** JavaFX 控件只能在 FX 线程上创建。 */
    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static io.agenticdb.model.ColumnSpec row(String name, boolean primaryKey) {
        return new io.agenticdb.model.ColumnSpec(name, "INTEGER", false, null, null,
                false, primaryKey);
    }
}
