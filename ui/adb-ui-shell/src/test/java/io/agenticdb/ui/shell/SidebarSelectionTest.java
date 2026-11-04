package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import io.agenticdb.ui.Tokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ UX-5：侧栏选中态的门禁。
 *
 * <p>规格：侧栏的连接/库/表选中后要和网格选中行**同一套语言** ——
 * {@code bg-selected} tint 填充 + 左侧 2px {@code accent} 强调条，
 * 不再画「蓝描边 + 外发光」那种焦点框（窄栏里一圈蓝框既抢注意力又吃掉文字宽度）。
 *
 * <p>⚠️ 三条门禁全部读**计算后的 CSS 值**（{@code getBackground()} / {@code getBorder()} /
 * {@code getEffect()}），所以 {@link #start} 必须把两套样式表挂上场景 ——
 * S-7 那轮的教训：样式表没挂时这些 getter 拿到的是节点默认值，断言等于没测。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(ApplicationExtension.class)
class SidebarSelectionTest {

    private SideBar sideBar;

    @Start
    void start(Stage stage) {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile demo = new ConnectionProfile("demo", "演示库",
                DialectKind.SQLITE, "localhost", 0, "db", "u", false, 0, "", java.util.Map.of());
        List<SchemaService.SchemaObject> objects = List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE));
        sideBar = new SideBar(actions, objects, "演示库", tab -> { }, message -> { },
                "demo", List.of(demo), profile -> { });

        Scene scene = new Scene(sideBar, 300, 600);
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        sideBar.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /** 选中节点 = 与网格选中行同一个 tint 令牌，不是「只有文字变粗」。 */
    @Test
    void theSelectedNodeIsFilledWithTheGridsSelectionTint(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        onFxRun(() -> {
            tree.getSelectionModel().select(0);
            tree.getFocusModel().focus(0);
        });

        TreeCell cell = cellAt(tree, 0);
        assertThat(cell).as("第一个连接节点必须已渲染成 cell").isNotNull();
        assertThat(cell.isSelected()).as("先确认选中状态本身成立").isTrue();

        assertThat(cell.getBackground()).as("CSS 没生效时这里是 null —— 断言会假绿").isNotNull();
        Paint paint = cell.getBackground().getFills().get(0).getFill();
        assertThat(paint)
                .as("侧栏选中必须用 bg-selected（与 .adb-grid .table-row-cell:selected 同一令牌）")
                .isEqualTo(Color.web(Tokens.BG_SELECTED_LIGHT));
    }

    /** 键盘焦点不再画蓝框 + 外发光；淡底留给「有焦点但没选中」这一态。 */
    @Test
    void theFocusedButUnselectedNodeHasNoBlueHalo(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        onFxRun(() -> {
            tree.getSelectionModel().select(0);
            tree.getFocusModel().focus(1);
        });
        // ⚠️ 必须让**格子自己**成为场景焦点拥有者：getFocusModel().focus(1) 只移动
        //    TreeView 内部的焦点索引，cell.isFocused() 仍然是 false（实测），
        //    那样下面两条断言测的是一个「根本没聚焦的格子」—— 门禁自己就是假的。
        TreeCell focused = cellAt(tree, 1);
        assertThat(focused).isNotNull();
        onFxRun(() -> focused.requestFocus());

        assertThat(focused.isFocused()).as("先确认焦点确实落在这一格").isTrue();
        assertThat(focused.isSelected()).as("它不能同时是选中格").isFalse();

        assertThat(focused.getEffect())
                .as("外发光（dropshadow 焦点环）必须去掉：窄栏里它既盖不住内容也不体面")
                .isNull();
        assertThat(borderPaint(focused))
                .as("焦点格不再描 border-focus 那圈蓝，改用淡底")
                .isNotEqualTo(Color.web(Tokens.BORDER_FOCUS_LIGHT));
        assertThat(focused.getBackground().getFills().get(0).getFill())
                .as("焦点格用 bg-active 淡底，键盘用户仍看得见焦点在哪")
                .isEqualTo(Color.web(Tokens.BG_ACTIVE_LIGHT));
    }

    /** 槽位常留：选中只上色不改宽度，否则选中一次文字就左右跳一次。 */
    @Test
    void selectingANodeDoesNotShiftItsText(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        TreeCell before = cellAt(tree, 0);
        double widthBefore = borderLeftWidth(before);

        onFxRun(() -> tree.getSelectionModel().select(0));
        TreeCell after = cellAt(tree, 0);

        assertThat(borderLeftWidth(after))
                .as("选中不能靠加粗边框表达 —— 边框槽位由基础规则常留，选中只换颜色")
                .isEqualTo(widthBefore);
    }

    /**
     * ⭐ H5-j（R62-a）：选中态是<b>贴着栏边的平色带</b>，不是圆角药丸 —— 并且
     * 左侧那 2px accent 强调条必须**真的画出来**。
     *
     * <p>这条只能读<b>像素</b>：{@code Background} 的 getter 只交出颜色，不交出
     * {@code -fx-background-insets} 与 {@code -fx-background-radius} —— 也就是
     * 「同一套语言那条门禁」（{@link #theSelectedNodeIsFilledWithTheGridsSelectionTint}）
     * 验不出形状，更验不出「有没有画」。所以对同一个格子取两帧快照（选中前 / 选中后），
     * 钉四个点（实测值，不是推算）：
     * <ul>
     *   <li>x=0 行中：accent #0078D7 —— 强调条真的在<b>左缘</b>。上一轮写的是
     *       {@code -adb-accent transparent transparent transparent}，四值顺序是<b>上右下左</b>，
     *       于是强调色挂在上边（而上下边框宽度都是 0），唯一的 2px 左槽是透明色 ——
     *       <b>整条强调条从来没存在过</b>，实测 x=0 读到的是 bg-selected；</li>
     *   <li>(4,0)、(最右,0)、(最右,最下)：都必须是 bg-selected —— 旧写法在这里读到的是
     *       没填充的透明底（内缩 4px + 圆角 6px 把这三点都切在形状外）。</li>
     * </ul>
     * 判定用「离哪个色更近」，避开抗锯齿的边界误差；四个点都另钉「选中前后读数不同」，
     * 否则样式表没挂上时两侧同为透明底，就近判定会假绿。
     */
    @Test
    void theSelectionBandIsFlatAndSpansTheRow(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        List<Color> before = onFx(() -> bandPixels(cellAtFx(tree, 0)));
        onFxRun(() -> {
            tree.getSelectionModel().select(0);
            tree.applyCss();
            tree.layout();
        });
        List<Color> after = onFx(() -> bandPixels(cellAtFx(tree, 0)));

        Color selected = Color.web(Tokens.BG_SELECTED_LIGHT);
        Color accent = Color.web(Tokens.ACCENT_LIGHT);
        assertThat(before).as("四个探针点都要读得到").hasSize(4);
        assertThat(after).hasSize(4);
        assertThat(after.get(0))
                .as("左缘 2px 必须真的是 accent 强调条 —— 实测 %s", after.get(0))
                .isEqualTo(accent);
        assertThat(closerTo(before.get(0), accent, selected))
                .as("选中前左缘不该有强调条（否则这条门禁是常亮，不是选中态）—— 实测 %s",
                        before.get(0))
                .isFalse();
        for (int i = 1; i < 4; i++) {
            assertThat(after.get(i))
                    .as("探针点 %s 必须因选中而改变（旧的内缩 4px + 圆角 6px 读到的是空底）",
                            PROBE_NAMES[i])
                    .isNotEqualTo(before.get(i));
            assertThat(closerTo(after.get(i), selected, before.get(i)))
                    .as("探针点 %s 选中后 %s 应更接近 bg-selected %s",
                            PROBE_NAMES[i], after.get(i), selected)
                    .isTrue();
        }
    }

    /**
     * ⭐ H5-j 的另一半：<b>粗体只留给「当前在用的库」</b>。
     *
     * <p>原来连接名与分组名都恒粗，侧栏里几乎每一行结构性节点都是粗的 ——
     * 于是粗体不再表达任何东西，而它本来是这里唯一说「我在哪个库」的线索。
     * 层级改由缩进 + 颜色承担（分组 text-secondary、连接 text-primary，颜色规则没动）。
     *
     * <p>⚠️ 三条都得先证明<b>标签真的在屏幕上</b>：读不到时 helper 交回空串，
     * 「不含 Bold」照样成立 —— 那等于门禁自己造假。
     */
    @Test
    void onlyTheCurrentDatabaseIsBold(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        onFxRun(() -> sideBar.setObjects("demo", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));
        onFxRun(() -> sideBar.setCatalogs("demo",
                List.of(new SideBar.CatalogEntry("maindb", "demo"))));
        // 虚拟化树里「没展开就没有 cell」：整棵展开才读得到分组那一层。
        onFxRun(() -> {
            expandAll(tree.getRoot());
            tree.applyCss();
            tree.layout();
        });

        List<String> styles = java.util.Arrays.asList(
                nameFontStyle(tree, "演示库"), nameFontStyle(tree, I18n.t("sidebar.group.tables")),
                nameFontStyle(tree, "maindb"));
        assertThat(styles).as("三个标签必须都渲染出来（读不到就是空串，断言会假绿）")
                .allMatch(style -> !style.isEmpty());

        assertThat(styles.get(0))
                .as("连接名不再恒粗 —— 它和「当前库」抢同一档强调；实测 %s", styles.get(0))
                .doesNotContain("Bold");
        assertThat(styles.get(1))
                .as("分组名不再恒粗 —— 层级由缩进与颜色承担；实测 %s", styles.get(1))
                .doesNotContain("Bold");
        assertThat(styles.get(2))
                .as("当前在用的库必须仍然是粗的 —— 这是这里唯一保留的粗体承诺；实测 %s", styles.get(2))
                .contains("Bold");
    }

    /**
     * ⭐ H5-k（R62-a）：展开箭头是<b>细 chevron</b>，不是 Modena 的实心小三角。
     *
     * <p>⚠️ {@code .arrow} 在 JavaFX 25 的 TreeViewSkin 里是个 <b>StackPane</b>，
     * {@code -fx-shape} 落到了它的 {@code shape}（遮罩）上，交回的是 {@code SVGPath} 而不是
     * {@code Path} —— 所以「数路径元素」这条路走不通，读 {@code getContent()} 的段数才是真的。
     *
     * <p>钉三件事，缺一件就是没钉住：
     * ① 形状有 <b>5</b> 段直线（细 chevron 的轮廓）—— Modena 的实心三角只有 2 段；
     * ② 箭头槽的<b>宽度仍是 6px</b> —— Modena 实测 6.0，第一版我们写成 {@code 4px 2px}
     *    把槽缩到 4.0，整棵树的缩进跟着左移 2px；本轮改成 {@code 4px 4px 4px 2px} 拿回 6.0；
     * ③ 展开态仍然转 90°，颜色仍是 text-tertiary（换形不许把转向和颜色一起换掉）。
     */
    @Test
    void theDisclosureArrowIsAChevronNotASolidTriangle(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        // 库节点当「有孩子」的那个格子：没有子节点就没有 disclosure，箭头根本不存在。
        onFxRun(() -> sideBar.setCatalogs("demo",
                List.of(new SideBar.CatalogEntry("maindb", "demo"))));
        onFxRun(() -> {
            expandAll(tree.getRoot());
            tree.applyCss();
            tree.layout();
        });
        List<Object> measured = onFx(() -> {
            TreeCell cell = cellWithDisclosure(tree);
            if (cell == null) {
                return List.of();
            }
            Node arrow = cell.lookup(".arrow");
            if (!(arrow instanceof javafx.scene.layout.Region region)) {
                return List.of();
            }
            String content = region.getShape() instanceof javafx.scene.shape.SVGPath svg
                    ? svg.getContent() : "";
            int lines = content.isBlank() ? -1
                    : content.length() - content.replace("L", "").length();
            Paint fill = region.getBackground() == null
                    || region.getBackground().getFills().isEmpty()
                    ? Color.TRANSPARENT : region.getBackground().getFills().get(0).getFill();
            return List.of(content, lines, region.prefWidth(-1), region.getRotate(), fill);
        });
        assertThat(measured)
                .as("树里必须有一个带展开箭头的格子（没有 disclosure 节点这条门禁就是空的）")
                .isNotEmpty();
        assertThat(((Number) measured.get(1)).intValue())
                .as("chevron 的轮廓是 5 段直线（Modena 实心三角只有 2 段）—— 实测形状 %s",
                        measured.get(0))
                .isGreaterThanOrEqualTo(5);
        assertThat(((Number) measured.get(2)).doubleValue())
                .as("箭头槽宽度必须还是 Modena 的 6px —— 槽变窄，整棵树的缩进跟着挪")
                .isEqualTo(6.0);
        assertThat(((Number) measured.get(3)).doubleValue())
                .as("展开态仍要转 90°")
                .isEqualTo(90.0);
        assertThat(measured.get(4))
                .as("只改形不改色：箭头填充仍是 text-tertiary %s", Color.web(Tokens.TEXT_TERTIARY_LIGHT))
                .isEqualTo(Color.web(Tokens.TEXT_TERTIARY_LIGHT));
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    /** 四个决定性像素：左缘强调条 + 三个「旧药丸形状切在外面」的角点。 */
    private static final String[] PROBE_NAMES =
            {"左缘 x=0", "(4,0)", "(最右,0)", "(最右,最下)"};

    private static List<Color> bandPixels(TreeCell cell) {
        Color[] empty = {Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT,
                Color.TRANSPARENT};
        if (cell == null) {
            return List.of(empty);
        }
        cell.applyCss();
        cell.layout();
        javafx.scene.image.WritableImage image =
                (javafx.scene.image.WritableImage) cell.snapshot(null, null);
        if (image == null) {
            return List.of(empty);
        }
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        int w = Math.max(1, (int) image.getWidth() - 1);
        int h = Math.max(1, (int) image.getHeight() - 1);
        int middle = Math.max(1, (int) (cell.getHeight() / 2));
        return List.of(reader.getColor(0, Math.min(middle, h)),
                reader.getColor(4, 0), reader.getColor(w, 0), reader.getColor(w, h));
    }

    /** 抗锯齿让边界像素不精确相等 —— 这里只问「离哪个色更近」。 */
    private static boolean closerTo(Color actual, Color a, Color b) {
        return distance(actual, a) <= distance(actual, b);
    }

    private static double distance(Color x, Color y) {
        return Math.abs(x.getRed() - y.getRed())
                + Math.abs(x.getGreen() - y.getGreen())
                + Math.abs(x.getBlue() - y.getBlue());
    }

    /** 取名字标签**计算后**的字体风格串（"Bold" / "Regular"…）；没渲染出来返回空串。 */
    private static String nameFontStyle(TreeView tree, String text) {
        return onFx(() -> {
            for (Node node : tree.lookupAll(".adb-tree__name")) {
                if (node instanceof javafx.scene.control.Label label
                        && text.equals(label.getText())) {
                    label.applyCss();
                    String style = label.getFont().getStyle();
                    return style == null ? "" : style;
                }
            }
            return "";
        });
    }

    /** ⚠️ 只在 FX 线程内调用：这里不能再走 {@link #onFx} —— 嵌套 runLater + waitForFxEvents 会拿到 null。 */
    private static TreeCell cellWithDisclosure(TreeView tree) {
        for (Node node : tree.lookupAll(".tree-cell")) {
            // 要**展开**的那一格：转向 90° 是 Modena 的 `.tree-cell:expanded` 给的，
            // 挑到收起格会把「没转向」读成「换形换坏了转向」。
            if (node instanceof TreeCell cell && cell.getItem() != null && isExpandedCell(cell)
                    && cell.lookup(".arrow") != null) {
                return cell;
            }
        }
        return null;
    }

    /** TreeCell 没有 isExpanded()：展开态只在 {@code :expanded} 伪类上。 */
    private static boolean isExpandedCell(TreeCell cell) {
        return cell.getPseudoClassStates().stream()
                .anyMatch(state -> "expanded".equals(state.getPseudoClassName()));
    }

    /** 递归展开：虚拟化树里「没展开就没有 cell」。 */
    private static void expandAll(javafx.scene.control.TreeItem<?> item) {
        item.setExpanded(true);
        for (javafx.scene.control.TreeItem<?> child : item.getChildren()) {
            expandAll(child);
        }
    }

    private static Paint borderPaint(TreeCell cell) {
        if (cell.getBorder() == null || cell.getBorder().getStrokes().isEmpty()) {
            return null;
        }
        return cell.getBorder().getStrokes().get(0).getLeftStroke();
    }

    private static double borderLeftWidth(TreeCell cell) {
        if (cell.getBorder() == null || cell.getBorder().getStrokes().isEmpty()) {
            return 0.0;
        }
        return cell.getBorder().getStrokes().get(0).getWidths().getLeft();
    }

    private static TreeView treeOf(SideBar sideBar) {
        return onFx(() -> (TreeView) sideBar.lookup(".adb-object-tree"));
    }

    private static TreeCell cellAt(TreeView tree, int index) {
        return onFx(() -> cellAtFx(tree, index));
    }

    /** ⚠️ 只能在 FX 线程内调用 —— {@link #cellAt} 自己走 onFx，嵌在 FX 任务里会拿到 null。 */
    private static TreeCell cellAtFx(TreeView tree, int index) {
        for (Node node : tree.lookupAll(".tree-cell")) {
            if (node instanceof TreeCell cell && cell.getIndex() == index) {
                return cell;
            }
        }
        return null;
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
