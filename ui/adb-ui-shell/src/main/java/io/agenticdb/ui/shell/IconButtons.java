package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;

/**
 * ⭐ 图标按钮的工厂。
 *
 * <p>统一三件事，免得每个地方各写一遍、各漏一遍：
 * <ol>
 *   <li>图形来自 {@link Icons}（16×16 矢量，1.5px 描边）；</li>
 *   <li><b>只有图标</b>的按钮必须同时有 Tooltip（鼠标可发现）与
 *       accessibleText（读屏可朗读）—— 少一个都不合格（见 {@code IconAuditTest}）；</li>
 *   <li>图标与文字之间留一点间距，别挤在一起。</li>
 * </ol>
 */
final class IconButtons {

    /** 图标与文字之间的间距。 */
    private static final double GAP = 6;

    private IconButtons() {
    }

    /** 图标 + 文字的按钮。 */
    static Button of(Icons.Icon icon, String text) {
        Button button = new Button(text);
        button.setGraphic(Icons.of(icon));
        button.setGraphicTextGap(GAP);
        return button;
    }

    /** 只有图标的按钮（必须有提示文字）。 */
    static Button icon(Icons.Icon icon, String hint) {
        Button button = new Button();
        button.setGraphic(Icons.of(icon));
        button.setTooltip(new Tooltip(hint));
        button.setAccessibleText(hint);
        return button;
    }

    /**
     * 图标 + 提示的 {@link Label}。
     *
     * <p>⚠️ {@code setTooltip} 只在 {@code Control} 上，{@code SVGPath} 是 {@code Shape}
     * —— 挂不了 Tooltip。所以图标要套一层 Label 才能带提示
     * （功能栏就是这种情况：图标 + 文字竖排，提示挂图标上）。
     */
    static Label iconLabel(Icons.Icon icon, double size, String hint, String accessibleText) {
        Label label = new Label();
        label.setGraphic(Icons.of(icon, size));
        label.setTooltip(new Tooltip(hint));
        label.setAccessibleText(accessibleText);
        return label;
    }

    /** 给已有的按钮装上图标（保持原有文字与提示）。 */
    static <T extends Button> T withIcon(T button, Icons.Icon icon) {
        button.setGraphic(Icons.of(icon));
        button.setGraphicTextGap(GAP);
        return button;
    }

    /**
     * 纯图标的**下拉**按钮（提示由调用方设置 —— 菜单按钮的提示各不相同）。
     */
    static <T extends MenuButton> T iconMenu(T button, Icons.Icon icon, String accessibleText) {
        button.setGraphic(Icons.of(icon));
        button.setAccessibleText(accessibleText);
        return button;
    }

    /** 给已有的下拉按钮装上图标。 */
    static <T extends MenuButton> T withIcon(T button, Icons.Icon icon) {
        button.setGraphic(Icons.of(icon));
        button.setGraphicTextGap(GAP);
        return button;
    }

    /**
     * ⭐ 让按钮进入「加载中」：图标换成小转圈，**宽度不变**。
     *
     * <p>为什么强调宽度：加载前是 16px 图标，加载后是 14px 转圈，
     * 如果不固定宽度，按钮会**变窄**，整排工具栏跟着往左抽一下 ——
     * 用户正在点的按钮会「跑掉」。
     *
     * <p>禁用状态由调用方决定（不同操作的前置条件不一样），
     * 这里只管**图形与宽度**。
     */
    static <T extends Button> T loading(T button, boolean loading) {
        if (loading) {
            if (button.getUserData() == null) {
                button.setUserData(button.getGraphic());
            }
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize(14, 14);
            spinner.setPrefSize(14, 14);
            spinner.getStyleClass().add("adb-button__spinner");
            button.setGraphic(spinner);
            double width = Math.max(button.getPrefWidth(), button.getWidth());
            if (width > 0) {
                button.setMinWidth(width);
                button.setPrefWidth(width);
            }
        } else {
            Object graphic = button.getUserData();
            if (graphic instanceof Node node) {
                button.setGraphic(node);
            }
            button.setUserData(null);
        }
        return button;
    }
}
