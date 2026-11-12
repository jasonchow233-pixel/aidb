package io.agenticdb.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样式类覆盖门禁。
 *
 * <h2>为什么要有它</h2>
 * 2026-10-22 第十轮做样式审计时，用一句话就量出了一个大洞：
 * <b>Java 里用到的 {@code adb-*} 样式类，有 43 个在 {@code app.css} 里根本没有规则</b>。
 * <ul>
 *   <li>结构页四张表完全没有样式（{@code adb-structure__table} 等 6 个）；</li>
 *   <li>连接页表格用 Modena 默认外观，与其它表格不一致（{@code adb-connections__table}）；</li>
 *   <li>五个向导的内容区没有统一留白（{@code adb-wizard__content}）；</li>
 *   <li>最要命的一个：{@code adb-error}（表单校验错误）没有规则 ——
 *       <b>错误文字和普通文字长得一样</b>，用户看不出哪里填错了。</li>
 * </ul>
 *
 * <p>这类问题<strong>截图能看出来</strong>，但<strong>行为门禁测不到</strong>：
 * 控件确实存在、事件确实绑好了，只是没人给它上样式。
 * 现有的门禁（{@code TokenParityTest} / {@code AppCssAuditTest} / {@code FocusRingAuditTest}）
 * 管的是「令牌有没有被用」「尺度对不对」「焦点环有没有」，
 * <b>没有一个在问「你写的样式类到底有没有对应规则」</b>。
 *
 * <h2>本门禁的策略</h2>
 * <ul>
 *   <li><b>已知洞进白名单</b>：历史缺口逐批补齐；补完一个就从白名单里删一个。</li>
 *   <li><b>白名单外的未定义样式类 = 失败</b>：新写的 {@code adb-*} 类必须同时在 CSS 里落地。
 *       这才是本门禁真正的价值：<b>防止洞继续扩大</b>。</li>
 * </ul>
 */
class StyleClassCoverageTest {

    /**
     * 看着像样式类、其实**不是**样式类的字符串。
     *
     * <p>扫描是按字面量扫的，所以会误抓线程名这类东西 —— 它们以 {@code adb-} 开头，
     * 但永远不会出现在样式类里。
     */
    private static final Set<String> NOT_STYLE_CLASSES = Set.of(
            // workbench/adb-jdbc 的单线程执行器名字
            "adb-db",
            // ScreenshotSession 的截图线程名字
            "adb-screenshot");

    /**
     * 已知未定义样式类（待补）。
     *
     * <p>第十轮把当时的已知缺口**全部补齐**了，
     * 所以这份白名单现在是空的 —— 以后再出现「Java 用了、CSS 没定义」的类，
     * 门禁会**立刻失败**。
     *
     * <p>补齐一条样式时不需要动这里；只有「确实不该有样式」的类才加进
     * {@link #NOT_STYLE_CLASSES}，并写清原因。
     */
    private static final Set<String> KNOWN_MISSING = Set.of();

    /** Java 里出现的样式类字面量。 */
    private static final Pattern JAVA_CLASS = Pattern.compile("\"(adb-[a-zA-Z0-9_-]+)\"");

    /** CSS 里的类选择器。 */
    private static final Pattern CSS_SELECTOR = Pattern.compile("\\.([a-zA-Z][a-zA-Z0-9_-]*)");

    @Test
    void everyStyleClassUsedInJavaHasACssRule() throws IOException {
        Set<String> used = usedInJava();
        Set<String> styled = definedInCss();

        Set<String> missing = new TreeSet<>();
        for (String name : used) {
            if (!styled.contains(name)
                    && !KNOWN_MISSING.contains(name)
                    && !NOT_STYLE_CLASSES.contains(name)) {
                missing.add(name);
            }
        }

        assertThat(missing)
                .as("这些样式类在 Java 里被使用，但 app.css 里没有对应规则 ——\n"
                        + "  · 界面会退回 JavaFX 默认外观（和周围不是一套东西），或\n"
                        + "  · 根本没有任何视觉效果（比如错误提示不显红）。\n"
                        + "  · 要么在 app.css 里补规则，要么把这个类从 Java 里删掉。\n"
                        + "  · 确实不是样式类的（线程名之类）加进 NOT_STYLE_CLASSES 并写明原因。\n"
                        + "  未定义：%s", String.join("\n  ", missing))
                .isEmpty();
    }

    @Test
    void theKnownMissingListIsActuallyStillMissing() throws IOException {
        // 白名单里的类如果哪天被补上了，这条就失败 → 提醒把白名单收紧。
        // （反向校验：白名单不能长期养着「其实早就修好了」的条目。）
        Set<String> styled = definedInCss();
        List<String> stale = new ArrayList<>();
        for (String name : KNOWN_MISSING) {
            if (styled.contains(name)) {
                stale.add(name);
            }
        }
        assertThat(stale)
                .as("这些类在白名单里，但 CSS 里已经有规则了 —— 请从 KNOWN_MISSING 里删掉")
                .isEmpty();
    }

    @Test
    void cssDoesNotDefineClassesNobodyUses() throws IOException {
        // 反向检查：CSS 里定义了但 Java 从没用过的类 = 死样式（改了没人看得见）。
        Set<String> used = usedInJava();
        Set<String> styled = definedInCss();

        Set<String> dead = new TreeSet<>();
        for (String name : styled) {
            if (name.startsWith("adb-") && !used.contains(name)) {
                dead.add(name);
            }
        }

        assertThat(dead)
                .as("app.css 里定义了这些类，但 Java 从来不用 —— 改了没人看得见：\n  %s",
                        String.join("\n  ", dead))
                .isEmpty();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** Java 源码里出现过的 {@code adb-*} 样式类。 */
    private static Set<String> usedInJava() throws IOException {
        Set<String> used = new TreeSet<>();
        // adb-ui-core 也要扫：`.adb-icon--filled` 是在 Icons 里加上的。
        // adb-asset 是资产族登记表：磁贴的厂商作用域类（.adb-db--*）在这里声明，
        // 向导磁贴与导航树都从它取样式类，所以 CSS 厂商色类的「被使用」证据落在这一层。
        for (String rootSpec : new String[]{
                "../adb-ui-shell/src/main/java",
                "../adb-ui-core/src/main/java",
                "../../workbench/adb-asset/src/main/java",
                "workbench/adb-asset/src/main/java"}) {
            Path root = Path.of(rootSpec);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    // ⭐ 先去掉注释 —— 注释里提到的「adb-xxx」不是**被使用**的样式类。
                    String source = Files.readString(path, StandardCharsets.UTF_8)
                            .replaceAll("(?s)/\\*.*?\\*/", "")
                            .replaceAll("(?m)//.*$", "");
                    Matcher matcher = JAVA_CLASS.matcher(source);
                    while (matcher.find()) {
                        used.add(matcher.group(1));
                    }
                }
            }
        }
        assertThat(used).as("扫到的样式类").isNotEmpty();
        return used;
    }

    /** {@code app.css} 里定义过的类选择器。 */
    private static Set<String> definedInCss() throws IOException {
        Path css = Path.of("..", "adb-ui-shell", "src", "main", "resources",
                "io", "agenticdb", "ui", "app.css");
        if (!Files.exists(css)) {
            css = Path.of("ui", "adb-ui-shell", "src", "main", "resources",
                    "io", "agenticdb", "ui", "app.css");
        }
        assertThat(css).as("app.css 路径").exists();
        // ⭐ 先去掉注释 —— 注释里提到的类名（例如「这个类其实无效」）
        //    不代表 CSS 里真有它的规则。
        String text = Files.readString(css, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "");
        Set<String> styled = new TreeSet<>();
        Matcher matcher = CSS_SELECTOR.matcher(text);
        while (matcher.find()) {
            styled.add(matcher.group(1));
        }
        // 伪类不是类名的一部分（:hover / :focused 已被上面的模式排除，
        // 但像 .adb-button:disabled 里的 disabled 仍会被误收）—— 过滤掉常见伪类名。
        styled.removeAll(Set.of("hover", "focused", "disabled", "pressed", "selected",
                "filled", "empty", "active", "armed", "root", "odd", "even", "showing",
                "cell", "row", "column", "header", "label", "container", "text", "graphic"));
        return styled;
    }

    @Test
    void knownMissingListOnlyContainsRealStyleClasses() {
        // 白名单里的每个名字都必须是「看起来像样式类」的名字 ——
        // 防止有人往白名单里塞奇怪的东西，让门禁失去意义。
        assertThat(KNOWN_MISSING).allSatisfy(name ->
                assertThat(name.toLowerCase(Locale.ROOT)).startsWith("adb-"));
        assertThat(NOT_STYLE_CLASSES).allSatisfy(name ->
                assertThat(name.toLowerCase(Locale.ROOT)).startsWith("adb-"));
        // 白名单与「非样式类」不能有交集 —— 否则一条字符串会同时被当成「有样式」
        // 和「不是样式」，门禁的语义就乱了。
        assertThat(KNOWN_MISSING).doesNotContainAnyElementsOf(NOT_STYLE_CLASSES);
    }
}
