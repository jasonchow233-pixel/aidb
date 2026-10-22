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
        onFxRun(() -> {
            dialog.rows().clear();
            dialog.previewArea().setText("");
        });

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
