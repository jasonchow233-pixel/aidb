package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R42 N28-a：视图设计器的门禁。
 *
 * <p>这里钉的是<b>界面层</b>那三条最容易静默出错的规则（SQL 形状本身归
 * {@code ViewLifecycleSqlTest} / {@code ViewDefinitionTest}）：
 * <ol>
 *   <li><b>预览即执行</b> —— 预览文本必须与 {@link StructurePlan#sql()} 逐字相同。
 *       两处各算一次会慢慢跑偏（R31 的教训），这条断言就是不让它跑偏。</li>
 *   <li><b>方言的重建写法要看得见</b> —— 同一个正文，在 MySQL / MariaDB 上预览一句
 *       {@code CREATE OR REPLACE}，只有 SQLite 预览两句「先删再建」。
 *       用户在弹窗里就该看见自己会执行几句，而不是执行完才发现视图被删过。</li>
 *   <li><b>校验不过不许关掉弹窗</b> —— 关掉重开等于把刚写的查询全丢。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class ViewDesignerDialogTest {

    private static final String BODY = "SELECT id, name FROM users WHERE status = 'active'";

    @Start
    void start(Stage stage) {
        stage.show();
    }

    // ─────────────────────────── 预览即执行 ───────────────────────────

    @Test
    void thePreviewShowsExactlyWhatWillBeExecuted() {
        ViewDesignerDialog dialog = onFx(() -> {
            ViewDesignerDialog created = ViewDesignerDialog.create(
                    FakeDbSession.of(DialectKind.MYSQL), List.of());
            created.nameField().setText("v_active_users");
            created.selectEditor().setText(BODY);
            return created;
        });

        StructurePlan plan = onFx(dialog::buildPlan);
        assertThat(plan).isNotNull();
        assertThat(onFx(() -> dialog.previewEditor().text()))
                .as("预览与计划必须逐字相同 —— 看到两句就跑两句")
                .isEqualTo(plan.sql());
        assertThat(plan.statements()).containsExactly(
                "CREATE VIEW `v_active_users` AS\n" + BODY);
    }

    @Test
    void anIncompleteBodyPreviewsNothingInsteadOfHalfAStatement() {
        ViewDesignerDialog dialog = onFx(() -> {
            ViewDesignerDialog created = ViewDesignerDialog.create(
                    FakeDbSession.of(DialectKind.MYSQL), List.of());
            created.nameField().setText("v_users");
            return created;
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> dialog.previewEditor().text()))
                .as("正文还没写，预览就该空着 —— 摆半条语法错的 DDL 是假装能执行")
                .isEmpty();
        assertThat(onFx(() -> dialog.errorLabel().getText()))
                .isEqualTo(I18n.t("view.error.emptySelect"));
    }

    // ─────────────────────── 方言的重建写法 ───────────────────────

    @Test
    void redefiningOnMariadbIsOneStatementAndNeedsNoSecondApproval() {
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.redesign(
                FakeDbSession.of(DialectKind.MARIADB), List.of("v_active_users"),
                "v_active_users", BODY));

        StructurePlan plan = onFx(dialog::buildPlan);
        assertThat(plan).isNotNull();
        assertThat(plan.statements()).containsExactly(
                "CREATE OR REPLACE VIEW `v_active_users` AS\n" + BODY);
        assertThat(plan.approvalRequired())
                .as("单句 OR REPLACE 不会先把视图删掉，不需要第二道确认")
                .isFalse();
        assertThat(onFx(() -> dialog.hintLabel().isVisible()))
                .as("没有「先删再建」要解释时，那行提示不能挂着")
                .isFalse();
    }

    @Test
    void redefiningOnMysqlIsOneStatementAndNeedsNoSecondApproval() {
        // ⭐ R52（D-29）：这条从「两句 + 要第二道确认」翻成「一句 + 不需要」。
        // 翻案依据是容器实测：MySQL 8.4 与 MariaDB 11.8 都收 CREATE OR REPLACE VIEW，
        // SHOW CREATE VIEW 回读的是新正文。判反的代价不是「多打一句 SQL」：
        // 两句之间视图<b>真的不存在</b>，还要用户在本来单句就够的服务器上
        // 答一次「要不要先删掉再建」—— 那是把我们的知识错误转嫁成用户的操作风险。
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.redesign(
                FakeDbSession.of(DialectKind.MYSQL), List.of("v_active_users"),
                "v_active_users", BODY));

        StructurePlan plan = onFx(dialog::buildPlan);
        assertThat(plan).isNotNull();
        assertThat(plan.statements()).containsExactly(
                "CREATE OR REPLACE VIEW `v_active_users` AS\n" + BODY);
        assertThat(plan.approvalRequired())
                .as("一句 OR REPLACE 里没有 DROP，不该弹危险确认")
                .isFalse();
        assertThat(onFx(() -> dialog.hintLabel().isVisible()))
                .as("没有「先删再建」要解释时，那行提示不能挂着")
                .isFalse();
    }

    @Test
    void redefiningOnSqliteDropsThenCreatesAndSaysSo() {
        // SQLite 是唯一真的没有单句重建形式的方言（R52 在六个库上都量过）。
        // 两句之间视图已经不存在了：这必须写在弹窗里，不能只写在文档里。
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.redesign(
                FakeDbSession.of(DialectKind.SQLITE), List.of("v_active_users"),
                "v_active_users", BODY));

        StructurePlan plan = onFx(dialog::buildPlan);
        assertThat(plan).isNotNull();
        assertThat(plan.statements()).containsExactly(
                "DROP VIEW \"v_active_users\"",
                "CREATE VIEW \"v_active_users\" AS\n" + BODY);
        assertThat(plan.approvalRequired())
                .as("两句「先删再建」必须有第二道确认")
                .isTrue();
        assertThat(onFx(() -> dialog.hintLabel().getText()))
                .contains(I18n.t("newview.hint.dropThenCreate", "").trim());
    }

    // ─────────────────────────── 校验 ───────────────────────────

    @Test
    void aNameAlreadyTakenByATableIsRefusedWithAReadableReason() {
        ViewDesignerDialog dialog = onFx(() -> {
            ViewDesignerDialog created = ViewDesignerDialog.create(
                    FakeDbSession.of(DialectKind.SQLITE), List.of("users"));
            created.nameField().setText("users");
            created.selectEditor().setText(BODY);
            return created;
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> dialog.errorLabel().getText()))
                .as("表与视图同一个命名空间 —— 撞了表名也要挡住，而不是等数据库报错")
                .isEqualTo(I18n.t("view.error.nameTaken", "users"));
    }

    @Test
    void redefiningDoesNotTreatTheViewsOwnNameAsADuplicate() {
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.redesign(
                FakeDbSession.of(DialectKind.SQLITE),
                List.of("users", "v_active_users"), "v_active_users", BODY));

        assertThat(onFx(dialog::buildPlan))
                .as("「已经有这个视图」正是重建的前提，不是错误")
                .isNotNull();
    }

    @Test
    void aSecondStatementInTheBodyIsRefused() {
        ViewDesignerDialog dialog = onFx(() -> {
            ViewDesignerDialog created = ViewDesignerDialog.create(
                    FakeDbSession.of(DialectKind.SQLITE), List.of());
            created.nameField().setText("v_users");
            created.selectEditor().setText("SELECT 1; DROP TABLE users");
            return created;
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> dialog.errorLabel().getText()))
                .isEqualTo(I18n.t("view.error.multiStatement"));
    }

    @Test
    void aPastedCreateViewIsRefusedInsteadOfNested() {
        ViewDesignerDialog dialog = onFx(() -> {
            ViewDesignerDialog created = ViewDesignerDialog.create(
                    FakeDbSession.of(DialectKind.SQLITE), List.of());
            created.nameField().setText("v_users");
            created.selectEditor().setText("CREATE VIEW v_users AS SELECT 1");
            return created;
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> dialog.errorLabel().getText()))
                .as("这里只收查询体 —— 原样拼进 CREATE VIEW 会套出两句 DDL")
                .isEqualTo(I18n.t("view.error.fullDdl"));
    }

    // ─────────────────────── 弹窗形态（诚实条款） ───────────────────────

    @Test
    void redefineModeLocksTheNameAndExplainsWhy() {
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.redesign(
                FakeDbSession.of(DialectKind.SQLITE), List.of("v_users"), "v_users", BODY));

        assertThat(onFx(dialog::isRedefine)).isTrue();
        assertThat(onFx(() -> dialog.nameField().isDisabled()))
                .as("本版本没有视图重命名 —— 那格要置灰 + 说明，不能摆一颗能改却无效输入框")
                .isTrue();
        assertThat(onFx(() -> dialog.nameField().getTooltip().getText()))
                .isEqualTo(I18n.t("newview.name.locked"));
    }

    @Test
    void createModeKeepsTheNameEditableAndBothModesHaveAnHonestOkButton() {
        ViewDesignerDialog create = onFx(() -> ViewDesignerDialog.create(
                FakeDbSession.of(DialectKind.SQLITE), List.of()));
        ViewDesignerDialog redesign = onFx(() -> ViewDesignerDialog.redesign(
                FakeDbSession.of(DialectKind.SQLITE), List.of("v_users"), "v_users", BODY));

        assertThat(onFx(() -> create.nameField().isDisabled())).isFalse();
        assertThat(onFx(() -> okButtonText(create))).isEqualTo(I18n.t("newview.button.create"));
        assertThat(onFx(() -> okButtonText(redesign)))
                .as("重建模式下按钮写「更新视图」，不是仍然写「新建」")
                .isEqualTo(I18n.t("newview.button.update"));
    }

    @Test
    void theReadOnlyPreviewDoesNotTeachTheUserToExecute() {
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.create(
                FakeDbSession.of(DialectKind.MYSQL), List.of()));

        assertThat(onFx(() -> dialog.previewEditor().area().isEditable()))
                .as("预览是只读的 —— 能改却不能执行，等于摆了个假编辑器")
                .isFalse();
        assertThat(onFx(() -> dialogPreviewHint(dialog)))
                .as("只读预览不能挂「⌘↵ 执行当前语句」")
                .isEqualTo(I18n.t("newview.preview"));
    }

    /**
     * 两格编辑器各自要有标题（R42 帧目视）：输入格和预览格在实拍里长得一模一样，
     * 上面那格写着「查询（SELECT）」，下面那格没有任何说明 —— 看不出它是「将要执行的 SQL」。
     */
    @Test
    void bothEditorBoxesCarryTheirOwnTitle() {
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.create(
                FakeDbSession.of(DialectKind.MYSQL), List.of()));

        List<String> titles = onFx(() -> dialog.dialog().getDialogPane().lookupAll(".adb-form__label")
                .stream().map(node -> ((javafx.scene.control.Label) node).getText()).toList());

        assertThat(titles).containsExactly(
                I18n.t("newview.context.name"),
                I18n.t("newview.select"),
                I18n.t("newview.preview.title"));
    }

    /**
     * 弹窗里的错误行 / 提示行必须保住自己的颜色（R42 帧目视）。
     *
     * <p>{@code .adb-dialog .label} 的特异性是 (0,2,0)，裸类 {@code .adb-error} /
     * {@code .adb-muted} 只有 (0,1,0) —— 压过去之后，弹窗里的校验错误渲染成正文黑，
     * 实拍帧上「红字」根本不存在（新建表弹窗同病）。规则顺序也是断言的一部分：
     * 同特异性时后写的才赢。
     */
    @Test
    void errorAndHintKeepTheirColorsInsideADialog() throws Exception {
        String css = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/io/agenticdb/ui/app.css"),
                java.nio.charset.StandardCharsets.UTF_8);
        int general = css.indexOf(".adb-dialog .label {");

        assertThat(general).as("弹窗正文标签那条通则还在（下面两条要靠它比顺序）").isGreaterThan(0);
        for (String override : List.of(".adb-dialog .adb-error {", ".adb-dialog .adb-muted {")) {
            assertThat(css.indexOf(override))
                    .as(override + " 必须存在，且写在 .adb-dialog .label 之后")
                    .isGreaterThan(general);
        }
    }

    /** 验收脚本靠 id 找控件，id 拼错就是静默拍空 —— 这里钉住四个。 */
    @Test
    void theDesignerHasTheWidgetsTheAcceptanceFramesLookFor() {
        ViewDesignerDialog dialog = onFx(() -> ViewDesignerDialog.create(
                FakeDbSession.of(DialectKind.MYSQL), List.of()));

        for (String id : List.of("new-view-name", "new-view-select",
                "new-view-preview", "new-view-error")) {
            assertThat(onFx(() -> (Node) dialog.dialog().getDialogPane().lookup("#" + id)))
                    .as("弹窗里必须找得到 #" + id)
                    .isNotNull();
        }
    }

    /** 表名那格在最上面且标题带上下文（N26 系统标题栏那一套）。 */
    @Test
    void theDialogTitleCarriesTheViewDesignerPurpose() {
        ViewDesignerDialog create = onFx(() -> ViewDesignerDialog.create(
                FakeDbSession.of(DialectKind.MYSQL), List.of()));
        ViewDesignerDialog redesign = onFx(() -> ViewDesignerDialog.redesign(
                FakeDbSession.of(DialectKind.MYSQL), List.of("v_users"), "v_users", BODY));

        assertThat(onFx(() -> create.dialog().getTitle()))
                .startsWith(I18n.t("newview.title"));
        assertThat(onFx(() -> redesign.dialog().getTitle()))
                .startsWith(I18n.t("newview.redefineTitle"));
    }

    /**
     * 形态门禁：视图设计器是<b>弹窗</b>，不是工作台标签页。
     *
     * <p>与 {@code NewTableDialogTest.newTableIsADialogNotATab} 同规（docs/UI-FORMS-AND-STATES.md §4 V1、§11.1）。
     * 「它是 Dialog」编译期就定了、测不出回退 —— 真正会回退的是后来有人把它搬进标签页，
     * 所以扫源码而不是断言运行时类型。
     */
    @Test
    void theViewDesignerIsADialogNotATab() throws Exception {
        java.nio.file.Path source =
                java.nio.file.Path.of("src/main/java/io/agenticdb/ui/shell/ViewDesignerDialog.java");
        assertThat(java.nio.file.Files.exists(source)).isTrue();
        String text = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(text).contains("new Dialog<");
        assertThat(text)
                .as("视图没有数据页可停留，做成标签页会留一张关不掉的空页 —— 见 §11.1")
                .doesNotContain("TabKind")
                .doesNotContain("openTab");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static String okButtonText(ViewDesignerDialog dialog) {
        return dialog.dialog().getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                .findFirst()
                .map(ButtonType::getText)
                .orElse("");
    }

    private static String dialogPreviewHint(ViewDesignerDialog dialog) {
        // 提示挂在预览编辑器自己的那一层，不在 DialogPane 的 children 里
        //（与 NewTableDialogTest 同一取法）。
        return dialog.previewEditor().area().getParent() instanceof javafx.scene.layout.Pane box
                ? box.getChildrenUnmodifiable().stream()
                        .filter(node -> node instanceof javafx.scene.control.Label)
                        .map(node -> ((javafx.scene.control.Label) node).getText())
                        .filter(text -> !text.isBlank())
                        .findFirst().orElse("")
                : "";
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
