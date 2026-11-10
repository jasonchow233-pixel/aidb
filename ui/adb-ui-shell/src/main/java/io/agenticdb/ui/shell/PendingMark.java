package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * ⭐ R62-a H5-i（拍板 Q8 第②案）：「本版本未提供」的统一角标。
 *
 * <p>要解决的问题：功能区曾有三格（Function / Automation / Model，其余格陆续接上
 * 真工具页、BI 已整体移除）和对象清单页的「用 AI 新建表」——它们背后没有能力面，
 * 点了落的是如实说明页。
 * R36 定的是「不许装死按钮」，落点确实是实话；但**界面上没有任何东西提前说实话**，
 * 用户只有点下去、读完一页说明才知道这格现在没用。
 *
 * <p>三条候选里 Q8 拍的是第②条：
 * <ul>
 *   <li>①置灰 —— 否。灰 = 「条件不满足」，而这里是「功能不存在」，两种意思我们
 *       已经分别用别的通道表达了；而且 {@code disableProperty} 绑的是动作本身，
 *       硬灰会把真实的可用性覆盖掉。</li>
 *   <li>②留在原位 + 一枚统一淡角标 —— <b>采用</b>。位置不动意味着用户学过的
 *       「Backup 在功能区第二段」这条地图不会随版本反复变；角标说的是现在这一刻。</li>
 *   <li>③收进「其他」—— 否。藏起来不是诚实，是把「没有」变成「找不到」。</li>
 * </ul>
 *
 * <p>为什么是**空心环**：点（{@code adb-tree__dot}）在我们这儿已经是「在线 / 离线」
 * 那一套词汇，同一个 UI 里一枚灰点和一枚绿点必须说同一类事实。环和点形状不同，
 * 也不表达任何一种状态，配上 Tooltip 与 accessibleText 才是「未提供」这句话。
 *
 * <p>⚠️ 三条不能踩的线：
 * <ol>
 *   <li>不许用 {@code -fx-opacity} 压暗 —— {@code FocusRingAuditTest} 只管得住 CSS，
 *       Java 侧同样不该动透明度，颜色只从令牌来；</li>
 *   <li>角标不许参与排版：环挂在宿主排上、{@code managed=false}，
 *       <b>不进任何宽高计算</b>。前两种写法都被实测打回过 —— 把图形裹一层
 *       {@code StackPane}（哪怕钉成图标尺寸）会让这六格的标签比另外六格差 ±6~7px，
 *       一排磁贴读成两排；</li>
 *   <li>环必须 {@code mouseTransparent} —— 它压在按钮的角上，不许吃掉点击。</li>
 * </ol>
 */
final class PendingMark {

    /** 角标本体的样式类（颜色与粗细在 {@code app.css} 的 {@code .adb-pending__mark}）。 */
    static final String MARK_CLASS = "adb-pending__mark";

    /** 环的直径：与状态点同一档（8px），但形状不同 —— 见类注释「为什么是空心环」。 */
    private static final double SIZE = 8;

    /** 环离那一格右上角的内缩：贴着边画会有一半被裁掉，贴不进「角」这个语义。 */
    private static final double EDGE_INSET = 3;

    private PendingMark() { }

    /**
     * 给每格目标按钮的右上角挂一枚环。
     *
     * <p>环是<b>宿主排</b>的孩子，不是按钮的孩子：Button 只有一个 graphic 槽，
     * 而 graphic 里加任何东西都会改到那一排的宽高（类注释第②条）。
     *
     * <p>⚠️ 位置和尺寸靠<b>监听 + resizeRelocate</b>，不靠绑定，也不靠排版：
     * {@code managed=false} 的孩子不参与父容器的排版，也就是说<b>没人给它算宽高</b> ——
     * {@code Region} 的宽高只能由布局写，绑定 {@code layoutX} 又会在下一次排版时撞上
     * 「写一个已绑定的值」。所以这里显式盯住按钮的 layoutBounds 与 layoutX/Y，
     * 三个里任何一个变了就把环按真实尺寸（8×8）重新摆一次。
     * 磁贴排可以横向滚动，窄窗口下按钮挪到哪，环就跟到哪。
     */
    static void attach(Pane host, Iterable<? extends Button> targets) {
        for (Button target : targets) {
            Region mark = new Region();
            mark.getStyleClass().add(MARK_CLASS);
            // 几何在 Java 钉，颜色与粗细归 CSS —— 没挂样式表的测试场景也要量得到真实
            // 尺寸与位置，否则门禁读到的是一个 0×0 的假角标。
            mark.setMinSize(SIZE, SIZE);
            mark.setPrefSize(SIZE, SIZE);
            mark.setMaxSize(SIZE, SIZE);
            mark.setManaged(false);
            mark.setMouseTransparent(true);
            mark.setAccessibleText(I18n.t("ribbon.pending"));
            host.getChildren().add(mark);
            Runnable place = () -> mark.resizeRelocate(
                    target.getLayoutX() + target.getWidth() - (SIZE + EDGE_INSET),
                    target.getLayoutY() + EDGE_INSET, SIZE, SIZE);
            target.layoutBoundsProperty().addListener((obs, old, now) -> place.run());
            target.layoutXProperty().addListener((obs, old, now) -> place.run());
            target.layoutYProperty().addListener((obs, old, now) -> place.run());
            place.run();
        }
    }

    /** 提示语：原文案 + 「本版本未提供」，Tooltip 与 accessibleText 同一句。 */
    static String hint(String label) {
        return (label == null ? "" : label + " · ") + I18n.t("ribbon.pending");
    }

    /**
     * 只把话说在前头，<b>不</b>挂角标 —— 横排工具条按钮用这一档。
     *
     * <p>为什么这里没有环：工具条按钮的图形槽由 ButtonSkin 按图形的首选宽算，
     * 包一层容器就把这颗按钮撑宽，同排另外六颗跟着左右挪。实话仍然要说，
     * 说的是<b>同一句</b>（同一个资源键 {@code ribbon.pending}）。
     */
    static void saySo(ButtonBase button) {
        button.setAccessibleText(hint(button.getAccessibleText()));
        button.getTooltip().setText(hint(button.getTooltip().getText()));
    }

    /**
     * ⭐ D-61：页内假按钮的完整一档 —— **环 + 实话**一起上。
     *
     * <p>功能区六格有环（attach），此前页内九处只有落点说明页：Q8 拍板的是
     * 「入口也要诚实」，环不吃排版（挂在宿主排、{@code managed=false}），
     * 工具条那一排挂环不会把按钮撑宽 —— 类注释第②条防的是 graphic 槽包容器，
     * 不是宿主排上的孩子。
     */
    static void mark(Pane host, Button target) {
        attach(host, List.of(target));
        saySo(target);
    }
}
