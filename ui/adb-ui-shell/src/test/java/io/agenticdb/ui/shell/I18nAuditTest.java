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
 *   <li><b>尺子的盲区</b> —— 汉字棘轮只认 U+4E00–U+9FFF，
 *       {@code "（NULL）"} 这种<b>纯全角标点</b>的中文残留它看不见，
 *       于是「清零」的文件其实还没清。→ 见
 *       {@link #filesThatClaimToBeCleanAreAlsoFreeOfFullwidthPunctuation()}。</li>
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
     * <p>抽取进度：2216（R34 开工实测）→ 2154（顶区四件套）→ 2011（动作层 71 个动作）
     * → 1917（表清单页）→ 1504（数据网格 {@code DataGridPage} 413 条清零）
     * → <b>1306</b>（本批：外壳 {@code AppShell} 198 条清零，598 键进两份资源包）。
     *
     * <p>调小它之前先量一次，别照抄上一轮文档里的数字 —— 本轮开工时就发现
     * README 与真源记的「1108 条 / 134 类」实测是「1107 / 133」，
     * <b>文档里的基线本身就没复现</b>。量法是 <code>node tools/cjk-count.mjs</code>
     * （与本类的 {@code stripComments} + 正则同构，实测与这里逐字一致）。
     *
     * <p>⚠️ <b>别用 grep 数</b>：
     * <pre>{@code
     * find ui -path '*src/main*' -name '*.java' -print0 \
     *   | xargs -0 grep -hoP '"[^"[:cntrl:]]*[\x{4e00}-\x{9fff}][^"[:cntrl:]]*"' | wc -l
     * }</pre>
     * 这条实测给 <b>1530</b>，比门禁多 26 条 —— 差的全在<b>注释里</b>：grep 剥不掉注释
     * （注释里的引号、跨行的块注释、字符串里的 {@code //} 都会骗过正则），
     * 而本类是先 {@code stripComments} 再计数。口径不同的两个数放在一起，
     * 只会让人以为棘轮又涨了。
     */
    private static final int CJK_CEILING = 1306;

    /** 源码里对 {@link I18n#t(String, Object...)} 的键引用。 */
    private static final Pattern KEY_REFERENCE = Pattern.compile("I18n\\.t\\(\\s*\"([^\"]+)\"");

    /**
     * 动作构造：{@code actions.register("id", …)} 与 {@code new Action("id", …)}。
     *
     * <p>⚠️ 两种都要匹配。第一版只写了 {@code actions.register}，于是
     * {@code TableListPage.localActions()} 那批走 {@code new Action} 的本地动作
     * 完全落在门禁视野之外 —— 门禁声称"穷举所有动作"，实际只穷举了注册表那一路。
     * 这类"看起来是全覆盖、其实漏了一整个分支"的门禁比没有门禁更危险，
     * 因为它给出的信心是假的。
     */
    private static final Pattern ACTION_REGISTRATION =
            Pattern.compile("(?:actions\\.register|new Action)\\(\\s*\"([^\"]+)\"");

    /** 含中文的字符串字面量（不含跨行文本块，够用且不会漏普通字面量）。 */
    private static final Pattern CJK_LITERAL =
            Pattern.compile("\"[^\"\\n]*[\\x{4e00}-\\x{9fff}][^\"\\n]*\"");

    /**
     * ⭐ R34 N1-b 补的第二把尺：<b>只含全角标点、不含汉字</b>的字面量。
     *
     * <p>{@link #CJK_LITERAL} 只认 U+4E00–U+9FFF，于是 {@code "（NULL）"}、
     * {@code String.join("、", keys)}、{@code "SQL："} 这一类<b>从棘轮底下钻过去了</b> ——
     * 数据网格「清零」之后重拍帧，才发现表单视图的空值提示还是全角括号。
     * 这类串在英文界面上照样露出中文标点，属于同一笔债。
     *
     * <p>为什么单开一条而不是将旧尺加宽：把全角并进 {@code CJK_CEILING} 会让
     * 数字从 1504 涨到 1536，<b>「只许降不许升」当场破功</b> —— 而涨的原因
     * 只是尺子变细了，不是有人新写了中文。两条各自单调递减，谁也别想借对方涨一次。
     */
    private static final Pattern FULLWIDTH_LITERAL =
            Pattern.compile("\"[^\"\\n]*[\\x{3000}-\\x{303f}\\x{ff01}-\\x{ff60}][^\"\\n]*\"");

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
     * ⭐ R34 N1-b 第五批：调用点<b>递进去几个实参</b>，两份资源里就该有几个占位符。
     *
     * <p>上一条门禁只证明「键存在」，证明不了「键用得对」。把
     * {@code status("已连接 " + name + " · " + n + " 个对象")} 抽成
     * {@code I18n.t("shell.connectedObjects", name, n)} 之后，两类错都<b>静默不报错</b>：
     * <ul>
     *   <li>少写一个 {@code {1}} → 界面上多出一段没人认领的 {@code · 3}，或者干脆看不见对象数；</li>
     *   <li>多写一个 {@code {2}} → 状态栏上直接长出字面量 {@code {2}}。</li>
     * </ul>
     * 两种都要到<b>验收帧</b>才看得见，而状态条文案是瞬时的、帧不一定抓得到那一刻 ——
     * 所以这里静态对账：实参数 == 占位符数，<b>en 与 zh 各判一次</b>
     * （两侧占位符数可以不一样，那就是这条门禁要抓的东西）。
     *
     * <p>顺带要求占位符从 {@code {0}} 起<b>连续</b>：{@code {0}} 和 {@code {2}} 缺 {@code {1}}
     * 会让第二个实参无处安放。
     */
    @Test
    void callSitesPassAsManyArgumentsAsTheBundlesHavePlaceholders() throws IOException {
        Properties en = bundle(Locale.ENGLISH);
        Properties zh = bundle(Locale.CHINESE);
        List<String> problems = new ArrayList<>();
        int parameterized = 0;
        for (Path file : uiSources()) {
            String source = stripComments(read(file));
            for (CallSite site : callSites(source)) {
                String enText = en.getProperty(site.key());
                String zhText = zh.getProperty(site.key());
                // 键不存在由上一条门禁负责报，这里不重复报。
                if (enText == null || zhText == null) {
                    continue;
                }
                int expectedEn = placeholders(enText);
                int expectedZh = placeholders(zhText);
                if (expectedEn > 0 || expectedZh > 0 || site.args() > 0) {
                    parameterized++;
                }
                if (site.args() != expectedEn) {
                    problems.add(relative(file) + " " + site.key()
                            + " 递了 " + site.args() + " 个实参，en 侧有 " + expectedEn + " 个占位符");
                }
                if (site.args() != expectedZh) {
                    problems.add(relative(file) + " " + site.key()
                            + " 递了 " + site.args() + " 个实参，zh 侧有 " + expectedZh + " 个占位符");
                }
            }
        }
        assertThat(parameterized)
                .as("一个带占位符的调用点都没扫到 —— 解析失效，这条门禁在空转")
                .isGreaterThan(20);
        assertThat(problems)
                .as("实参个数与占位符个数对不上（界面上会露出字面 {0} 或少一段话）：\n  %s",
                        String.join("\n  ", problems))
                .isEmpty();
    }

    /** 一个 {@code I18n.t("key", a, b)} 调用点：键名与<b>顶层实参个数</b>（不含键本身）。 */
    private record CallSite(String key, int args) {
    }

    /**
     * 按名读某一种语言的资源表原文。
     *
     * <p>不走 {@code ResourceBundle}（它会按机器 locale 级联，中文机器上的基包会推翻
     * 「默认英文」），也不走 {@code I18n.t(...)}（那会做替换、还会记缺失）。
     * ⚠️ 必须 {@code Reader} + UTF-8：{@code Properties.load(InputStream)} 永远按
     * ISO-8859-1 解，中文静默乱码 —— 与 {@link I18n} 里同一套读法。
     */
    private static Properties bundle(Locale locale) throws IOException {
        String resource = I18n.CHINESE.equals(locale)
                ? I18n.ZH_RESOURCE : I18n.EN_RESOURCE;
        Properties values = new Properties();
        try (java.io.InputStream in = I18n.class.getResourceAsStream(resource)) {
            assertThat(in).as("找不到资源 " + resource).isNotNull();
            try (java.io.Reader reader = new java.io.InputStreamReader(in,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                values.load(reader);
            }
        }
        return values;
    }

    /**
     * 括号/引号/花括号配平地扫一遍，取出每个 {@code I18n.t(} 的顶层参数。
     * 第一个参数不是纯字符串字面量的（拼出来的键）直接跳过 —— 那种由动作穷举那条门禁管。
     */
    private static List<CallSite> callSites(String source) {
        List<CallSite> sites = new ArrayList<>();
        String marker = "I18n.t(";
        int at = 0;
        while ((at = source.indexOf(marker, at)) >= 0) {
            int open = at + marker.length() - 1;
            List<String> args = splitTopLevel(source, open);
            if (!args.isEmpty()) {
                String first = args.get(0).trim();
                if (first.length() >= 2 && first.charAt(0) == '"'
                        && first.charAt(first.length() - 1) == '"'
                        && first.substring(1, first.length() - 1).indexOf('"') < 0) {
                    sites.add(new CallSite(first.substring(1, first.length() - 1), args.size() - 1));
                }
            }
            at = open;
        }
        return sites;
    }

    /** 从 {@code open} 指向的左括号开始，按<b>顶层逗号</b>切参数（嵌套括号、字符串内的逗号不算）。 */
    private static List<String> splitTopLevel(String source, int open) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false, inChar = false, escaped = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                current.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                current.append(c);
                if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inChar = false;
                } else if (escaped) {
                    escaped = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == '\'') {
                inChar = true;
                current.append(c);
                continue;
            }
            if (c == '(' || c == '{' || c == '[') {
                depth++;
                if (depth == 1) {
                    continue;
                }
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    if (!current.toString().isBlank()) {
                        parts.add(current.toString());
                    }
                    return parts;
                }
            } else if (c == ',' && depth == 1) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        return List.of();
    }

    /** 文案里占位符的个数（要求从 {0} 起连续；不连续按最大下标 + 1 计，让调用点对不上号）。 */
    private static int placeholders(String text) {
        int highest = -1;
        java.util.regex.Matcher matcher =
                Pattern.compile("\\{(\\d+)}").matcher(text);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest + 1;
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

    /**
     * ⭐ N1-d 的两条约束：偏好链的**优先级**，以及 configure 必须**早于任何节点构造**。
     *
     * <p>优先级是行为断言（{@code -Dadb.locale} &gt; 偏好 &gt; 默认英文）——
     * 截图脚本要靠它拍中文帧，搞错就会整套帧都是英文而没人发现。
     *
     * <p>顺序那条只能静态查：JavaFX 的 {@code setText} 是构造时一次性取值，
     * {@code configure()} 跑晚了等于没跑，而"跑晚了"在运行时**完全静默**。
     * 所以这里查 {@code App.start} 里两行的先后 —— 不是用文本 grep 代替行为，
     * 而是这条约束本身没有可执行的行为面（构造顺序错了不会报错，只是语言不对）。
     */
    @Test
    void thePreferenceChainIsPropertyThenPreferenceThenDefault() {
        String previous = System.getProperty(I18n.LOCALE_PROPERTY);
        try {
            System.clearProperty(I18n.LOCALE_PROPERTY);
            assertThat(I18n.configure(I18n.CHINESE))
                    .as("无系统属性时，显式偏好生效")
                    .isEqualTo(I18n.CHINESE);
            assertThat(I18n.configure(null))
                    .as("偏好也没给时，落回默认英文（拍板 3）")
                    .isEqualTo(I18n.ENGLISH);

            System.setProperty(I18n.LOCALE_PROPERTY, "zh");
            assertThat(I18n.configure(I18n.ENGLISH))
                    .as("系统属性必须压过偏好 —— shots.sh 靠它拍中文帧")
                    .isEqualTo(I18n.CHINESE);
        } finally {
            if (previous == null) {
                System.clearProperty(I18n.LOCALE_PROPERTY);
            } else {
                System.setProperty(I18n.LOCALE_PROPERTY, previous);
            }
            I18n.setLocale(I18n.DEFAULT_LOCALE);
        }
    }

    @Test
    void theEntryPointConfiguresTheLocaleBeforeBuildingAnyNode() throws IOException {
        String source = read(repositoryRoot().resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/App.java"));
        int configure = source.indexOf("I18n.configure(");
        int buildShell = source.indexOf("new AppShell(");
        assertThat(configure)
                .as("App.start 里必须调用 I18n.configure —— 不调用的话偏好设置是死的")
                .isPositive();
        assertThat(configure)
                .as("configure 必须早于 new AppShell(...)：标签是构造时一次性取值的，"
                        + "晚 configure 在运行时完全静默")
                .isLessThan(buildShell);
    }

    // ─────────────────────────── 2. 硬编码中文棘轮 ───────────────────────────

    @Test
    void hardcodedChineseIsRatchetedDown() throws IOException {
        int total = 0;
        List<String> byFile = new ArrayList<>();
        for (Path file : uiSources()) {
            int hits = count(CJK_LITERAL, stripComments(read(file)));
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
                        + "当前残留 %d 条（上限 %d，按文件）：%s",
                        total, CJK_CEILING, String.join("  ", byFile))
                .isLessThanOrEqualTo(CJK_CEILING);
    }

    /**
     * ⭐ 补的第二把尺：<b>已经抽干净的文件，也不许留全角标点</b>。
     *
     * <p>{@link #CJK_LITERAL} 只认 U+4E00–U+9FFF，于是 {@code "（NULL）"}、
     * {@code String.join("、", keys)} 这类<b>从棘轮底下钻了过去</b> —— 数据网格「清零」
     * 之后重拍帧才看见表单视图的空值提示仍是全角括号。英文界面露出中文括号，
     * 和露出中文汉字是同一笔债。
     *
     * <p>为什么不做成「全角字面量总数 ≤ N」的第二条棘轮：那样数是 366，
     * 但其中绝大多数本来就压在汉字棘轮上（一条中文串两边各计一次），
     * 等于给同一笔债立两张账，还得多养一个只能靠人记得调的数字。
     * 这条按文件判：<b>你说自己干净，就得真干净</b>；汉字棘轮降到 0 的那天，
     * 这条自动变成「全库零全角」，不需要再改。
     */
    @Test
    void filesThatClaimToBeCleanAreAlsoFreeOfFullwidthPunctuation() throws IOException {
        List<String> dirty = new ArrayList<>();
        List<String> clean = new ArrayList<>();
        for (Path file : uiSources()) {
            String source = stripComments(read(file));
            int han = count(CJK_LITERAL, source);
            int fullwidth = count(FULLWIDTH_LITERAL, source);
            if (han == 0 && fullwidth > 0) {
                dirty.add(relative(file) + " 残留 " + fullwidth + " 条全角标点字面量");
            } else if (han == 0) {
                clean.add(relative(file));
            }
        }
        assertThat(clean)
                .as("一个「汉字清零」的文件都没有 —— 这条门禁在空转，假绿")
                .isNotEmpty();
        assertThat(dirty)
                .as("这些文件汉字残留已经清零，却还留着全角标点（分隔符/括号也是文案，"
                        + "抽进资源包，别在代码里拼）：\n  %s\n（当前 %d 个文件做到零汉字且零全角）",
                        String.join("\n  ", dirty), clean.size())
                .isEmpty();
    }

    /**
     * 去掉注释，只留代码 —— 棘轮数的是**界面文案**，不是解释文案的注释。
     *
     * <p>不做这一步的话，写一段带 ASCII 引号的中文注释就会把上限顶高一个，
     * 于是这条门禁反过来惩罚"把 WHY 写清楚" —— 与本仓库的注释文化正好相反。
     * （实测踩过：StatusBar 修布尔哨兵时补的一句注释让计数从 2011 变 2012。）
     *
     * <p>逐字符扫而不是用正则删，是因为字符串里合法地会出现 {@code //}
     * （URL、路径分隔），正则删会把字符串一起咬掉。
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inString = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    if (next != '\0') {
                        out.append(next);
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
