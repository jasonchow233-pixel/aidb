package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.jdbc.ColumnProblem;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.Danger;
import io.agenticdb.ui.I18n;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ⭐ R42 N28-a：视图设计器 —— Navicat 的「新建视图 / 设计视图」那一个窗口。
 *
 * <p><b>只收查询体</b>（{@code SELECT …}），{@code CREATE VIEW} 由方言拼：
 * 六个方言的引用符号、重建写法都不一样（{@link io.agenticdb.jdbc.ViewReplace}），
 * 让用户手写整条 DDL 等于把方言差异推给最不熟悉它的人。
 *
 * <p><b>预览即执行</b>（与 {@link NewTableDialog} 同一条铁律）：
 * 底下那块只读预览和真正交给 {@link StructurePlanRunner} 的语句出自同一次
 * {@link #currentPlan()} 调用 —— 预览里看到两句「先删再建」，库里就跑两句。
 *
 * <p><b>「设计视图」不改名字</b>：本版本没有视图重命名（要 {@code RENAME} 的方言
 * 写法各不相同，MySQL 走 {@code RENAME TABLE}、PG 走 {@code ALTER VIEW … RENAME TO}），
 * 于是名字那格在重建模式下<b>置灰</b>并写明「请用新名字建一个再删旧的」——
 * 不是藏起来，也不是摆一颗点了没反应的按钮。
 *
 * <p>校验交回的是「键 + 参数」（{@link ColumnProblem}），文案在这里渲染：
 * 模型层的中文棘轮已经卡满（{@code I18nAuditTest} 的 290），
 * 所以连计划标题也一样 —— UI 侧拿 {@code view.plan} 前缀的键现拼，模型层只出键名。
 */
final class ViewDesignerDialog {

    /** 设计器宽度：720 档（900 是给字段网格的）。长定义靠折行读，不靠横向滚动 —— 见 {@link #buildUi}。 */
    private static final double DIALOG_WIDTH = DialogSizes.WIDE;

    private final Dialog<StructurePlan> dialog = new Dialog<>();
    private final DbSession session;
    /** 当前库里已经占用的名字（表 + 视图同命名空间），新建时用来挡重名。 */
    private final List<String> takenNames;
    /** {@code true} = 改一个已经存在的视图（名字锁死、走方言的重建写法）。 */
    private final boolean redefine;

    private final TextField name = new TextField();
    private final SqlEditor selectEditor = new SqlEditor();
    private final SqlEditor previewEditor = new SqlEditor();
    private final Label error = new Label();
    private final Label hint = new Label();

    private ViewDesignerDialog(DbSession session, List<String> takenNames,
                               String viewName, String initialBody) {
        this.session = session;
        this.takenNames = takenNames == null ? List.of() : List.copyOf(takenNames);
        this.redefine = viewName != null && !viewName.isBlank();
        buildUi(viewName, initialBody);
    }

    static ViewDesignerDialog create(DbSession session, List<String> takenNames) {
        return new ViewDesignerDialog(session, takenNames, "", "");
    }

    /** 设计已存在的视图：名字锁死，查询体预填（预填内容由调用方读好）。 */
    static ViewDesignerDialog redesign(DbSession session, List<String> takenNames,
                                       String viewName, String initialBody) {
        return new ViewDesignerDialog(session, takenNames, viewName, initialBody);
    }

    /** 显示并返回视图变更计划；取消或校验不通过时为空。 */
    Optional<StructurePlan> show(Window owner, Collection<String> stylesheets) {
        if (stylesheets != null) {
            dialog.getDialogPane().getStylesheets().addAll(stylesheets);
        }
        DialogStyles.apply(dialog, owner);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    // ─────────────────────────── 界面 ───────────────────────────

    private void buildUi(String viewName, String initialBody) {
        // ⭐ N26：标题与「会话上下文」进系统标题栏，内容区不画自绘抬头。
        String context = DialogStyles.shortenContext(session.label(), DialogStyles.PATH_BUDGET);
        dialog.setTitle(I18n.t(redefine ? "newview.redefineTitle" : "newview.title")
                + " · " + context);
        dialog.getDialogPane().setPrefWidth(DIALOG_WIDTH);
        dialog.getDialogPane().setMinWidth(DIALOG_WIDTH);
        // 高度不写死：写死会在提示行下面留一条一百多像素的死空白，
        // 而错误行 / 先删再建提示出现时又要挤掉正文。让它跟着内容走。
        dialog.setHeaderText(null);

        name.setId("new-view-name");
        name.setPromptText(I18n.t("newview.name.prompt"));
        name.setText(viewName == null ? "" : viewName);
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());
        if (redefine) {
            // 见类头：本版本没有视图重命名 —— 置灰 + 写明替代路径。
            name.setDisable(true);
            name.setTooltip(new javafx.scene.control.Tooltip(I18n.t("newview.name.locked")));
        }

        selectEditor.area().setId("new-view-select");
        // ⭐ D-95：这两格读的是**一整句定义**，折行比横着拖条好读，所以就地开折行。
        //    （当时共用件 `SqlEditor` 连横条都没有 —— 一行超出弹窗宽度只能靠「把光标
        //    移到行尾」把内容横移出来，帧面上零线索，读的人看到的是 from `us 后面凭空没了。）
        //    ⭐ D-97 之后横条在共用件里补上了，这一句**仍然要留着**：AS_NEEDED 的策略下
        //    折行盒永远不会长出横条，两条路不冲突，而设计器要的本来就是「整句一次看全」。
        selectEditor.area().setWrapText(true);
        selectEditor.setHint(I18n.t("newview.select.hint"));
        selectEditor.setText(initialBody == null ? "" : initialBody);
        selectEditor.addTextListener((ignored) -> refreshPreview());
        selectEditor.setPrefHeight(184);
        VBox.setVgrow(selectEditor, Priority.ALWAYS);

        previewEditor.area().setEditable(false);
        previewEditor.area().setId("new-view-preview");
        // 只读那一格更需要折：它连「点一下把光标挪过去」这条隐路都很少人想到，
        // 而它显示的正是**将要执行的整句**（D-95 的实拍截断就在这格）。
        previewEditor.area().setWrapText(true);
        // 只读预览不能留那句「⌘↵ 执行当前语句」（§11.2 同判）—— 它执行不了。
        // 空态那句说的是「为什么现在是空的」，不是「这里叫什么」—— 名字在上面的标题里。
        previewEditor.setHint(I18n.t("newview.preview"));
        previewEditor.setPrefHeight(160);

        error.setId("new-view-error");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        hint.setId("new-view-hint");
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);
        hint.setVisible(false);
        hint.setManaged(false);

        VBox body = new VBox(12,
                buildNameSection(),
                buildSelectSection(),
                buildPreviewSection(),
                hint,
                error);
        body.getStyleClass().add("adb-dialog__body");
        dialog.getDialogPane().setContent(body);
        dialog.setResizable(true);

        ButtonType okType = new ButtonType(
                I18n.t(redefine ? "newview.button.update" : "newview.button.create"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(okType, cancel);
        // 校验不过要把弹窗留在原地：关掉重开等于把刚写的查询全丢（NewTableDialog 同规）。
        Button okButton = (Button) dialog.getDialogPane().lookupButton(okType);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (buildPlan() == null) {
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == okType ? buildPlan() : null);

        refreshPreview();
    }

    private HBox buildNameSection() {
        Label label = formLabel(I18n.t("newview.context.name"));
        label.setMinWidth(56);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox box = new HBox(10, label, name);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    private VBox buildSelectSection() {
        Label label = formLabel(I18n.t("newview.select"));
        return new VBox(6, label, selectEditor);
    }

    /**
     * 预览那一格必须有标题：两格编辑器挨在一起，没有标题就看不出上面是「用户写的」、
     * 下面是「服务器要执行的」（实拍帧里两格长得一模一样）。
     */
    private VBox buildPreviewSection() {
        Label label = formLabel(I18n.t("newview.preview.title"));
        return new VBox(6, label, previewEditor);
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-form__label");
        return label;
    }

    // ─────────────────────────── 计划与预览 ───────────────────────────

    /** 用户在「设计视图」里看到的名字（重建模式下就是要改的那个视图）。 */
    private String targetName() {
        return name.getText();
    }

    /**
     * 当前输入对应的变更计划；形态不合法时返回 {@code null} 并把原因写进错误行。
     *
     * <p>预览与执行都走这里 —— 两处各算一次会慢慢跑偏（R31 的教训）。
     */
    StructurePlan buildPlan() {
        try {
            return currentPlan();
        } catch (IllegalArgumentException e) {
            showError(NewTableDialog.problemText(e));
            return null;
        }
    }

    private StructurePlan currentPlan() {
        String trimmed = targetName() == null ? "" : targetName().trim();
        if (!redefine) {
            // 新建才查重名；重建时「已经有这个视图」正是前提，不是错误。
            ColumnProblem problem = StructureSqlBuilder.viewNameProblem(trimmed, takenNames);
            if (problem != null) {
                throw problem;
            }
        }
        List<String> statements = StructureSqlBuilder.viewStatements(
                session, trimmed, selectEditor.text(), redefine);
        // ⭐ 「先删再建」是两句 —— 第二句之前那个视图已经没了，依赖它的对象会失败。
        //    这种计划必须第二道确认；单句 OR REPLACE 不用。
        boolean secondApproval = statements.size() > 1;
        return new StructurePlan(redefine ? "view.plan.update" : "view.plan.create",
                List.of(trimmed), statements, Danger.DESTRUCTIVE, secondApproval, false);
    }

    private void refreshPreview() {
        hideError();
        StructurePlan plan = buildPlan();
        if (plan == null) {
            // 写一半也要给东西看：留空比「半条语法错的 SQL」诚实。
            previewEditor.setText("");
            showHint(null);
            return;
        }
        previewEditor.setText(plan.sql());
        showHint(plan.statements().size() > 1
                ? I18n.t("newview.hint.dropThenCreate", session.dialect().displayName())
                : null);
    }

    private void showHint(String text) {
        hint.setText(text == null ? "" : text);
        hint.setVisible(text != null);
        hint.setManaged(text != null);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
    }

    private void hideError() {
        error.setText("");
        error.setVisible(false);
        error.setManaged(false);
    }

    /**
     * 截图 / 验收：把设计器摆成有内容的样子（空弹窗证不了形态）。
     *
     * <p>名字用的是 {@code v_paid_orders} —— 演示库里已经有 {@code v_active_users}，
     * 摆一个会撞名的样子，拍出来的就是「校验拒绝」那一态而不是正常态。
     */
    void poseForShots(String pose) {
        if ("create".equals(pose)) {
            if (name.getText().isBlank()) {
                name.setText("v_paid_orders");
            }
            if (selectEditor.text().isBlank()) {
                selectEditor.setText("""
                        SELECT o.id, o.user_id, o.amount
                          FROM orders o
                          JOIN users u ON u.id = o.user_id
                         WHERE o.status = 'paid'
                        """);
            }
            refreshPreview();
        }
        if ("invalid".equals(pose)) {
            name.setText("v_orders");
            // 一句 SQL 里塞两句：预览必须空着、错误必须显出来（不许拼出半条 DDL）。
            selectEditor.setText("SELECT 1; DROP TABLE users");
            refreshPreview();
        }
    }

    // ─────────────────────────── 门禁用 ───────────────────────────

    Dialog<StructurePlan> dialog() {
        return dialog;
    }

    TextField nameField() {
        return name;
    }

    SqlEditor selectEditor() {
        return selectEditor;
    }

    SqlEditor previewEditor() {
        return previewEditor;
    }

    Label errorLabel() {
        return error;
    }

    Label hintLabel() {
        return hint;
    }

    boolean isRedefine() {
        return redefine;
    }
}
