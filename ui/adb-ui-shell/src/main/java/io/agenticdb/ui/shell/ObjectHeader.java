package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

/**
 * ⭐ 对象标题栏（§6.1）。
 *
 * <p>回答三个问题，一眼之内：
 * <ol>
 *   <li><b>这是什么</b> —— 类型图标 + 对象名 + 对象类型；</li>
 *   <li><b>有多大</b> —— 记录数（拿不到就明说「行数未知」，绝不编数字）；</li>
 *   <li><b>能对它做什么</b> —— 右侧动作按钮。</li>
 * </ol>
 *
 * <p>数据页与结构页共用这一个组件 —— 同一类东西在两个页面里长得不一样，
 * 是「不统一」最直接的来源。
 */
public final class ObjectHeader extends HBox {

    private final SVGPath icon;
    private final Label title = new Label();
    private final Label type = new Label();
    private final Label count = new Label();
    private final Label context = new Label();
    private final HBox actions = new HBox(4);
    /** ⭐ 切面条挂在标题栏同一行（§6.2）—— 单独占一行太费垂直空间。 */
    private final javafx.scene.layout.StackPane tabHost = new javafx.scene.layout.StackPane();

    /**
     * @param iconType  对象类型图标（表 / 视图 / 过程…）
     * @param name      对象名
     * @param typeLabel 对象类型文案（「表」/「视图」）
     */
    public ObjectHeader(Icons.Icon iconType, String name, String typeLabel) {
        getStyleClass().add("adb-object-header");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);

        icon = Icons.of(iconType, Icons.SIZE_TOOLBAR);
        icon.getStyleClass().add("adb-object-header__icon");

        title.setText(name);
        // ⚠️ 保留 .adb-page__title —— 门禁与既有测试按这个类找标题。
        title.getStyleClass().addAll("adb-page__title", "adb-object-header__title");

        type.setText(typeLabel);
        type.getStyleClass().addAll("adb-badge", "adb-badge--muted", "adb-object-header__type");
        // 提示挂在徽标上（SVGPath 是 Shape，挂不了 Tooltip）。
        type.setTooltip(new Tooltip(typeLabel));

        count.getStyleClass().add("adb-object-header__count");
        count.setText(I18n.t("details.rows.unknown"));

        context.getStyleClass().addAll("adb-muted", "adb-object-header__context");

        // ⭐ 右侧详情抽屉打开时标题栏只剩 ~700px：HBox 会把所有 Label 按比例
        //    压缩，连对象名和切面一起缩成「…」。这里钉死优先级 ——
        //    类型 / 行数 / 切面 / 动作按钮**永不缩写**，对象名保底，只有
        //    「连接名 · 读写」这段辅助信息可以让位（它是最先可以放弃的）。
        title.setMinWidth(60);
        type.setMinWidth(Region.USE_PREF_SIZE);
        count.setMinWidth(Region.USE_PREF_SIZE);
        tabHost.setMinWidth(Region.USE_PREF_SIZE);
        context.setMinWidth(0);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("adb-object-header__actions");

        tabHost.setId("object-header-tabs");
        tabHost.getStyleClass().add("adb-object-header__tabs");
        // 切面多 / 窗口窄时**横向滚动**，而不是被挤到溢出。
        tabHost.setMaxWidth(520);
        tabHost.setAlignment(Pos.CENTER_LEFT);
        // 切面条紧跟在对象名后面 —— 它说的是「这个对象的不同切面」，
        // 隔着一整个空白跑到右边，视觉上就跟标题断开了。
        HBox.setMargin(tabHost, new Insets(0, 8, 0, 8));

        getChildren().addAll(icon, title, type, count, tabHost, spacer, context, actions);
    }

    /**
     * 把切面条挂进标题栏（同一行）。
     *
     * <p>⛔ 不传 {@code null}：那会让标题栏留一块空洞。
     */
    public void setTabs(Node tabs) {
        tabHost.getChildren().clear();
        if (tabs == null) {
            return;
        }
        if (tabs instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        tabHost.getChildren().add(tabs);
    }

    /**
     * 设置记录数。
     *
     * @param text 已知的记录数文案（「200 行」）；传 null 显示「行数未知」。
     */
    public void setCount(String text) {
        count.setText(text == null || text.isBlank() ? I18n.t("details.rows.unknown") : text);
    }

    /**
     * 隐藏记录数。
     *
     * <p>结构页没有「行数」这回事 —— 显示「行数未知」只会让人以为功能坏了。
     */
    public void hideCount() {
        count.setVisible(false);
        count.setManaged(false);
    }

    /** 更新对象类型文案（结构页要等内省结果出来才知道是表还是视图）。 */
    public void setType(String text) {
        type.setText(text);
        type.setTooltip(new Tooltip(text));
    }

    /** 设置右侧的辅助信息（连接名 · 可读/只读）。 */
    public void setContext(String text) {
        context.setText(text == null ? "" : text);
    }

    /** 追加一个动作按钮。 */
    public void addAction(Button button) {
        actions.getChildren().add(button);
    }

    /** 追加一组动作按钮。 */
    public void addActions(Button... buttons) {
        for (Button button : buttons) {
            actions.getChildren().add(button);
        }
    }
}
