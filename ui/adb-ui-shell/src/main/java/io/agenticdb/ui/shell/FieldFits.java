package io.agenticdb.ui.shell;

import javafx.scene.control.TextField;

/**
 * 输入框的宽度跟着**提示文字**量。
 *
 * <p>写死的宽度都是按中文量的：「跳至页」三个字 64px 装得下，
 * 英文 "Go to page" 要 70px，于是框里长出「Go to pa」。
 * 第四批在数据网格的分页器上踩过一次，第六批抽查询页时在同一排又踩一次 ——
 * 所以把这条规则收到一处，而不是让两页各写一遍。
 */
final class FieldFits {

    private FieldFits() {
    }

    /**
     * 抬到装得下 {@code promptText}。
     *
     * <p>⚠️ pref 与 min 要一起抬：只抬 pref 时父容器（HBox）一挤就压回原来的 min，
     * 实测英文档里框仍被压成 56px、提示照样截断。抬完放不下时，
     * 应由降级阶梯**整项收起**，而不是把文字切成半截。
     *
     * <p>⚠️ 必须在节点**已挂到场景**之后调用：没挂上时 `getFont()` 还是默认字体，
     * 量出来的宽度和实际渲染对不上。
     */
    static void prompt(TextField field) {
        String prompt = field.getPromptText();
        if (prompt == null || prompt.isEmpty()) {
            return;
        }
        field.applyCss();
        javafx.scene.text.Text probe = new javafx.scene.text.Text(prompt);
        probe.setFont(field.getFont());
        // 16 = -fx-padding 左右各 8px；再留 4px 给光标与圆角边框。
        double need = Math.ceil(probe.getLayoutBounds().getWidth()) + 20;
        if (field.getPrefWidth() < need) {
            field.setPrefWidth(need);
        }
        if (field.getMinWidth() < need) {
            field.setMinWidth(need);
        }
    }
}
