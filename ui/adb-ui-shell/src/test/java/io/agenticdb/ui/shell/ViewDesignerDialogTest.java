package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
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

    /**
     * D-95 的那条长定义：MySQL 实拍帧里的视图正文就是一整行（约 300 字符），
     * 在 720 宽的弹窗里远超一行能放下的量。
     */
    private static final String LONG_BODY = "SELECT `users`.`id` AS `id`, `users`.`name` AS `name`,"
            + " `users`.`email` AS `email`, `users`.`status` AS `status`,"
            + " `users`.`created_at` AS `created_at`, `users`.`updated_at` AS `updated_at`"
            + " FROM `users` WHERE `users`.`status` = 'active'"
            + " AND `users`.`created_at` > '2026-01-01' ORDER BY `users`.`id`";

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

    // ─────────────────── D-95：长定义折行（帧轮读出的能力缺口） ───────────────────

    /**
     * ⭐ D-95：输入格 —— 一行超长定义必须<b>折进框内</b>，不能被右边框截掉。
     *
     * <p>立案证据是实拍帧 {@code p6-design-view-mysql}：预览格第二行读到
     * ``from `us`` 就断在框边（x≈712），底边<b>没有滚动条轨道</b>。探针在同一共用件上
     * 实测（690 宽、200 字符单行）：不折行时 {@code getParagraphLinesCount} 恒为 1，
     * 而横移只在「把光标移到行尾」之后才发生（{@code estimatedScrollX} 0 → 786）——
     * 也就是说内容并没有丢，但<b>界面上没有任何线索</b>说右边还有东西，读的人只能看到半句。
     *
     * <p>钉的是几何结果，不是 {@code isWrapText()}：折行只是手段，
     * 「一行放不下时它占多个视觉行」才是要的东西。
     */
    @Test
    void aLongSelectBodyFoldsIntoTheInputBoxInsteadOfRunningOffTheEdge() {
        ViewDesignerDialog dialog = onFx(() -> {
            ViewDesignerDialog created = ViewDesignerDialog.create(
                    FakeDbSession.of(DialectKind.MYSQL), List.of());
            created.nameField().setText("v_active_users");
            created.selectEditor().setText(LONG_BODY);
            return created;
        });

        Fold fold = onFx(() -> laidOut(dialog.selectEditor()));

        assertThat(fold.boxWidth())
                .as("量的是弹窗那一档宽度里的排版，不是无限宽的容器")
                .isLessThanOrEqualTo(DialogSizes.WIDE);
        assertThat(fold.visualLines())
                .as("设计器没有横向滚动条（SqlEditor 把 CodeArea 直接放进 StackPane）："
                        + "一行放不下就必须折起来，否则 from 后面的表名截在右边框外")
                .isGreaterThan(1);
    }

    /**
     * ⭐ D-95：只读预览格 —— 同一句「将要执行的 SQL」更要折。
     *
     * <p>这一格 {@code setEditable(false)}，用户本来就不会去里面移光标，
     * 「靠光标跟随把右边横移出来」这条隐路对它基本不存在，所以截断在这格最致命。
     */
    @Test
    void aLongGeneratedDefinitionFoldsIntoTheReadOnlyPreviewBox() {
        ViewDesignerDialog dialog = onFx(() -> {
            ViewDesignerDialog created = ViewDesignerDialog.create(
                    FakeDbSession.of(DialectKind.MYSQL), List.of());
            created.nameField().setText("v_active_users");
            created.selectEditor().setText(LONG_BODY);
            return created;
        });

        assertThat(onFx(() -> dialog.previewEditor().text()))
                .as("预览格没内容就说明正文被判成了非法形状，这条测的就不是折行")
                .isNotEmpty();
        Fold fold = onFx(() -> laidOut(dialog.previewEditor()));

        assertThat(fold.boxWidth()).isLessThanOrEqualTo(DialogSizes.WIDE);
        assertThat(fold.visualLines())
                .as("要执行的那句必须整句看得见 —— 只读格里被截掉等于让用户闭着眼睛执行")
                .isGreaterThan(1);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 编辑器在真实排版下的几何：框宽与「最长那个逻辑行占几个视觉行」。 */
    private record Fold(double boxWidth, int visualLines) {
    }

    /**
     * 把设计器的编辑器按弹窗那一档宽度挂进场景、走完 CSS 与排版再量。
     *
     * <p>不这么做量到的是「没排版过的 Region」：{@code getParagraphLinesCount}
     * 在排版前恒为 1，折没折行都看不出区别。
     */
    private static Fold laidOut(SqlEditor editor) {
        javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(editor);
        Scene scene = new Scene(box, DialogSizes.WIDE, 420);
        java.net.URL app = ViewDesignerDialogTest.class.getResource("/io/agenticdb/ui/app.css");
        assertThat(app).as("量折行要带真实字号的样式表").isNotNull();
        scene.getStylesheets().add(app.toExternalForm());
        box.applyCss();
        box.layout();
        org.fxmisc.richtext.CodeArea area = editor.area();
        int last = area.getParagraphs().size() - 1;
        return new Fold(area.getWidth(), area.getParagraphLinesCount(last));
    }

    private static String okButtonText(ViewDesignerDialog dialog) {
        return dialog.dialog().getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                .findFirst()
                .map(ButtonType::getText)
                .orElse("");
    }

    private static String dialogPreviewHint(ViewDesignerDialog dialog) {
        // 提示挂在预览编辑器自己的那一层，不在 DialogPane 的 children 里
        //（与 NewTableDialogTest 同一取法：按 .adb-query__hint 类查，别数 children 的下标 ——
        //  D-97 之后 CodeArea 的父件是滚动视口，「取父件的 children」这条路已经指向错的地方）。
        return dialog.previewEditor().lookup(".adb-query__hint")
                instanceof javafx.scene.control.Label label
                ? label.getText() : "";
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
