package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableCell;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5-15 / 4.5-5 / 4.5-6 的门禁：图标控件不能被读屏软件读成「按钮」。
 *
 * <p>规则（与 docs/UI-GUIDE.md 一致）：一个纯图标控件必须同时具备
 * {@code Tooltip}（鼠标可发现）与 {@code accessibleText}（读屏可朗读）。
 * 只满足其中一个视为不合格 —— Tooltip 读屏读不到，accessibleText 鼠标看不到。
 *
 * <p>豁免两类：
 * <ul>
 *   <li>装饰性图标：同一个容器里已有文字标签（图标 + 文字组合）；</li>
 *   <li>表格单元格内的字形（✓ / ✗ / NULL 等），它们渲染的是数据不是控件。</li>
 * </ul>
 */
@ExtendWith(ApplicationExtension.class)
class IconAuditTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        Preferences preferences = Preferences.userNodeForPackage(SettingsStore.class);
        preferences.put("ui.theme", "light");
        preferences.putBoolean("ui.sidebar.collapsed", false);
        new App().start(stage);
    }

    /**
     * ⭐ R34 N1-b：语言包里的 {@code &} 不许被 JavaFX 当成助记符吃掉。
     *
     * <p>Navicat 自己工具条上的词就是 "Filter &amp; Sort"。而 JavaFX 的 {@code Labeled}
     * 默认开着助记符解析，于是 "Filter &amp; Sort" 会画成 "Filter&nbsp;&nbsp;Sort" 并给 S
     * 加一条下划线 —— <b>文字被吃掉一个字符，但 {@code getText()} 仍然返回原文</b>，
     * 所以任何只比字符串的门禁都看不见它，只有帧能看见。
     *
     * <p>三段各管一件事：① 资源里确实有 {@code &}（否则这条门禁是空气）；
     * ② 造文字控件的工厂关掉了助记符；③ 场景里现存每一个带 {@code &} 的控件都关掉了
     * —— 第 ③ 段是给未来漏网的调用点准备的（绕过 {@code IconButtons} 直接 {@code new Button}）。
     */
    @Test
    void ampersandsFromTheLanguagePackAreNotEatenAsMnemonics(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        List<String> withAmpersand = io.agenticdb.ui.I18n.keys(Locale.ENGLISH).stream()
                .filter(key -> key.startsWith("grid."))
                .filter(key -> I18n.t(key).indexOf('&') >= 0)
                .sorted()
                .toList();
        assertThat(withAmpersand)
                .as("grid 资源里一个含 & 的词都没有 —— 这条门禁证不了任何事（Navicat 的原词是 Filter & Sort）")
                .isNotEmpty();

        List<Boolean> parsed = onFx(() -> List.of(
                IconButtons.of(Icons.Icon.SAVE, "Filter & Sort").isMnemonicParsing(),
                IconButtons.labeled(new javafx.scene.control.MenuButton(), Icons.Icon.FILTER,
                        "Filter & Sort", "hint").isMnemonicParsing()));
        assertThat(parsed)
                .as("IconButtons 造出来的文字控件必须关掉助记符解析（true = 会吃字符）")
                .containsOnly(false);

        List<String> violations = onFx(() -> {
            List<String> found = new ArrayList<>();
            walk(stage.getScene().getRoot(), node -> {
                if (node instanceof Labeled labeled && labeled.getText() != null
                        && labeled.getText().indexOf('&') >= 0 && labeled.isMnemonicParsing()) {
                    found.add(describe(node, labeled.getText()));
                }
            });
            return found;
        });
        assertThat(violations)
                .as("场景里带 & 的控件还开着助记符解析（会少一个字符 + 多一条下划线）：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    void iconOnlyChromeDeclaresTooltipAndAccessibleText(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        List<String> violations = onFx(() -> audit(stage.getScene().getRoot()));

        assertThat(violations)
                .as("纯图标控件必须同时带 Tooltip（鼠标）与 accessibleText（读屏）：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    void iconOnlyChromeOfOpenTabsDeclaresTooltipAndAccessibleText(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        // 数据页：侧栏双击 users 打开。
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();
        List<String> gridViolations = onFx(() -> audit(stage.getScene().getRoot()));

        // 查询页：工具栏新建查询。
        robot.clickOn("#ribbon-new-query");
        WaitForAsyncUtils.waitForFxEvents();
        List<String> queryViolations = onFx(() -> audit(stage.getScene().getRoot()));

        assertThat(gridViolations).as("数据页图标控件：\n  %s",
                String.join("\n  ", gridViolations)).isEmpty();
        assertThat(queryViolations).as("查询页图标控件：\n  %s",
                String.join("\n  ", queryViolations)).isEmpty();
    }

    @Test
    void railIconsAreUniquePerContainer(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        Map<String, List<String>> collisions = onFx(() -> {
            Map<String, List<String>> found = new LinkedHashMap<>();
            collectGlyphCollisions(stage.getScene().getRoot(), found);
            return found;
        });

        assertThat(collisions)
                .as("同一容器里两个不同动作不能共用同一个字形：\n  %s", collisions)
                .isEmpty();
    }

    /**
     * 4.5-5 的补齐：<b>一个符号只表一个意思</b>。
     *
     * <p>旧版只查「图标控件有没有 Tooltip」，查不出「⚙ 既表示结构又表示设置」——
     * 数据页的「⚙ 结构」按钮和设置页的「⚙」用的是同一个字形，用户分不出。
     * 现在受管符号在整个应用里只能对应一种语义。
     */
    @Test
    void managedSymbolsMeanExactlyOneThing(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        // 先把数据页也打开，让数据页的「结构」按钮进入审计范围。
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        Map<String, Set<String>> meanings = onFx(() ->
                collectSymbolMeanings(stage.getScene().getRoot()));

        List<String> violations = new ArrayList<>();
        meanings.forEach((symbol, semantics) -> {
            if (semantics.size() > 1) {
                violations.add(symbol + " 同时表示 " + semantics);
            }
        });

        assertThat(violations)
                .as("一个符号只能表一个意思：\n  %s", String.join("\n  ", violations))
                .isEmpty();
    }

    /** 受管符号：这些符号在项目里已经被统一过语义，不允许再分叉。 */
    private static final List<String> MANAGED_SYMBOLS = List.of("⚙", "☰", "↻", "✕", "⌕");

    /**
     * 收集「符号 → 它被用来表示的含义」。
     *
     * <p>含义的取法：文本里去掉符号后剩下的文字；没有剩余文字时用 accessibleText；
     * 再没有就用 Tooltip。三者都没有 → 装饰性图标，跳过。
     */
    private static Map<String, Set<String>> collectSymbolMeanings(Node root) {
        Map<String, Set<String>> meanings = new LinkedHashMap<>();
        walk(root, node -> {
            if (!(node instanceof Labeled labeled)) {
                return;
            }
            String text = labeled.getText();
            if (text == null || text.isBlank()) {
                return;
            }
            for (String symbol : MANAGED_SYMBOLS) {
                if (!text.contains(symbol)) {
                    continue;
                }
                String semantic = semanticOf(labeled, symbol);
                if (semantic == null) {
                    continue;
                }
                meanings.computeIfAbsent(symbol, key -> new LinkedHashSet<>()).add(semantic);
            }
        });
        return meanings;
    }

    private static String semanticOf(Labeled labeled, String symbol) {
        String rest = labeled.getText().replace(symbol, "").strip();
        if (!rest.isEmpty()) {
            return stripHint(rest);
        }
        String accessible = labeled.getAccessibleText();
        if (accessible != null && !accessible.isBlank()) {
            return stripHint(accessible.strip());
        }
        Tooltip tooltip = labeled.getTooltip();
        if (tooltip != null && tooltip.getText() != null && !tooltip.getText().isBlank()) {
            return stripHint(tooltip.getText().strip());
        }
        return null;
    }

    /**
     * 去掉末尾的括号提示：「搜索 (⌘F)」与「搜索」是同一个意思。
     * 不去掉的话，同一个符号会因为提示键位不同被判成语义分叉。
     */
    private static String stripHint(String text) {
        return text.replaceAll("\\s*[（(][^）)]*[）)]\\s*$", "").strip();
    }

    // ─────────────────────────── 审计实现 ───────────────────────────

    /** 遍历控件树，返回不合格的纯图标控件描述。必须在 FX 线程调用。 */
    private static List<String> audit(Node root) {
        List<String> violations = new ArrayList<>();
        walk(root, node -> {
            if (!(node instanceof Labeled labeled)) {
                return;
            }
            String text = labeled.getText();
            if (!isIconOnly(text)) {
                return;
            }
            if (isDecorative(node) || insideTable(node)) {
                return;
            }
            Tooltip tooltip = labeled.getTooltip();
            String accessible = labeled.getAccessibleText();
            List<String> missing = new ArrayList<>();
            if (tooltip == null || tooltip.getText() == null || tooltip.getText().isBlank()) {
                missing.add("Tooltip");
            }
            if (accessible == null || accessible.isBlank()) {
                missing.add("accessibleText");
            }
            if (!missing.isEmpty()) {
                violations.add(describe(node, text) + " → 缺少 " + String.join(" + ", missing));
            }
        });
        return violations;
    }

    /** 同一父容器内，字形相同但文字不同的图标视为撞形。必须在 FX 线程调用。 */
    private static void collectGlyphCollisions(Node root, Map<String, List<String>> out) {
        walk(root, node -> {
            if (!(node instanceof Labeled labeled) || isDecorative(node)) {
                return;
            }
            String text = labeled.getText();
            if (!isIconOnly(text)) {
                return;
            }
            Node parent = node.getParent();
            if (parent == null) {
                return;
            }
            if (!(parent instanceof Parent container)) {
                return;
            }
            for (Node sibling : container.getChildrenUnmodifiable()) {
                if (sibling == node || !(sibling instanceof Labeled other)) {
                    continue;
                }
                String otherText = other.getText();
                if (!isIconOnly(otherText) || !otherText.equals(text)) {
                    continue;
                }
                if (other.getTooltip() == null || labeled.getTooltip() == null) {
                    continue;
                }
                String mine = labeled.getTooltip().getText();
                String theirs = other.getTooltip().getText();
                if (mine != null && theirs != null && !mine.equals(theirs)) {
                    out.computeIfAbsent(text + " 被复用为「" + mine + "」与「" + theirs + "」",
                            key -> new ArrayList<>()).add(parent.getClass().getSimpleName());
                }
            }
        });
    }

    private static void walk(Node node, java.util.function.Consumer<Node> visitor) {
        visitor.accept(node);
        if (node instanceof Parent parent) {
            for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) {
                walk(child, visitor);
            }
        }
    }

    /**
     * 纯图标 = 一个字母/数字都没有。
     * 中文、英文、数字都算「有语义」；只剩符号的才算图标。
     */
    private static boolean isIconOnly(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.chars().noneMatch(Character::isLetterOrDigit);
    }

    /** 装饰性：同一容器里已经有文字了，图标只是陪衬。 */
    private static boolean isDecorative(Node node) {
        if (!(node.getParent() instanceof Parent container)) {
            return false;
        }
        for (Node sibling : container.getChildrenUnmodifiable()) {
            if (sibling == node || !(sibling instanceof Labeled other)) {
                continue;
            }
            if (!isIconOnly(other.getText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 表格内部（单元格的数据字形、表头文字）不属于「控件图标」——
     * 它们由 {@link javafx.scene.control.TableColumn} 与 skin 生成，
     * 应用代码拿不到这些节点，自然无法补 Tooltip。故豁免。
     */
    private static boolean insideTable(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof TableCell<?, ?>) {
                return true;
            }
            for (String style : current.getStyleClass()) {
                if (style.contains("column-header") || style.contains("table-header")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String describe(Node node, String text) {
        String id = node.getId() == null ? "" : "#" + node.getId();
        String style = node.getStyleClass().isEmpty()
                ? "" : "." + String.join(".", node.getStyleClass());
        StringBuilder chain = new StringBuilder();
        for (Node current = node.getParent(); current != null; current = current.getParent()) {
            chain.append(" < ").append(current.getClass().getSimpleName());
            if (!current.getStyleClass().isEmpty()) {
                chain.append(".").append(String.join(".", current.getStyleClass()));
            }
        }
        return node.getClass().getSimpleName() + "「" + text + "」" + id + style
                + "  路径:" + chain;
    }

    private static Node treeCell(FxRobot robot, String name) {
        return robot.lookup(node -> node instanceof javafx.scene.control.TreeCell<?> cell
                        && cell.getTreeItem() != null
                        && cell.getTreeItem().getValue() != null
                        && cell.getTreeItem().getValue().toString()
                                .toLowerCase(Locale.ROOT).contains(name))
                .query();
    }

    private static <T> T onFx(Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
