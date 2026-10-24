package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
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
        return icon(icon, hint, Icons.SIZE_TOOLBAR);
    }

    /** 只有图标的按钮，指定尺寸（页面主工具条用 {@link Icons#SIZE_PRIMARY}）。 */
    static Button icon(Icons.Icon icon, String hint, double size) {
        Button button = new Button();
        button.setGraphic(Icons.of(icon, size));
        button.setTooltip(new Tooltip(hint));
        button.setAccessibleText(hint);
        return button;
    }

    /**
     * ⭐ 工具条**主操作**：图标 + 文字 + 提示，一次给全。
     *
     * <p>为什么要这个工厂：同一个工具条里「保存」有文字、「导出」只有图标，
     * 用户只能靠猜。承担「改数据 / 走网络 / 不可逆」语义的动作一律带文字
     * （门禁：{@code ToolbarDiscoverabilityTest}）。
     *
     * <p>参数收在 {@link ButtonBase} 上 —— {@code Button} 与 {@code MenuButton}
     * 都是它的子类，工具条两类都有（导出、视图是下拉）。
     */
    static <T extends ButtonBase> T labeled(T button, Icons.Icon icon, String text, String hint) {
        return labeled(button, icon, text, hint, Icons.SIZE_TOOLBAR);
    }

    /** 工具条主操作，指定图标尺寸（对象首页用 {@link Icons#SIZE_PRIMARY}）。 */
    static <T extends ButtonBase> T labeled(T button, Icons.Icon icon, String text, String hint,
                                            double size) {
        button.setGraphic(Icons.of(icon, size));
        button.setText(text);
        button.setGraphicTextGap(GAP);
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
