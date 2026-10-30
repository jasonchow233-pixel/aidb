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
 *   <li>图形来自 {@link Icons#colored(Icons.Icon, double)}（16×16 矢量，1.5px 描边）——
 *       有彩色资产就走彩色分层，没有就自动回退单色。工具条**不选边**：
 *       同一个图标在树上彩、在工具条上单色，就是 UI-VISUAL-SPEC I-4 记的那条缺陷；</li>
 *   <li><b>只有图标</b>的按钮必须同时有 Tooltip（鼠标可发现）与
 *       accessibleText（读屏可朗读）—— 少一个都不合格（见 {@code IconAuditTest}）；</li>
 *   <li>图标与文字之间留一点间距，别挤在一起。</li>
 * </ol>
 *
 * <p>⚠️ 彩色分层返回的是 {@code Group}（子层 {@code .adb-icon-edge} 等），
 * 主动作按钮（蓝底）与禁用态必须把这两类子层一起翻色，否则「蓝边压蓝底」看不见图标、
 * 「禁用还亮着」等于骗用户能点 —— 见 {@code app.css} 的彩色分层段。
 */
final class IconButtons {

    /** 图标与文字之间的间距。 */
    private static final double GAP = 6;

    private IconButtons() {
    }

    /** 图标 + 文字的按钮。 */
    static Button of(Icons.Icon icon, String text) {
        Button button = new Button(text);
        button.setMnemonicParsing(false);
        button.setGraphic(Icons.colored(icon));
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
        button.setGraphic(Icons.colored(icon, size));
        button.setTooltip(new Tooltip(hint));
        button.setAccessibleText(hint);
        return button;
    }

    /**
     * ⭐ **单色**图标按钮 —— 只在「贴着数据网格的行操作条」这一类位置用。
     *
     * <p>为什么不是一律彩色：Navicat 的 16px 图标其实分两区，逐像素看
     * {@code docs/screenshots/navicat/navicat-data-grid-2026-10-24.png} ——
     * 上沿工具条（Table Profile / Begin Transaction / Cell Editor / Filter & Sort /
     * Columns / History）是**蓝色实心小图形**，而贴在网格左下的行操作条
     * （{@code + − ✓ ✗ ↺}）是**灰的单线**。两区画法不同是刻意的：
     * 上沿在说「能做什么」，下沿在说「这一行的编辑开关」，彩了反而抢过数据本身。
     *
     * <p>所以本类的默认工厂走彩色通道，行操作条这类调用点显式走这条单色道。
     * 门禁：{@code ToolbarIconChannelTest} 两个方向都钉（彩的必须彩、单色的必须单色），
     * {@code DataGridPageTest} 从真实页面上验。
     */
    static Button iconMono(Icons.Icon icon, String hint) {
        Button button = new Button();
        button.setGraphic(Icons.of(icon, Icons.SIZE_TOOLBAR));
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

    /** 工具条主操作，指定图标尺寸（对象首页用 {@link Icons#SIZE_PRIMARY}）。
     *
     * <p>⚠️ 文字关掉了助记符解析：R34 之后按钮文字来自资源包，而资源里合法地会出现
     * {@code &}（Navicat 自己的词就是 "Filter & Sort"）。JavaFX 默认把 {@code &X}
     * 当成助记符，于是 "Filter & Sort" 会画成 "Filter  Sort" 并把 S 加下划线 ——
     * 少一个字符、多一条下划线，而且门禁全绿，只有肉眼能看出来。
     */
    static <T extends ButtonBase> T labeled(T button, Icons.Icon icon, String text, String hint,
                                            double size) {
        button.setGraphic(Icons.colored(icon, size));
        button.setText(text);
        button.setMnemonicParsing(false);
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
        label.setGraphic(Icons.colored(icon, size));
        label.setTooltip(new Tooltip(hint));
        label.setAccessibleText(accessibleText);
        return label;
    }

    /** 给已有的按钮装上图标（保持原有文字与提示）。 */
    static <T extends Button> T withIcon(T button, Icons.Icon icon) {
        button.setGraphic(Icons.colored(icon));
        button.setGraphicTextGap(GAP);
        return button;
    }

    /**
     * 纯图标的**下拉**按钮（提示由调用方设置 —— 菜单按钮的提示各不相同）。
     */
    static <T extends MenuButton> T iconMenu(T button, Icons.Icon icon, String accessibleText) {
        button.setGraphic(Icons.colored(icon));
        button.setAccessibleText(accessibleText);
        return button;
    }

    /** 给已有的下拉按钮装上图标。 */
    static <T extends MenuButton> T withIcon(T button, Icons.Icon icon) {
        button.setGraphic(Icons.colored(icon));
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
