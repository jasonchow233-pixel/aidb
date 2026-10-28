package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R34 N1-c 门禁：i18n 层的三条硬规则。
 *
 * <h2>为什么这一层需要门禁</h2>
 * 抽取 1404 条硬编码中文是一次**跨 77 个文件的机械改动**，机械改动最容易出的三类错：
 * <ol>
 *   <li><b>只补了一边</b> —— 代码里写了 {@code I18n.t("x")}，但只在 en 里加了 {@code x}，
 *       切到中文就掉字。→ {@link #theTwoBundlesHaveExactlyTheSameKeys()}；</li>
 *   <li><b>键名打错</b> —— {@code t("menue.file")} 在编译期完全合法，
 *       运行时才在界面上长出一个 {@code ⚠menue.file}。
 *       等验收帧才发现，代价是整轮重拍。→ {@link #everyKeyReferencedInSourceExists()}
 *       在<b>编译前</b>就把源码里引用的每个键拿去对账；</li>
 *   <li><b>悄悄回潮</b> —— 有人图省事直接 {@code setText("新建")}，
 *       门禁若只查「有没有资源文件」就永远发现不了。
 *       → {@link #hardcodedChineseIsRatchetedDown()}。</li>
 * </ol>
 *
 * <h2>棘轮（ratchet）而不是清零</h2>
 * 「ui 主源码零 CJK 字面量」是终态，但 1404 条不可能一次抽完；
 * 从第一天就写死「必须为 0」只会得到两种坏结果：门禁被注释掉，或者被改成假通过。
 * 所以这里断言的是 <b>≤ {@link #CJK_CEILING}</b>，每抽完一批就把数字调小 ——
 * <b>只许降不许升</b>，新增硬编码中文会立刻顶穿它。
 * 与 {@code IconSystemAuditTest} 同一套纪律：先证明扫描本身扫到了东西，再谈断言。
 */
class I18nAuditTest {

    /**
     * 允许残留的 CJK 字面量条数。⚠️ <b>只许降不许升。</b>
     *
     * <p>抽取进度：2216（R34 开工实测）→ 本批（顶区四件套：菜单标题 / Ribbon /
     * 连接胶囊 / 搜索 / 主题 / ⋯ / 状态栏 / 页签右键菜单）→ 当前值。
     *
     * <p>调小它之前先量一次，别照抄上一轮文档里的数字 —— 本轮开工时就发现
     * README 与真源记的「1108 条 / 134 类」实测是「1107 / 133」，
     * <b>文档里的基线本身就没复现</b>。量法（find 的 path 参数写成
     * 星号+src/main+星号，别在注释里放完整的 glob —— 星号斜杠会提前关掉这段注释，
     * 这个坑本轮真的踩了一次）：
     *
     * <pre><code>find ui -path '*src/main*' -name '*.java' -print0 \
     *   | xargs -0 grep -hoP '"[^"[:cntrl:]]*[han][^"[:cntrl:]]*"' | wc -l</code></pre>
     */
    private static final int CJK_CEILING = 2011;

    /** 源码里对 {@link I18n#t(String, Object...)} 的键引用。 */
    private static final Pattern KEY_REFERENCE = Pattern.compile("I18n\\.t\\(\\s*\"([^\"]+)\"");

    /** 动作注册：{@code actions.register("id", …)} —— 第一个实参就是标签键的后缀。 */
    private static final Pattern ACTION_REGISTRATION =
            Pattern.compile("actions\\.register\\(\\s*\"([^\"]+)\"");

    /** 含中文的字符串字面量（不含跨行文本块，够用且不会漏普通字面量）。 */
    private static final Pattern CJK_LITERAL =
            Pattern.compile("\"[^\"\\n]*[\\x{4e00}-\\x{9fff}][^\"\\n]*\"");

    @AfterEach
    void restoreDefaultLocale() {
        I18n.setLocale(I18n.DEFAULT_LOCALE);
    }

    // ─────────────────────────── 1. 两套键集 ───────────────────────────

    @Test
    void theTwoBundlesHaveExactlyTheSameKeys() {
        Set<String> en = I18n.keys(Locale.ENGLISH);
        Set<String> zh = I18n.keys(Locale.CHINESE);

        assertThat(en).as("en 资源一个键都没读到 —— 扫描失效，这份审计是假绿").isNotEmpty();
        assertThat(zh).as("zh 资源一个键都没读到 —— 扫描失效，这份审计是假绿").isNotEmpty();

        assertThat(new TreeSet<>(en))
                .as("只在 en 里有的键（切到中文会掉字）")
                .containsExactlyElementsOf(new TreeSet<>(zh));
    }

    @Test
    void everyKeyReferencedInSourceExists() throws IOException {
        Set<String> referenced = new LinkedHashSet<>();
        for (Path file : uiSources()) {
            Matcher matcher = KEY_REFERENCE.matcher(read(file));
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
        }
        assertThat(referenced)
                .as("ui 主源码里一个 I18n.t(...) 都没扫到 —— 正则或路径过滤失效，假绿")
                .isNotEmpty();

        Set<String> en = I18n.keys(Locale.ENGLISH);
        Set<String> zh = I18n.keys(Locale.CHINESE);
        List<String> problems = new ArrayList<>();
        for (String key : new TreeSet<>(referenced)) {
            if (!en.contains(key)) {
                problems.add(key + " 缺 en");
            }
            if (!zh.contains(key)) {
                problems.add(key + " 缺 zh");
            }
        }
        assertThat(problems)
                .as("源码引用了但资源里没有的键 —— 界面上会长出 ⚠key")
                .isEmpty();
    }

    /**
     * ⭐ 每个注册的动作必须在**两种语言里都有名字**。
     *
     * <p>动作 id 是标签键的来源（{@code register("new-connection", …)} →
     * {@code action.new-connection}），所以这条门禁可以**穷举**而不是抽查：
     * 新加一个动作却忘了配名字，这里立刻红 —— 比"扫源码里出现的键"强，
     * 因为它不依赖有人记得写 {@code I18n.t("...")} 的字面量。
     */
    @Test
    void everyRegisteredActionHasANameInBothLanguages() throws IOException {
        Set<String> en = I18n.keys(Locale.ENGLISH);
        Set<String> zh = I18n.keys(Locale.CHINESE);
        Set<String> ids = new LinkedHashSet<>();
        for (Path file : uiSources()) {
            Matcher matcher = ACTION_REGISTRATION.matcher(read(file));
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        assertThat(ids)
                .as("没扫到任何 actions.register(...) —— 正则失效，这份审计是假绿")
                .isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (String id : new TreeSet<>(ids)) {
            String key = "action." + id;
            if (!en.contains(key)) {
                problems.add(key + " 缺 en");
            }
            if (!zh.contains(key)) {
                problems.add(key + " 缺 zh");
            }
        }
        assertThat(problems)
                .as("动作注册了但没有名字（菜单 / 命令面板会长出 ⚠action.xxx）")
                .isEmpty();
    }

    // ─────────────────────────── 2. 硬编码中文棘轮 ───────────────────────────

    @Test
    void hardcodedChineseIsRatchetedDown() throws IOException {
        int total = 0;
        List<String> byFile = new ArrayList<>();
        for (Path file : uiSources()) {
            int hits = count(CJK_LITERAL, read(file));
            total += hits;
            if (hits > 0) {
                byFile.add(relative(file) + " = " + hits);
            }
        }
        assertThat(byFile)
                .as("没扫到任何含中文字面量的文件 —— 扫描失效，这份审计是假绿")
                .isNotEmpty();

        // 降下来了就把常量改成实际值；升上来了说明有人新写了硬编码中文。
        assertThat(total)
                .as("ui 主源码里的 CJK 字面量条数超过棘轮上限。"
                        + "新增硬编码中文请改成 I18n.t(...)；抽完一批请把 CJK_CEILING 调小。\n"
                        + "当前残留（按文件）：" + String.join("\n  ", byFile))
                .isLessThanOrEqualTo(CJK_CEILING);
    }

    // ─────────────────────────── 3. format 的两个坑 ───────────────────────────

    /**
     * ⭐ 证明「为什么不用 MessageFormat」。
     *
     * <p>MessageFormat 会把<b>单引号</b>当 literal 区起始符，于是英文文案里的缩写被吃掉撇号。
     * 这条门禁不是装饰：将来谁把 {@link I18n#format} 换成 MessageFormat，
     * 或者在 {@code t()} 里顺手用 {@code MessageFormat.format}，这条立刻红。
     */
    @Test
    void placeholdersAreSubstitutedLiterallyNotByMessageFormat() {
        assertThat(I18n.format("Deleted {0} of {1} rows", 3, 7))
                .as("{0}/{1} 字面替换")
                .isEqualTo("Deleted 3 of 7 rows");

        assertThat(I18n.format("Don't delete {0} rows", 5))
                .as("MessageFormat 会把这句变成 \"Dont delete 5 rows\" —— 这里必须原样保留撇号")
                .isEqualTo("Don't delete 5 rows");

        assertThat(I18n.format("100% {0} {cost}", "done"))
                .as("百分号与花括号里的非数字片段都不该被动到")
                .isEqualTo("100% done {cost}");

        // 对照实验：同样的文案交给 MessageFormat 会坏成什么样，写在这里防止有人"顺手统一"。
        // ⚠️ 实测比"吃掉撇号"更糟：撇号开启的 literal 区一路吞到行尾，连 {0} 都不再替换 ——
        //    占位符静默失效，界面会原样长出 "Dont delete {0} rows"。这条断言钉的是
        //    MessageFormat 的真实行为，不是我们的期望。
        assertThat(java.text.MessageFormat.format("Don't delete {0} rows", 5))
                .as("MessageFormat 会把撇号之后的一切当字面量，占位符跟着失效")
                .isEqualTo("Dont delete {0} rows");
    }

    // ─────────────────────────── 4. 语言与缺键行为 ───────────────────────────

    @Test
    void defaultLocaleIsEnglishAndSwitchingRoundTrips() {
        I18n.setLocale(Locale.ENGLISH);
        assertThat(I18n.locale()).as("拍板 3：默认英文").isEqualTo(Locale.ENGLISH);
        String english = I18n.t("menu.file");

        I18n.setLocale(Locale.CHINESE);
        assertThat(I18n.locale()).isEqualTo(Locale.CHINESE);
        String chinese = I18n.t("menu.file");

        assertThat(english).as("中英两侧必须真的不同，否则资源是空的").isEqualTo("File");
        assertThat(chinese).isEqualTo("文件");
    }

    @Test
    void anUntranslatedKeyShowsUpInsteadOfCrashing() {
        I18n.clearMissingKeys();

        assertThat(I18n.t("definitely.not.a.real.key"))
                .as("缺键要返回看得见的标记，不能抛异常（一个漏抽的键不该让整个窗口打不开），"
                        + "也不能静默回落到键名")
                .isEqualTo("⚠definitely.not.a.real.key");
        assertThat(I18n.missingKeys())
                .as("同时必须记账 —— 这才是能入门禁的那一半")
                .contains("definitely.not.a.real.key");

        I18n.clearMissingKeys();
        assertThat(I18n.missingKeys()).as("清空后不留残余").isEmpty();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int hits = 0;
        while (matcher.find()) {
            hits++;
        }
        return hits;
    }

    /**
     * ui 两个模块的主源码。
     *
     * <p>⚠️ Windows 的路径分隔符是 {@code \}，不归一化会一个文件都不扫、
     * 门禁静默变绿 —— 这条注释与断言抄自 {@code IconSystemAuditTest}，同一个坑不踩第二次。
     */
    private static List<Path> uiSources() throws IOException {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".java") && relative.contains("src/main/java")
                        && relative.startsWith("ui/")) {
                    files.add(path);
                }
            }
        }
        assertThat(files)
                .as("没有扫到 UI 主源码 —— 路径过滤失效，这份审计是假绿")
                .hasSizeGreaterThan(30);
        return files;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String relative(Path path) {
        return repositoryRoot().relativize(path).toString().replace('\\', '/');
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("core"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
