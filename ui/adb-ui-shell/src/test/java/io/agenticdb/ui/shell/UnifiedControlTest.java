package io.agenticdb.ui.shell;

import io.agenticdb.ui.Tokens;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U-CTRL 门禁：**通用控件必须真的按令牌画出来**。
 *
 * <h2>为什么钉计算值而不是钉 CSS 文本</h2>
 * 任务书 §七 要的是「同一套控件在全应用里长一个样」。CSS 里写了一行
 * {@code -fx-background-color} 不代表渲染出来是那个颜色 —— 这个模块踩过的坑全是
 * 「规则写了，但被别的层顶掉 / 被皮肤的隐式链路吃掉」：
 * <ul>
 *   <li>Modena 给所有 {@code :disabled} 控件加 {@code -fx-opacity: .4}，
 *       禁用的按钮整块看不见（{@link DisabledButtonVisibilityTest}）；</li>
 *   <li>{@code .list-cell} 一旦显式设成 transparent，
 *       Modena「-fx-background → -fx-selection-bar」那条隐式链就断了 ——
 *       列表点什么都选不中。只有计算值能发现这件事。</li>
 * </ul>
 * 所以这里量的是**皮肤节点上算出来的背景色、文字色与尺寸**。
 *
 * <h2>量什么</h2>
 * <ol>
 *   <li>复选框：方框定死 16×16、勾的**实际绘制区**不许被内边距挤没、整行命中区 ≥24px
 *       （任务书 §八）；常态 / 选中 / 按住 / 禁用四态的颜色全部来自令牌；</li>
 *   <li>列表：单元格字号落在 12px（数据文字统一档），选中底色 = 表格那张色卡；</li>
 *   <li>下拉：按钮面字号同一档；</li>
 *   <li>「选中」的颜色只许在 {@code .root} 定一次 —— 场景里各写一份就是第二种语言。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class UnifiedControlTest {

    private CheckBox plain;
    private CheckBox checked;
    private CheckBox disabled;
    private Button icon;
    private TextField field;
    private TextArea area;
    private ListView<String> list;
    private ComboBox<String> combo;

    @Start
    void start(Stage stage) {
        plain = new CheckBox("唯一索引");
        checked = new CheckBox("可空");
        checked.setSelected(true);
        disabled = new CheckBox("只读");
        disabled.setDisable(true);

        icon = new Button("刷新");
        icon.getStyleClass().add("adb-icon-button");
        icon.setDisable(true);

        field = new TextField();
        field.setPromptText("表名");
        area = new TextArea("多行摘要");
        area.setPrefRowCount(3);

        list = new ListView<>(FXCollections.observableArrayList("id", "name", "created_at"));
        list.getSelectionModel().select(0);

        combo = new ComboBox<>(FXCollections.observableArrayList("MySQL", "PostgreSQL"));
        combo.setValue("MySQL");

        VBox root = new VBox(8, plain, checked, disabled, icon, field, area, list, combo);
        Scene scene = new Scene(root, 420, 340);
        scene.getStylesheets().add(resource("/io/agenticdb/ui/theme-light.css"));
        scene.getStylesheets().add(resource("/io/agenticdb/ui/app.css"));
        // ⚠️ 主题类必须挂在根节点上 —— 主题是 .theme-light **类选择器**，
        //    不挂则 -adb-* 查找全部落空，断言会因「读到默认值」而假通过。
        root.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    // ─────────────────────────── 复选框：尺寸与命中区 ───────────────────────────

    @Test
    void theCheckBoxBoxIsSixteenPixelsWithAReadableTick() {
        // ⚠️ 勾量的是**布局之后的实际宽度**，不是 prefWidth：Modena 的勾是一段带
        //    -fx-shape 的 Region，prefWidth 永远等于图形天然宽度（约 16px），
        //    内边距吃掉多少它都反映不出来；只有父容器真正分给它的那几个像素才看得见「勾被挤没了」。
        double[] measured = onFx(() -> {
            Region box = box(plain);
            Region mark = mark(plain);
            box.layout();
            mark.layout();
            return new double[] {
                    box.prefWidth(-1), box.prefHeight(-1),
                    mark.getWidth(), mark.getHeight(),
                    plain.prefHeight(-1)};
        });

        assertThat(measured[0]).as("方框宽定死 16px —— 不许跟着字号缩").isEqualTo(16.0);
        assertThat(measured[1]).as("方框高定死 16px").isEqualTo(16.0);
        assertThat(measured[2])
                .as("勾的实际绘制区 ≥8px：内边距一大这里就塌成 0，复选框看着永远是空的")
                .isGreaterThanOrEqualTo(8.0);
        assertThat(measured[3]).as("勾的实际绘制区高 ≥8px").isGreaterThanOrEqualTo(8.0);
        assertThat(measured[4])
                .as("命中区 ≥24px：能点的不能只有那个 16px 的方框（任务书 §八）")
                .isGreaterThanOrEqualTo(24.0);

        assertThat(onFx(() -> box(plain).getPadding()))
                .as("方框内边距 2px —— 它决定勾剩多大")
                .isEqualTo(new Insets(2));
    }

    // ─────────────────────────── 复选框：五态颜色 ───────────────────────────

    @Test
    void uncheckedAndCheckedUseTheTokenColors() {
        Paint[] unchecked = onFx(() -> new Paint[] {fill(box(plain)), fill(mark(plain))});
        Paint[] selected = onFx(() -> new Paint[] {fill(box(checked)), fill(mark(checked))});

        assertThat(unchecked[0])
                .as("未选中：方框底 = bg-inset（第二十一轮无边框：白底上再画一个白方框等于看不见，"
                        + "凹陷色块才读得出「这里有一格没勾」；也不是 Modena 的四层渐变）")
                .isEqualTo(Color.web(Tokens.BG_INSET_LIGHT));
        assertThat(unchecked[1])
                .as("未选中：勾不许画出来")
                .isNull();

        assertThat(selected[0])
                .as("选中：方框底 = accent")
                .isEqualTo(Color.web(Tokens.ACCENT_LIGHT));
        assertThat(selected[1])
                .as("选中：勾 = 反色文字令牌（压在 accent 上才看得见）")
                .isEqualTo(Color.web(Tokens.TEXT_INVERSE_LIGHT));
    }

    @Test
    void theArmedBoxShiftsToTheActiveTint() {
        Paint armed = onFx(() -> {
            plain.arm();
            plain.applyCss();
            plain.layout();
            Color measured = fill(box(plain));
            plain.disarm();
            return measured;
        });

        assertThat(armed)
                .as("按住：底色 = bg-active（五态里必须有反馈，否则点下去像没反应）")
                .isEqualTo(Color.web(Tokens.BG_ACTIVE_LIGHT));
    }

    @Test
    void theDisabledBoxStaysVisibleAndLosesItsBlue() {
        Object[] state = onFx(() -> {
            Region box = box(disabled);
            disabled.applyCss();
            disabled.layout();
            return new Object[] {disabled.getOpacity(), fill(box), disabled.getTextFill(),
                    disabled.lookup(".text")};
        });

        assertThat((Double) state[0])
                .as("Modena 给 :disabled 整体压 0.4 —— 必须抵消，禁用感由颜色表达")
                .isEqualTo(1.0);
        assertThat(state[3])
                .as("复选框必须真的渲染出文字节点（皮肤里是 .text），否则下面这条颜色断言测的是空气")
                .isNotNull();
        assertThat(state[1])
                .as("禁用：方框底退到 bg-disabled")
                .isEqualTo(Color.web(Tokens.BG_DISABLED_LIGHT));
        assertThat(state[2])
                .as("禁用：文字 = text-disabled，不能是白字（白字白底 = 看不见）")
                .isEqualTo(Color.web(Tokens.TEXT_DISABLED_LIGHT));
    }

    @Test
    void theFocusedBoxKeepsItsShape() {
        // ⚠️ 先确认真的聚焦了：没聚焦时量到的是常态描边，这条门禁就成了摆设。
        boolean focused = onFx(() -> {
            plain.requestFocus();
            return plain.isFocused();
        });
        assertThat(focused).as("先确认焦点确实落在这个复选框上").isTrue();

        double[] geometry = onFx(() -> {
            Region box = box(plain);
            return new double[] {borderWidth(box), box.getWidth(), box.getHeight()};
        });
        assertThat(geometry[0])
                .as("焦点环只换描边颜色：宽度一改变成「聚焦 = 整行抖 1px」")
                .isEqualTo(1.0);
        assertThat(geometry[1]).as("聚焦不改变方框宽度").isEqualTo(16.0);
        assertThat(geometry[2]).as("聚焦不改变方框高度").isEqualTo(16.0);
    }

    // ─────────────────────────── 列表与下拉 ───────────────────────────

    @Test
    void listCellsUseTheCompactBodySizeAndTheRowSelectionTint() {
        // ⚠️ 字号只能从 Labeled 上读：Region 没有公开的 getFont()。
        //    ListCell extends Cell extends Labeled，所以一格既是 Region（能读背景）
        //    也是 Labeled（能读字号）。
        List<Object[]> cells = onFx(() -> {
            List<Object[]> measured = new ArrayList<>();
            for (ListCell<?> cell : cells(list)) {
                measured.add(new Object[] {cell.getFont(), fill(cell)});
            }
            return measured;
        });
        assertThat(cells).as("列表至少要渲染出一个单元格").isNotEmpty();

        List<Paint> fills = new ArrayList<>();
        for (Object[] cell : cells) {
            assertThat(((Font) cell[0]).getSize())
                    .as("列表正文与表格同一档：12px（跟着 .root 的 13px 就两种密度混排）")
                    .isEqualTo(12.0);
            fills.add((Paint) cell[1]);
        }
        assertThat(fills)
                .as("选中格底色 = bg-selected（与表格行、侧栏选中同一张色卡；"
                        + "若 .list-cell 被压成 transparent 而选中态没显式补，这里就找不到它）")
                .contains(Color.web(Tokens.BG_SELECTED_LIGHT));
    }

    @Test
    void theComboBoxButtonFaceUsesTheSameScale() {
        List<Double> sizes = onFx(() -> {
            List<Double> measured = new ArrayList<>();
            for (ListCell<?> cell : cells(combo)) {
                measured.add(cell.getFont().getSize());
            }
            return measured;
        });
        assertThat(sizes).as("下拉按钮面里的单元格").isNotEmpty();
        assertThat(sizes.get(0))
                .as("下拉正文 12px")
                .isEqualTo(12.0);
    }

    // ─────────────────────────── CSS 结构 ───────────────────────────

    @Test
    void selectionColorIsDeclaredOnceAtTheRoot() throws IOException {
        String css = appCss();
        assertThat(count(css, "-fx-selection-bar:"))
                .as("「选中」的颜色只在 .root 定一次；场景里再各写一份 = 第二种语言，"
                        + "而列表 / 下拉 / 树读不到那些场景规则")
                .isEqualTo(1);
        assertThat(count(css, "-fx-selection-bar-non-focused:"))
                .as("非焦点档同上")
                .isEqualTo(1);
    }

    @Test
    void everyControlStateHasADeclaration() throws IOException {
        String css = appCss();
        for (String selector : List.of(
                ".check-box:hover > .box",
                ".check-box:armed > .box",
                ".check-box:selected > .box",
                ".check-box:focused > .box",
                ".check-box:disabled > .box",
                ".list-view .list-cell:filled:hover",
                ".list-view .list-cell:filled:selected",
                ".combo-box-popup .list-cell:hover",
                ".combo-box-popup .list-cell:filled:selected")) {
            assertThat(declares(css, selector))
                    .as("五态缺一条就不算统一：%s", selector)
                    .isTrue();
        }
        assertThat(ruleBody(css, ".check-box:focused > .box"))
                .as("复选框焦点环只换描边色 + 光晕，不改宽度")
                .contains("-fx-border-color: -adb-border-focus")
                .doesNotContain("-fx-border-width");
        // U23：暗色实拍里未勾选方框和面板融在一起（p5-dark「只看收藏」）——
        // 描边必须落在 text-tertiary 这一档，border 档（暗色 1.3:1）等于没画。
        assertThat(ruleBody(css, ".check-box > .box"))
                .as("未勾选描边用 text-tertiary（任务书 §3.2），不许退回 border/border-subtle")
                .contains("-fx-border-color: -adb-text-tertiary");
        assertThat(ruleBody(css, ".check-box:disabled > .box"))
                .as("禁用 ≠ 消失：描边退到 text-disabled 档，不许置透明")
                .contains("-fx-border-color: -adb-text-disabled")
                .doesNotContain("-fx-border-color: transparent");
    }

    // ─────────────────────────── 按钮（任务书 §七：5 变体 × 5 态）───────────

    /**
     * 五种按钮都要凑齐五态。
     *
     * <p>变体（primary / ghost / danger）只负责「颜色语言」，常态尺寸与
     * 悬停 / 按下 / 禁用 / 焦点**继承基础类** —— 但继承必须真的成立：
     * 基础类少写一条，三种变体跟着一起少。变异：删掉 {@code .adb-button:pressed}
     * → 四条断言同时红。
     */
    @Test
    void everyButtonVariantHasAllFiveStates() throws IOException {
        String css = appCss();
        assertThat(declares(css, ".adb-button")).as("按钮常态（尺寸 / 内边距 / 字号）").isTrue();
        assertThat(declares(css, ".adb-icon-button")).as("图标按钮常态").isTrue();

        for (String variant : List.of(
                ".adb-button--primary", ".adb-button--ghost", ".adb-button--danger")) {
            assertThat(declares(css, variant)).as("%s 自己的常态规则", variant).isTrue();
        }
        // 图标按钮的变体走同一套颜色语言（DataGridPage 的行内操作条就这么挂）。
        for (String base : List.of(".adb-button", ".adb-icon-button")) {
            for (String state : List.of(":hover", ":pressed", ":disabled", ":focused")) {
                assertThat(declares(css, base + state))
                        .as("%s 缺 %s —— 四种按钮一起没有这态反馈", base, state)
                        .isTrue();
            }
        }
        assertThat(ruleBody(css, ".adb-icon-button:disabled"))
                .as("禁用的图标按钮要有自己的文字色：只有 -fx-opacity: 1 等于「禁用和可用长一样」")
                .contains("-adb-text-disabled");

        // ⭐ 实心变体必须有**自己的**按下态。它们的 :hover 排在基础 .adb-button:pressed
        //    之后（同特异度后写胜），不补一条的话按住时看到的仍是悬停色 —— 点了没反应；
        //    更要命的是 primary：基础按下态是 10% 蓝的浅底，白字压上去直接糊掉。
        for (String variant : List.of(".adb-button--primary", ".adb-button--danger")) {
            assertThat(declares(css, variant + ":pressed"))
                    .as("%s 缺独立按下态", variant)
                    .isTrue();
            assertThat(css.indexOf(variant + ":pressed"))
                    .as("%s 的 :pressed 必须排在它的 :hover 之后，否则被悬停顶掉", variant)
                    .isGreaterThan(css.indexOf(variant + ":hover"));
        }
    }

    /**
     * 挂变体类必须同时挂基础类。
     *
     * <p>实测踩过：连接页的「新建连接」只写了 {@code adb-button--primary}，
     * 于是它拿不到 28px 行高、12px 内边距和按下 / 焦点反馈 ——
     * 全应用唯一一颗尺寸不同的主按钮，正是任务书 §七 禁止的「各页面自己定样式」。
     */
    @Test
    void everyVariantCarriesItsBaseClass() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher calls = Pattern.compile("getStyleClass\\(\\)\\.(add|addAll)\\(([^)]*)\\)")
                    .matcher(text);
            while (calls.find()) {
                String arguments = calls.group(2);
                boolean hasVariant = Pattern.compile("\"adb-button--(primary|ghost|danger)\"")
                        .matcher(arguments)
                        .find();
                boolean hasBase = arguments.contains("\"adb-button\"")
                        || arguments.contains("\"adb-icon-button\"");
                if (hasVariant && !hasBase) {
                    offenders.add(file.getFileName() + " → " + arguments.trim());
                }
            }
        }
        assertThat(offenders)
                .as("只挂变体不挂基础类的按钮（拿不到统一尺寸与五态）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    /** 禁用的图标按钮：看得见、但明显不可用。 */
    @Test
    void theDisabledIconButtonIsGreyButPresent() {
        Object[] state = onFx(() -> {
            icon.applyCss();
            icon.layout();
            return new Object[] {icon.getOpacity(), icon.getTextFill()};
        });
        assertThat((Double) state[0]).as("不能被 Modena 压成 40%").isEqualTo(1.0);
        assertThat(state[1])
                .as("禁用图标按钮文字退到 text-disabled（与可用态区分得开）")
                .isEqualTo(Color.web(Tokens.TEXT_DISABLED_LIGHT));
    }

    /**
     * 单行输入框与按钮同高，多行 TextArea 保留自己的算法。
     *
     * <p>任务书 §七 把「高度」列进输入框的统一项：以前只统一了字号 / 内边距 /
     * 边框 / 圆角，高度是「字号 + 内边距」自己算出来的（约 25px），
     * 和并排的按钮（28px）差 3px —— 弹窗里输入框 + 按钮一排，基线永远不齐。
     */
    @Test
    void singleLineInputsShareTheButtonHeightWhileTextAreaKeepsItsOwn() {
        double[] heights = onFx(() -> new double[] {
                prefHeight(field), prefHeight(icon), prefHeight(combo), prefHeight(area)});

        assertThat(heights[0]).as("输入框 = 28px（h-control 档）").isEqualTo(28.0);
        assertThat(heights[0]).as("输入框和按钮必须同高，否则并排时基线不齐")
                .isEqualTo(heights[1]);
        assertThat(heights[2]).as("下拉也是同一档").isEqualTo(heights[0]);
        assertThat(heights[3])
                .as("TextArea 不许被钉成 28 —— 它的高度由 setPrefRowCount 算，钉死会把多行压成一行")
                .isGreaterThan(28.0);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 在 FX 线程上跑一段并取值。
     *
     * <p>本包惯例是 {@code Platform.runLater + waitForFxEvents}（见
     * {@link SidebarSelectionTest}）；TestFX 4.x 的 {@code interact(Callable)}
     * 只回传机器人本身，不返回值，所以自己把结果带回来。
     */
    private static <T> T onFx(Callable<T> action) {
        Object[] holder = new Object[1];
        Throwable[] failure = new Throwable[1];
        Platform.runLater(() -> {
            try {
                holder[0] = action.call();
            } catch (Throwable e) {
                // ⚠️ 连 AssertionError 一起带回测试线程：吞在 FX 线程里
                //    只会打印一行栈，测试却「通过」—— 那是假绿。
                failure[0] = e;
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        if (failure[0] instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        @SuppressWarnings("unchecked")
        T value = (T) holder[0];
        return value;
    }

    /** 取方框 {@code .box}（必须在 FX 线程上调）。 */
    private static Region box(CheckBox checkBox) {
        return (Region) skinned(checkBox, ".box");
    }

    /** 取勾 {@code .mark}（必须在 FX 线程上调）。 */
    private static Region mark(CheckBox checkBox) {
        return (Region) skinned(checkBox, ".mark");
    }

    /** applyCss + layout 之后才存在皮肤子节点；找不到就是皮肤换了形态。 */
    private static Node skinned(Parent node, String selector) {
        node.applyCss();
        node.layout();
        Node found = node.lookup(selector);
        assertThat(found).as("皮肤里找不到 %s（改皮肤就会红在这里）", selector).isNotNull();
        return found;
    }

    private static List<ListCell<?>> cells(Region control) {
        control.applyCss();
        control.layout();
        List<ListCell<?>> result = new ArrayList<>();
        for (Node node : control.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                result.add(cell);
            }
        }
        assertThat(result)
                .as("%s 里一个渲染好的 .list-cell 都没有 —— 断言会假绿", control.getStyleClass())
                .isNotEmpty();
        return result;
    }

    /** 计算出来的第一层背景色；没有可见背景（null / 空填充 / 全透明）时返回 null。 */
    private static Color fill(Region region) {
        Background background = region.getBackground();
        if (background == null || background.getFills().isEmpty()) {
            return null;
        }
        BackgroundFill first = background.getFills().get(0);
        Paint paint = first.getFill();
        if (paint instanceof Color color && color.getOpacity() > 0) {
            return color;
        }
        return null;
    }

    /** 算好样式之后的首选高度（必须在 FX 线程上调）。 */
    private static double prefHeight(Region region) {
        region.applyCss();
        region.layout();
        return region.prefHeight(-1);
    }

    /** 算出来的左边框宽度；没有描边时 0。 */
    private static double borderWidth(Region region) {
        var border = region.getBorder();
        if (border == null || border.getStrokes().isEmpty()) {
            return 0.0;
        }
        return border.getStrokes().get(0).getWidths().getLeft();
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** 规则头可能是**分组选择器**（{@code .a:selected, .a:selected:focused {}}），不能只找 {@code selector + " {"}。 */
    private static boolean declares(String css, String selector) {
        return Pattern.compile(Pattern.quote(selector) + "\\s*[{,]").matcher(css).find();
    }

    /**
     * 取某个选择器规则块的正文。
     *
     * <p>⚠️ 只认**紧跟着花括号**的那一处：{@code .a:disabled} 既可能是分组规则里的一项
     * （{@code .a:disabled, .b:disabled { -fx-opacity: 1; }}），也可能自己是一条独立规则，
     * 取前者会读到别人的正文。
     */
    private static String ruleBody(String css, String selector) {
        Matcher matcher = Pattern.compile(Pattern.quote(selector) + "\\s*\\{").matcher(css);
        assertThat(matcher.find()).as("app.css 里找不到独立规则 %s", selector).isTrue();
        int end = css.indexOf('}', matcher.start());
        assertThat(end).as("规则 %s 没有闭合", selector).isNotNegative();
        return css.substring(matcher.end(), end);
    }

    /** 扫描主源码（测试代码不算）：按钮类名是挂在 Java 里的，门禁得去那边查。 */
    private static List<Path> mainSources() throws IOException {
        Path directory = Path.of("src", "main", "java");
        if (!Files.isDirectory(directory)) {
            directory = Path.of("ui", "adb-ui-shell", "src", "main", "java");
        }
        assertThat(directory).as("主源码目录").exists();
        Path root = directory;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String appCss() throws IOException {
        try (InputStream in = UnifiedControlTest.class
                .getResourceAsStream("/io/agenticdb/ui/app.css")) {
            assertThat(in).as("app.css").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    private static String resource(String path) {
        URL url = UnifiedControlTest.class.getResource(path);
        assertThat(url).as("样式资源 %s", path).isNotNull();
        return url.toExternalForm();
    }
}
