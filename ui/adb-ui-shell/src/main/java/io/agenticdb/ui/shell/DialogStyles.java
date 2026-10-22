package io.agenticdb.ui.shell;

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
 */
final class DialogStyles {

    private static final String THEME_LIGHT = "theme-light";
    private static final String THEME_DARK = "theme-dark";

    private DialogStyles() {
    }

    /**
     * 给弹窗套上应用样式与当前主题。
     *
     * @param dialog 目标弹窗
     * @param owner  拥有者窗口（用来取样式表与当前主题）；可为 null
     */
    static <T> void apply(Dialog<T> dialog, Window owner) {
        if (dialog == null) {
            return;
        }
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
