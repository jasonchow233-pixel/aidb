package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import java.util.List;

/**
 * ⭐ 弹窗外观的统一入口。
 *
 * <h2>为什么必须有这个类</h2>
 * JavaFX 的 looked-up color（{@code -adb-*}）定义在 {@code .theme-light} /
 * {@code .theme-dark} 这两个<b>类选择器</b>上，而主题类是挂在<b>主场景根节点</b>上的。
 * 弹窗有自己的 Scene 与根节点（{@link DialogPane}）—— 那个根节点上**没有**主题类，
 * 于是弹窗里所有 {@code -adb-*} 查找全部落空：
 * <ul>
 *   <li>声明里引用未定义的 looked-up color → 该条声明被丢弃 → 退回 JavaFX 默认皮肤；</li>
 *   <li>表现就是「新建表」弹窗没有边框、没有标题分隔、按钮全是默认灰钮，
 *       和主窗口完全不是一套东西（实测截图）。</li>
 * </ul>
 *
 * <p>所以弹窗打开前要做两件事：把主题样式表挂到 DialogPane 上，
 * 并把主题类<b>抄</b>到 DialogPane 的 style class 里。
 *
 * <h2>第二件事：按钮排布（Navicat 口径）</h2>
 * JavaFX 的 Windows 默认序把主动作（OK_DONE / YES / FINISH / APPLY）排在<b>左边</b>、
 * 取消（CANCEL_CLOSE）排第二 —— 即「确定 | 取消」，和 Navicat 的桌面习惯相反。
 * 任务书 §6.7 要求主按钮在底部右侧、左侧留给辅助 / 取消（与 §21 §13.3 的裁定一致：
 * 全应用一个口径，不改个别弹窗）。这里直接把 button order 串里两个字符换位：
 * {@code …YNOCAH… → …YNCAOH…}。所有弹窗都从 {@link #apply} 走，一处改全应用生效。
 */
final class DialogStyles {

    private static final String THEME_LIGHT = "theme-light";
    private static final String THEME_DARK = "theme-dark";

    /** JavaFX Windows 默认序里主动作 O 与取消 C 的相对位置：Windows 是 "OC"，Navicat 要 "CO"。 */
    private static final String WINDOWS_DEFAULT_ORDER = "L_E+U+FBXI_YNOCAH_R";

    private DialogStyles() {
    }

    /**
     * 给弹窗套上应用样式、当前主题与统一的按钮排布。
     *
     * <p>⭐ U20：顺带把弹窗设为**可缩放**。固定尺寸的弹窗在内容放不下时
     * 只能把控件裁掉一半（新建表的字段表在 1024 高的屏上就吃掉过底部两行），
     * 用户既不能拉大也不能记住自己的偏好 —— 这两条都在这里一次性解决。
     *
     * @param dialog 目标弹窗
     * @param owner  拥有者窗口（用来取样式表与当前主题）；可为 null
     */
    static <T> void apply(Dialog<T> dialog, Window owner) {
        if (dialog == null) {
            return;
        }
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("adb-dialog");
        for (String sheet : stylesheetsOf(owner)) {
            if (!pane.getStylesheets().contains(sheet)) {
                pane.getStylesheets().add(sheet);
            }
        }
        String theme = themeOf(owner);
        if (theme != null) {
            pane.getStyleClass().removeAll(THEME_LIGHT, THEME_DARK);
            pane.getStyleClass().add(theme);
        }
        clampToScreen(pane, owner);
        orderButtonsNavicatStyle(pane);
        localizeStandardButtons(dialog);
    }

    /**
     * ⭐ R34 收官：把 JavaFX **自带**的标准按钮文字收进资源包。
     *
     * <p>「确定 / 取消」不是我们写的 Label：{@code Alert} / {@code TextInputDialog}
     * 的按钮文字来自 {@code com.sun.javafx.scene.control.resources}，按 <b>JVM 默认
     * Locale</b> 解析 —— 本机 zh_CN 就出「确定」，换台日文机器就出第三种语言。
     * 界面默认英文、按钮却是中文，这一半不是「没抽干净」而是<b>抽不到</b>：
     * 文字从来不在我们的源码里，25 批 i18n 全绿也拦不住它（p2-error / p6-rename-table
     * 两张实拍帧抓到）。收口点只有这里：所有弹窗都过 {@link #apply}。
     *
     * <p>⚠️ 只认 {@link ButtonType#OK} / {@link ButtonType#CANCEL} /
     * {@link ButtonType#CLOSE} 这三颗单例。业务弹窗里的自定义 ButtonType
     * （{@code new ButtonType(I18n.t("fk.add"), OK_DONE)} 那一族）文字本来就在我们手里，
     * 按 ButtonData 一律覆写会把「添加」改成「OK」。
     */
    private static <T> void localizeStandardButtons(Dialog<T> dialog) {
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addListener(
                (javafx.collections.ListChangeListener<ButtonType>) change ->
                        localizeStandardButtons(pane));
        localizeStandardButtons(pane);
    }

    private static void localizeStandardButtons(DialogPane pane) {
        for (ButtonType type : pane.getButtonTypes()) {
            String key = standardButtonKey(type);
            if (key == null) {
                continue;
            }
            // DialogPane 没有公开的 getButton(...)；lookupButton 返回的就是那颗 Button
            // （JavaFX 内部已按需建好），改它的 text 即生效。
            if (pane.lookupButton(type) instanceof javafx.scene.control.Button button) {
                button.setText(I18n.t(key));
            }
        }
    }

    /** 标准按钮单例 → 资源键；不是单例（业务自定义文字）返回 null，一律不动。 */
    private static String standardButtonKey(ButtonType type) {
        if (type == ButtonType.OK) {
            return "common.ok";
        }
        if (type == ButtonType.CANCEL) {
            return "common.cancel";
        }
        if (type == ButtonType.CLOSE) {
            return "common.close";
        }
        return null;
    }

    /**
     * ⭐ U20：可缩放 + **尺寸记忆**的弹窗入口。
     *
     * <p>传了 {@code sizeKey} 的弹窗，用户拉多大下次就多大（按 key 存偏好）。
     * 不传的走 {@link #apply} —— 尺寸每次回到档位默认值。
     *
     * @param sizeKey 稳定的英文标识（如 {@code new-table}）；改文案不影响记忆
     */
    static <T> void apply(Dialog<T> dialog, Window owner, String sizeKey) {
        apply(dialog, owner);
        if (dialog == null || sizeKey == null || sizeKey.isBlank()) {
            return;
        }
        java.util.prefs.Preferences prefs = dialogPrefs();
        int width = prefs.getInt(WIDTH_KEY + sizeKey, 0);
        int height = prefs.getInt(HEIGHT_KEY + sizeKey, 0);
        DialogPane pane = dialog.getDialogPane();
        if (width >= MIN_REMEMBERED && height >= MIN_REMEMBERED) {
            // 换过显示器 / 缩过窗口，记忆里可能留着一个比屏幕还大的尺寸 —— 恢复前先夹一次。
            javafx.geometry.Rectangle2D bounds = screenOf(owner);
            if (bounds != null && bounds.getWidth() > 0) {
                width = (int) Math.min(width, bounds.getWidth() - 64);
                height = (int) Math.min(height, bounds.getHeight() - 96);
            }
            if (width >= MIN_REMEMBERED && height >= MIN_REMEMBERED) {
                pane.setPrefSize(width, height);
            }
        }
        // ⭐ U28（2026-10-26）：记的是**面板**尺寸，不是窗口尺寸。
        // 旧实现监听 dialog.widthProperty() —— 那是**含标题栏/边框**的窗口宽，
        // 恢复时却直接 pane.setPrefSize(窗口宽) —— 窗口 = 面板 + 16px 装饰，
        // 于是每开一次「记 896 → 开成 912 → 记 912 → 开成 928…」无限棘轮，
        // 新建索引从 720 一路长到 912×1023（实拍帧验收时抓到的）。
        // 面板属性只有布局会动它，记多少恢复出来就是多少，闭环从此稳定。
        pane.widthProperty().addListener((o, old, w) ->
                prefs.putInt(WIDTH_KEY + sizeKey, (int) Math.round(w.doubleValue())));
        pane.heightProperty().addListener((o, old, h) ->
                prefs.putInt(HEIGHT_KEY + sizeKey, (int) Math.round(h.doubleValue())));
    }

    private static final String WIDTH_KEY = "dialog.size.v2.width.";
    private static final String HEIGHT_KEY = "dialog.size.v2.height.";
    /** 小于这个尺寸的记录一律不当「用户偏好」恢复（0 = 还没显示，负数 = 垃圾值）。 */
    private static final int MIN_REMEMBERED = 200;

    /** 与 {@code SettingsStore} / 窗口几何**同一个**偏好节点（截图脚本靠它隔离）。 */
    private static java.util.prefs.Preferences dialogPrefs() {
        return SettingsStore.openNode();
    }

    /**
     * 弹窗标题栏的上下文（「表 users · 方言 · 库」）。
     *
     * <p>⭐ U20：会话标签里的库路径是**绝对路径**
     * （{@code C:\Users\Administrator\.agenticdb\agenticdb-demo.db}），
     * 原样摆进标题栏，用户先看的是 40 个反斜杠，真正要区分的文件名反而被挤走。
     * 这里逐段扫，把像绝对路径的那一段收成「头 … 文件名」，完整值进 tooltip。
     * 不用尾部省略号 —— 尾部截断截掉的正是文件名。
     */
    static javafx.scene.control.Label contextLabel(String text) {
        javafx.scene.control.Label label = new javafx.scene.control.Label(text);
        label.getStyleClass().add("adb-dialog__context");
        String shortened = shortenContext(text, PATH_BUDGET);
        if (!shortened.equals(text)) {
            label.setText(shortened);
            label.setTooltip(new javafx.scene.control.Tooltip(text));
        }
        return label;
    }

    /** 标题栏上下文里路径段的显示预算（字符数）。 */
    static final int PATH_BUDGET = 28;

    /** 按 {@code " · "} 分段，只压像路径的那一段，其它段一个字不动。 */
    static String shortenContext(String text, int budget) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] parts = text.split(" · ", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(" · ");
            }
            out.append(shortenPath(parts[i], budget));
        }
        return out.toString();
    }

    /**
     * 把一段像绝对路径的文本收成「头 … 文件名」。
     *
     * <p>不带分隔符的（表名、方言名）原样返回 —— 那不是要压的对象。
     */
    static String shortenPath(String value, int budget) {
        if (value == null || value.length() <= budget) {
            return value;
        }
        int cut = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (cut < 0 || cut == value.length() - 1) {
            return value;
        }
        String separator = value.charAt(cut) == '/' ? "/" : "\\";
        String name = value.substring(cut + 1);
        String parent = value.substring(0, cut + 1);
        int head = Math.max(1, Math.min(parent.length(), budget - name.length() - 1));
        return parent.substring(0, head) + "…" + separator + name;
    }

    /**
     * 把按钮栏排成 Navicat 的「取消在左、主动作在右」。
     *
     * <p>JavaFX 25 的 DialogPane 没有公开的 getButtonBar()，但它在**构造时**就把
     * ButtonBar 挂成了自己的直接子节点（源码 :242-244）—— 遍历 children 即可拿到，
     * 不依赖皮肤、不用反射。皮肤侧对 order 串有变更监听（ButtonBarSkin :121 →
     * layoutButtons），改字符串就会重排；DialogPane 之后只重排按钮列表、**不会再覆盖
     * order 串**，所以这里改一次即全生命周期有效。默认串若不是我们认识的那份
     * （平台预设换了），宁可维持现状，也不猜一个把按钮排乱。
     */
    private static void orderButtonsNavicatStyle(DialogPane pane) {
        ButtonBar bar = buttonBarOf(pane);
        if (bar == null) {
            return;
        }
        if (WINDOWS_DEFAULT_ORDER.equals(bar.getButtonOrder())) {
            // 'O'（OK_DONE，确定/创建/执行这一族）与 'C'（CANCEL_CLOSE，取消）换槽，
            // 其余（H 帮助、A 应用、Y/N 是否）相对次序保持 JavaFX 原样。
            bar.setButtonOrder(swapChar(WINDOWS_DEFAULT_ORDER, 'O', 'C'));
        }
    }

    /** 交换序串里两个字符所在的位置（值互换，不动其它槽）。 */
    static String swapChar(String order, char first, char second) {
        char[] chars = order.toCharArray();
        int a = order.indexOf(first);
        int b = order.indexOf(second);
        if (a < 0 || b < 0) {
            return order;
        }
        chars[a] = second;
        chars[b] = first;
        return new String(chars);
    }

    /** DialogPane 构造时把 ButtonBar 直接挂成子节点 —— 找它，不用反射也不用皮肤。 */
    private static ButtonBar buttonBarOf(DialogPane pane) {
        for (Node child : pane.getChildren()) {
            if (child instanceof ButtonBar bar) {
                return bar;
            }
        }
        return null;
    }

    /**
     * 弹窗不能比屏幕还高 —— 底部按钮（确定 / 取消）会跑到屏幕外，用户够不着。
     *
     * <p>留 96px 余量给标题栏与窗口装饰。
     */
    private static void clampToScreen(DialogPane pane, Window owner) {
        if (owner == null) {
            return;
        }
        javafx.geometry.Rectangle2D bounds = screenOf(owner);
        if (bounds == null || bounds.getHeight() <= 0) {
            return;
        }
        double max = bounds.getHeight() - 96;
        if (max > 200) {
            pane.setMaxHeight(max);
        }
    }

    /** 窗口所在的那块屏幕的边界（找不到就用主屏）。 */
    private static javafx.geometry.Rectangle2D screenOf(Window owner) {
        double x = owner.getX();
        double y = owner.getY();
        for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
            javafx.geometry.Rectangle2D bounds = screen.getBounds();
            if (bounds.contains(x, y)) {
                return bounds;
            }
        }
        return javafx.stage.Screen.getPrimary() == null
                ? null : javafx.stage.Screen.getPrimary().getBounds();
    }

    /** 拥有者窗口的样式表（主题表 + 业务表）。 */
    static List<String> stylesheetsOf(Window owner) {
        if (owner == null || owner.getScene() == null) {
            return List.of();
        }
        return List.copyOf(owner.getScene().getStylesheets());
    }

    /** 当前主题类（theme-light / theme-dark）；找不到返回 null。 */
    static String themeOf(Window owner) {
        if (owner == null || owner.getScene() == null || owner.getScene().getRoot() == null) {
            return null;
        }
        List<String> classes = owner.getScene().getRoot().getStyleClass();
        if (classes.contains(THEME_DARK)) {
            return THEME_DARK;
        }
        if (classes.contains(THEME_LIGHT)) {
            return THEME_LIGHT;
        }
        return null;
    }
}
