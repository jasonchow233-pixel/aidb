package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/**
 * ⭐ **统一的空状态** —— 图标 + 标题 + 说明 + 「下一步」按钮。
 *
 * <h2>为什么要有这个类</h2>
 * 空状态原本是 {@code WorkspaceView} 里的一个 private 方法，于是只有工作区能用；
 * 对象首页、数据页、连接页各自另写一套，于是出现了同一件事的三种长相：
 * 有的只有四个字「没有数据」，有的有图标没按钮，有的按钮文案是祈使句有的不是。
 *
 * <p>更根本的问题是：**空页面是用户最需要被引导的时刻**，
 * 却常常被写成"什么都没有"。用户看到空白，不知道下一步能干什么。
 *
 * <h2>规则（写新空态时按这个来）</h2>
 * <ol>
 *   <li><b>必须有图标</b> —— 32px，颜色交给 CSS（{@code .adb-empty__icon}）。</li>
 *   <li><b>标题说"这是什么状态"</b>，别说"暂无数据"这种黑话。</li>
 *   <li><b>说明说"为什么会这样 / 接下来能怎样"</b> —— 一句话，460px 内换行。</li>
 *   <li><b>必须给一个能点的出口</b>（{@code of} 五参版）。
 *       只有确实无处可去时才用三参版，且要在说明里讲清楚。</li>
 *   <li>按钮文案用<b>动词短语</b>（「新建连接」「打开查询页」），不用「点击这里」。</li>
 * </ol>
 *
 * <p>样式类：{@code adb-empty} / {@code adb-empty__icon} / {@code adb-empty__title} /
 * {@code adb-empty__hint}，由 {@code app.css} 统一控制，各处不要自己设颜色。
 */
final class EmptyState extends VBox {

    private EmptyState(Icons.Icon iconType, String titleText, String description) {
        SVGPath icon = Icons.of(iconType, Icons.SIZE_EMPTY);
        icon.getStyleClass().add("adb-empty__icon");

        Label title = new Label(titleText);
        title.getStyleClass().add("adb-empty__title");

        VBox box = new VBox(10, icon, title);
        if (description != null && !description.isBlank()) {
            Label desc = new Label(description);
            desc.getStyleClass().add("adb-muted");
            desc.setWrapText(true);
            desc.setMaxWidth(460);
            box.getChildren().add(desc);
        }
        getChildren().add(box);

        getStyleClass().add("adb-empty");
        setAlignment(Pos.CENTER);
        setPadding(new Insets(48));
    }

    /** 三参版：确实无处可去时用。说明里要讲清楚为什么。 */
    static EmptyState of(Icons.Icon iconType, String titleText, String description) {
        return new EmptyState(iconType, titleText, description);
    }

    /** ⭐ 五参版：带一个明确的下一步按钮。新空态优先用这个。 */
    static EmptyState of(Icons.Icon iconType, String titleText, String description,
                         String actionText, Runnable action) {
        EmptyState state = new EmptyState(iconType, titleText, description);
        if (actionText != null && !actionText.isBlank() && action != null) {
            state.action(actionText, action);
        }
        return state;
    }

    /** 追加一个「下一步」按钮（主按钮样式）。 */
    EmptyState action(String text, Runnable runnable) {
        Button button = new Button(text);
        button.getStyleClass().addAll("adb-button", "adb-button--primary");
        button.setOnAction(event -> runnable.run());
        ((VBox) getChildren().get(0)).getChildren().add(button);
        return this;
    }

    /** 追加一行次要提示（比说明更小，用来放快捷键之类的补充信息）。 */
    EmptyState hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("adb-muted", "adb-empty__hint");
        ((VBox) getChildren().get(0)).getChildren().add(label);
        return this;
    }
}
