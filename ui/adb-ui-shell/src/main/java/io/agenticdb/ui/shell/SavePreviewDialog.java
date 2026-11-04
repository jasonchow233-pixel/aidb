package io.agenticdb.ui.shell;

import io.agenticdb.editing.EditSqlBuilder;
import io.agenticdb.model.Danger;
import io.agenticdb.ui.I18n;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * ⭐ R58-c：保存前的 SQL 预览弹窗 —— 数据网格与查询结果区共用这一个构造口。
 *
 * <p>为什么抽出来：查询页接上回写之后，真正执行前同样要把生成的 UPDATE 给用户看一遍。
 * 两页各搭一遍，「默认按钮是取消」这条不变量就存在两份实现，一份被改回
 * {@code OK_DONE} 时另一份不会跟着红。
 *
 * <p>⭐ <b>默认按钮必须是「取消」</b>。这里用的是
 * {@code ButtonType(I18n.t("grid.save.execute"), ButtonData.OTHER)} —— {@code OK_DONE}
 * 语义上就是「默认且确认」，用它等于<b>回车直接执行写库 SQL</b>；
 * 「执行」不是默认按钮，并在弹窗显示后把默认按钮钉在「取消」上
 * （{@link DestructiveConfirm#pinCancelAsDefault}）。
 *
 * <p>⚠️ 比对结果必须用弹窗上<b>同一个</b> {@link ButtonType} 实例：
 * {@code ButtonType} 没有覆写 {@code equals}，新建的实例比不上。
 */
final class SavePreviewDialog {

    private SavePreviewDialog() {
    }

    /** 「执行」按钮类型。抽出来是为了测试能断言它不是默认按钮。 */
    static ButtonType executeButtonType() {
        // ⭐ OTHER：不是默认按钮，回车不会落在「执行」上。
        return new ButtonType(I18n.t("grid.save.execute"), ButtonBar.ButtonData.OTHER);
    }

    /** 「取消」按钮类型。 */
    static ButtonType cancelButtonType() {
        return new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    /** 风险等级对应的样式类。 */
    static String riskStyleClass(Danger danger) {
        return switch (danger) {
            case READ_ONLY -> "adb-risk--read";
            case MUTATING -> "adb-risk--write";
            case DESTRUCTIVE, ADMIN -> "adb-risk--structure";
        };
    }

    /**
     * 构造保存预览弹窗（不显示）。
     *
     * <p>抽成独立方法是为了让门禁能直接断言「默认按钮不是执行」，
     * 而不用真的把模态弹窗弹出来。
     *
     * @param owner           弹窗归属的窗口；{@code null} = 还没挂上场景，只影响主题与模态父级
     * @param transactionOpen 事务中保存只是写进事务，标题与表头得说清，不能报「自动提交」
     */
    static Dialog<ButtonType> build(Window owner, EditSqlBuilder.Plan plan, Danger danger,
                                    boolean transactionOpen) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("grid.save.title"));
        dialog.setHeaderText(transactionOpen
                        ? I18n.t("grid.save.headTx", plan.size(), DangerLabels.t(danger))
                        : I18n.t("grid.save.headPlain", plan.size(), DangerLabels.t(danger)));
        ButtonType execute = executeButtonType();
        ButtonType cancel = cancelButtonType();
        dialog.getDialogPane().getButtonTypes().addAll(execute, cancel);
        dialog.setResultConverter(type -> type);

        TextArea sql = new TextArea(plan.preview());
        sql.setEditable(false);
        sql.setWrapText(false);
        sql.getStyleClass().add("adb-sql-preview");
        sql.setPrefColumnCount(72);
        sql.setPrefRowCount(Math.min(18, Math.max(4, plan.size() + 2)));

        Label badge = new Label(DangerLabels.t(danger));
        badge.getStyleClass().add("adb-query__risk");
        badge.getStyleClass().add(riskStyleClass(danger));

        VBox content = new VBox(8, badge, sql);
        content.setPadding(new javafx.geometry.Insets(0, 0, 0, 0));
        VBox.setVgrow(sql, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        // ⭐ 主题类必须抄到 DialogPane 上 —— 否则弹窗里所有 -adb-* 查找落空。
        DialogStyles.apply(dialog, owner);
        // ⭐ 默认按钮钉在「取消」上。必须在 skin 创建之后（shown 之后）才生效。
        dialog.setOnShown(event -> DestructiveConfirm.pinCancelAsDefault(
                dialog.getDialogPane(), cancel, execute));
        return dialog;
    }

    /** 弹窗并返回用户是否点了「执行」。 */
    static boolean confirm(Window owner, EditSqlBuilder.Plan plan, Danger danger,
                           boolean transactionOpen) {
        Dialog<ButtonType> dialog = build(owner, plan, danger, transactionOpen);
        ButtonType execute = dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> I18n.t("grid.save.execute").equals(type.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "execute button missing from the save preview dialog"));
        return dialog.showAndWait().filter(execute::equals).isPresent();
    }
}
