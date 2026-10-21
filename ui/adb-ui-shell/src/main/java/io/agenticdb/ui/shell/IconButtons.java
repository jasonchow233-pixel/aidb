package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
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
}
