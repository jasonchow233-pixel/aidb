package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     *
     * <p>⭐ <b>终态不是 0，是 8 —— 这 8 条是「输入 token」不是文案。</b>
     * {@code CellRenderer} 与 {@code CellValueParser} 各自用
     * {@code case "1", "true", "yes", "y", "on", "是", "真"} 认布尔值：
     * 中文那两项是<b>用户真会打进格子里的字面量</b>（导入 CSV、手填布尔列），
     * 翻成 "yes" 就等于把「输入 是 表示真」这条能力删掉。
     * 三条路都不通：进资源包会让布尔解析随界面语言变（同一份数据两种解释，比截字更坏）；
     * 挪到模型层只是把账从主尺转到 {@code MODEL_CJK_CEILING}，同一笔债记两次；
     * 写成 {@code \u662f} 转义则正好是这条门禁的 javadoc 警告过的「改成假通过」。
     * 所以留 8 条、写清理由，比凑一个好看的 0 更诚实。
     */
    private static final int CJK_CEILING = 8;

    /**
     * ⭐ R34 N1-b 第七批补的第三把尺：<b>模型层</b>（core / workbench）的汉字字面量。
     *
     * <p>为什么 {@link #CJK_CEILING} 挡不住这一层：它只扫 {@code ui/**}\/src/main。
     * 第六批的 D-7 就是这么漏的 —— {@code Danger.label()} 的中文写在
     * {@code core/adb-model}，英文构建把「只读」原样渲染到界面上，<b>三条门禁全绿</b>。
     * 第七批量到同一层还有 277 条，其中最大的一块是
     * {@code StructureSqlBuilder} 给结构变更计划拼的中文标题，
     * 正被结构页拼进状态条与确认弹窗（记为 D-9，<b>R45 G3-a 已修</b>）。
     *
     * <p>口径与 ui 侧完全一致：同一套 {@code stripComments} + 同一条正则，
     * 所以 {@code node tools/cjk-count.mjs --model} 能复现这个数字。
     * 只许降不许升 —— 它不证明「哪些会流到界面」，那只证明<b>没人新写</b>，
     * 并且把 D-9 这类债变成有数字的队列，而不是每次实拍帧撞见才发现。
     *
     * <p>⭐ 277 → 290 的唯一一次上调（D-3，2026-10-31）：新增 SQL Server / Oracle
     * 两种方言，带来 13 条<b>驱动级校验/提示</b>字面量（SqlServer 6、Oracle 7，
     * 例如「标识符不能为空」「自增列必须是整型」）。它们和既有方言里的同款文案
     * 是同一笔债，不是新长出来的界面文案 —— 向导的 TLS 说明已经改成资源键
     * （{@code wizard.ssl.notice.*}），正文在资源包里由界面渲染。
     *
     * <p>⭐ 290 → <b>170</b>（R45 G3-a，2026-11-02）：<b>结构变更这一整块键化</b> ——
     * {@code StructurePlan} 的 {@code title} 字段换成 {@code titleKey + titleArgs}，
     * {@code StructureSqlBuilder} 与五个 {@code Dialect} 的校验异常换成
     * {@link io.agenticdb.jdbc.ColumnProblem}（同样是「键 + 参数」），
     * 文字只在两个渲染口长出来：{@code StructurePlanRunner.title()} 与
     * {@code NewTableDialog.problemText()}。掉了 {@code StructureSqlBuilder}
     * 这一整层的 120 条（含它给计划标题拼的中文，D-9 最大的一块）。
     *
     * <p>⭐ 170 → <b>145</b>（R45 G3-b 批 1，2026-11-02）：<b>错误文案键化</b> ——
     * 这批是剩余里<b>最容易上屏</b>的一块：任何一次失败都会走状态栏那句
     * 「说明 + 原文 + 建议」，而 {@code ErrorClassifier} 把中文写死在模型层，
     * 连连接符「：」「——」都是中文的（D-11）。现在 {@code Diagnosis} 交
     * {@code (kind, summaryKey, adviceKey, detail)}，{@code ErrorLocator} 交建议键，
     * 渲染口只有 {@code ErrorText}（状态栏）和 {@code ErrorDetails}（弹窗）两处；
     * 顺带收掉了五个页面各抄一份的 {@code readable()}。
     * ⚠️ {@code ErrorClassifier.java = 1} 那条<b>不是文案</b>，是「只读」这个<b>匹配探针</b>：
     * 服务器用什么 locale 报错跟界面语言无关，删了就把一类真错误归不出来。
     * 清零时这条要写成豁免并给理由，<b>不许悄悄放宽尺子</b>。
     *
     * <p>剩下的 145 条按文件有账（同一轮棘轮内继续降，四批）：查询侧
     * （{@code FilterOperator} 14、{@code FilterCondition} 5、{@code DataSqlBuilder} 5、
     * {@code SortSpec} 3、{@code DataQuery} 4…）、导入导出（{@code TableImporter} 23、
     * {@code ExportFormat} 7、{@code TableExporter} 6…）、会话与凭据（{@code JdbcSession} 14、
     * {@code WindowsCredentialStore} 7、{@code ConnectionStore}/{@code CredentialStore} 各 3…）、
     * 方言与编辑（{@code OracleDialect}/{@code SqliteDialect}/{@code Danger} 各 4、
     * {@code RowIdentity} 3、{@code SqlServerDialect} 3…）。
     *
     * <p>⭐ 145 → <b>133</b>（R65 D-59，2026-11-05）：<b>传输三枚举键化</b> ——
     * {@code ImportMode}/{@code ImportFormat}/{@code ExportFormat} 的 label/hint 中文字面量
     * （2+3+7=12 条）换成 {@code labelKey()}/{@code hintKey()} 存键，语言在界面落地：
     * {@code ImportWizard} 给模式下拉挂 StringConverter，两向导把格式 hint 真上屏（D-83）。
     * 上面「剩下的 145 条按文件有账」里 {@code ExportFormat 7} 一笔清零，
     * {@code ImportMode}/{@code ImportFormat} 本就不在其中（label 直接进下拉，没被点名）。
     *
     * <p>⚠️ <b>这个 145 是怎么量出来的</b>：把本常量临时改成 0、跑
     * {@code -Dtest=I18nAuditTest#modelLayerChineseIsRatchetedDown}，
     * 读失败信息里的「当前残留 %d 条（按文件）」<b>再改回来</b>。
     * 尺子就是这条门禁自己，不存在口径漂移。
     * （文档里推荐的 <code>node tools/cjk-count.mjs --model</code> 在这台机器上跑不了 ——
     * 没有 node。别再抄文档里的旧数字：G3-a 开工前文档记的「186 顶格」实测是 170，
     * 差的正是那一批清掉的 {@code StructureSqlBuilder}。）
     */
    private static final int MODEL_CJK_CEILING = 133;

    /**
     * 源码里对 {@link I18n#t(String, Object...)} / {@link I18n#tPlural} 的键引用。
     *
     * <p>⚠️ 两个方法都要认。第一版只写 {@code I18n\.t\(}，而 {@code I18n.t(} 并<b>不是</b>
     * {@code I18n.tPlural(} 的子串（{@code t} 后面跟的是 {@code P} 不是括号），
     * 于是 tPlural 引用的键会整批落在「键存在性」门禁的视野之外 —— 门禁说它穷举了引用，
     * 其实漏了一整个方法。
     */
    private static final Pattern KEY_REFERENCE =
            Pattern.compile("I18n\\.(t|tPlural)\\(\\s*\"([^\"]+)\"");

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

    /**
     * ⭐ R34 收官：键集相等<b>看不见重复行</b>。
     *
     * <p>{@code .properties} 是后写覆盖先写，而两边都比的是 {@code Set} ——
     * 同一个键写两行、值不一样，这份审计照样绿。实测踩过：第十九批给对象详情面板
     * 写 {@code details.title=Object Details}，把第十三批的
     * {@code details.title=Cell Details} 顶掉了 —— 单元格抽屉的标题在两种语言里
     * 都变成「Object Details」，25 批全程门禁无感，是补帧目视才发现的。
     */
    @Test
    void noDuplicateKeyLinesInEitherBundle() throws IOException {
        for (String language : List.of("en", "zh")) {
            Path bundle = repositoryRoot().resolve(
                    "ui/adb-ui-core/src/main/resources/io/agenticdb/ui/messages_"
                            + language + ".properties");
            List<String> keys = new ArrayList<>();
            for (String line : Files.readAllLines(bundle, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals > 0) {
                    keys.add(trimmed.substring(0, equals).trim());
                }
            }
            assertThat(keys)
                    .as("messages_%s.properties 一个键都没扫到 —— 解析失效，假绿", language)
                    .isNotEmpty();
            Set<String> once = new LinkedHashSet<>();
            List<String> dupes = keys.stream().filter(key -> !once.add(key)).distinct().toList();
            assertThat(dupes)
                    .as("messages_%s.properties 里这些键写了不止一行：后写的覆盖先写的，"
                            + "而键集相等那条门禁（Set 比较）抓不到", language)
                    .isEmpty();
        }
    }

    @Test
    void everyKeyReferencedInSourceExists() throws IOException {
        Set<String> referenced = new LinkedHashSet<>();
        for (Path file : uiSources()) {
            Matcher matcher = KEY_REFERENCE.matcher(read(file));
            while (matcher.find()) {
                String key = matcher.group(2);
                // tPlural 取的是 <主干>.one / <主干>.other —— 主干本身不是键，展开成两形才对账。
                if ("tPlural".equals(matcher.group(1))) {
                    referenced.add(key + ".one");
                    referenced.add(key + ".other");
                } else {
                    referenced.add(key);
                }
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
     * ⭐ D-89：en bundle 的<b>硬复数棘轮</b>。
     *
     * <p>「1 rows」这类句子是英文里唯一能被机械抓住的复数错误：
     * 占位符后面紧跟一个以 s 结尾的名词。扫描有三个豁免，缺一不可：
     * <ol>
     *   <li><b>tPlural 管辖的主干</b>（有 {@code .one} 且有 {@code .other}）——
     *       它的 {@code .other} 本来就写着 {@code {0} rows}，单数由 {@code .one} 负责，
     *       不是硬复数；</li>
     *   <li><b>动词/虚词停用表</b> —— {@code {0} does not allow NULL} 里 {0} 是主语
     *       （方言名 / 表名），后面那个 -s 词是动词不是数量词；机械扫描约九成的命中是这类
     *       （D-89 台账的原话：不配停用表就是「向导测试 id」那种批量假阳性）；</li>
     *   <li><b>已知残留清单</b> —— 台账里 B 组的「能说对但和 A 组两个口径」的句子
     *       （{@code row(s)} 兜底写法、{@code {1} rows} 的伴随位、单位 {@code bytes}），
     *       本轮只棘轮冻结、不逐句改写；其中 {@code query.rowsAndDuration} 的句式
     *       归 D-75/Q13，未拍不擅动。</li>
     * </ol>
     *
     * <p>自证与棘轮分两句下（形状⑪的教训）：自证只断「已知集 ⊆ 扫描集」——
     * 正则或路径失效导致扫不到时，先红在自证句上，而不是把「扫空」当「清零」。
     */
    @Test
    void hardEnglishPluralsAreRatchetedDown() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Path bundle = repositoryRoot().resolve(
                "ui/adb-ui-core/src/main/resources/io/agenticdb/ui/messages_en.properties");
        for (String line : Files.readAllLines(bundle, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals > 0) {
                values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1));
            }
        }
        assertThat(values)
                .as("en bundle 一行都没读到 —— 路径或解析失效，这份审计是假绿")
                .isNotEmpty();

        Set<String> managedStems = new LinkedHashSet<>();
        for (String key : values.keySet()) {
            if (key.endsWith(".one")) {
                String stem = key.substring(0, key.length() - ".one".length());
                if (values.containsKey(stem + ".other")) {
                    managedStems.add(stem);
                }
            }
        }

        // {0} 是主语、后面跟的是动词的句子 —— 不是数量词，机械扫描对它们无能为力。
        Set<String> verbsAndFillers = Set.of(
                "does", "has", "had", "is", "was", "are", "were",
                "needs", "runs", "reports", "will", "can", "may", "must", "should", "not");
        Pattern hardPlural = Pattern.compile("\\{(\\d+)\\}\\s+([a-z]{2,}s)\\b");

        Set<String> scanned = new TreeSet<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String stem = key.endsWith(".one") || key.endsWith(".other")
                    ? key.substring(0, key.lastIndexOf('.')) : key;
            if (managedStems.contains(stem)) {
                continue;
            }
            Matcher matcher = hardPlural.matcher(entry.getValue());
            while (matcher.find()) {
                String word = matcher.group(2);
                if (!verbsAndFillers.contains(word)) {
                    scanned.add(key + ": {" + matcher.group(1) + "} " + word);
                }
            }
        }

        // 已知残留（B 组 + Q13 挡住的 rowsAndDuration）。往这个清单里加行 = 立案；
        // 从清单里减行 = 抽完了那一句，顺手把 A 组的对应键迁成 tPlural 两形。
        Set<String> knownRemaining = Set.of(
                "grid.msg.rectSelected: {1} cells",
                "shell.connectedObjects: {1} objects",
                "shell.currentSchema: {1} objects",
                "shell.objectsLoaded: {1} objects",
                "query.rowsAndDuration: {0} rows",
                "query.tabWithRows: {1} rows",
                "query.truncated: {0} rows",
                "query.exportedRows: {1} rows",
                "import.summary.preview: {0} rows",
                "import.done: {0} rows",
                "export.field.count: {1} fields",
                "export.done: {0} rows",
                "export.done.detail: {3} fields",
                "ddl.dropping: {0} tables",
                "ddl.dropped: {0} tables",
                "ddl.droppedPartial: {1} tables",
                "var.summary: {0} parameters",
                "colset.count: {1} columns",
                "filter.panel.error.limit: {0} conditions",
                "cell.blob: {0} bytes",
                "cell.jsonObject: {0} fields",
                "cell.jsonArray: {0} items",
                "plan.title.dropTables: {0} tables");

        assertThat(knownRemaining)
                .as("已知残留没被扫描器认出来 —— 正则或停用表改坏了，先修扫描器再谈棘轮")
                .allSatisfy(known -> assertThat(scanned).contains(known));

        List<String> newcomers = scanned.stream()
                .filter(hit -> !knownRemaining.contains(hit))
                .toList();
        assertThat(newcomers)
                .as("en bundle 出现了已知清单之外的「{N} + 复数名词」硬复数句 —— "
                        + "单数可能被读成「1 rows」；把该句迁成 I18n.tPlural 两形，"
                        + "或确认它属于 B 组兜底写法后点名进 knownRemaining")
                .isEmpty();
    }

    /**
     * 模型层里「长得像资源键」的字符串字面量。
     *
     * <p>白名单到<b>命名空间</b>：模型层合法地点号字符串很多
     * （{@code information_schema.tables}、驱动类名 {@code oracle.jdbc.OracleDriver}），
     * 只认落在这些前缀下的。副作用是好的 —— <b>新命名空间要先进这条门禁</b>，
     * 不能悄悄长出一片没人对账的键。
     *
     * <p>⭐ R58 加的 {@code query}：结果回写的卡点键由 {@code SqlQueryShape.Block} 与
     * {@code ResultEditPolicy.Block} 交出，键名写的是<b>字面量</b>而不是
     * {@code "前缀." + name()} —— 拼出来的键这条门禁扫不到，打错一个字母只有用户
     * 真跑到那一步才露出来。
     *
     * <p>⭐ R65 D-59 加的 {@code import} / {@code export}：传输三枚举（{@code ImportMode}、
     * {@code ImportFormat}、{@code ExportFormat}）把中文换成「每枚举一个键」之后，
     * 键名只出现在 workbench 的枚举常量里，ui 侧只剩 {@code I18n.t(format.hintKey())}
     * 这一个通用渲染口。{@code KEY_REFERENCE} 那条扫的是 ui/**\/src/main 里的
     * {@code I18n.t("字面量")}，这里传的是<b>方法返回值</b>，扫不到；于是「枚举里把
     * {@code export.format.xml.hint} 打成 {@code export.format.xtml.hint}」在改完那一刻
     * <b>没有任何门禁能看见</b>——编译期合法，运行时界面上露出 {@code ⚠export.format.xtml.hint}，
     * 而且只在用户真去选一次 XML 时露出来。补进命名空间白名单后由存在性那一段拦下。
     */
    private static final Pattern MODEL_KEY_LITERAL = Pattern.compile(
            "\"((?:plan|column|table|fk|index|trigger|structure|danger|error|query|import|export)"
                    + "\\.[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)*)\"");

    /**
     * 模型层交出的「键 + 参数」构造点：标记 + <b>键在第几个实参上</b>。
     *
     * <p>R45 G3-a 交的是「第 0 个实参是键、后面跟参数」；G3-b 的
     * {@code ErrorClassifier.Diagnosis} 是「类别 + <b>两个键</b> + 驱动原文」，
     * 所以这里必须能表达「键不在第 0 位、而且一行有两个键」。
     */
    private record ModelConstructor(String marker, int... keyPositions) {
    }

    private static final List<ModelConstructor> MODEL_KEY_CONSTRUCTORS = List.of(
            new ModelConstructor("new ColumnProblem(", 0),
            new ModelConstructor("new DesignStepException(", 0),
            new ModelConstructor("new StructurePlan(", 0),
            new ModelConstructor("new Diagnosis(", 1, 2));

    /**
     * ⭐ R45 G3-a：<b>模型层交出的键</b>必须在两份资源里都有，并且参数与占位符对得上。
     *
     * <h2>为什么 G3 之后必须补这一条</h2>
     * {@link #everyKeyReferencedInSourceExists()} 只扫 {@code ui/**}\/src/main 里的
     * {@code I18n.t("…")}。G3 把结构那一层的中文换成「键 + 参数」之后，
     * <b>键名写进了 core/workbench</b>（{@code new ColumnProblem("table.error.exists", …)}、
     * {@code new StructurePlan("plan.title.addColumn", …)}），ui 侧只剩两个通用渲染口
     * （{@code StructurePlanRunner.title} / {@code NewTableDialog.problemText}）。
     * 于是「键名打错一个字母」这件事<b>第一次没有任何门禁能看见</b>：编译期完全合法，
     * 运行时界面上长出 {@code ⚠plan.title.addColum} —— 而且只在用户真做一次加列操作时露出来，
     * 验收帧拍不到那一刻。这一条把视野补回来。
     *
     * <h2>两段判据</h2>
     * <ol>
     *   <li><b>存在性</b>按 {@link #MODEL_KEY_LITERAL} 扫，覆盖<b>透传</b>的键
     *       （{@code identifierProblem(value, "index.error.nameEmpty", …)} 里那个键
     *       最终才进 {@code new ColumnProblem(key, …)}，构造点扫不到它）。</li>
     *   <li><b>占位符对账</b>按 {@link #MODEL_KEY_CONSTRUCTORS} 的调用点形状解：
     *       {@code ColumnProblem} 的变长实参直接数，{@code StructurePlan} 的参数装在
     *       {@code List.of(...)} 里要再切一层；键名是变量、参数是 {@code Object...} 的
     *       写不出来就<b>跳过</b>（不误报），但那两类调用点自己已经由
     *       {@code adb-introspect} 的断言钉住参数（{@code titleArgs} / {@code args()}）。</li>
     * </ol>
     *
     * <p>少一个参数 → 界面上露出字面 {@code {1}}；多一个 → 静默少一段话。
     * 两侧（en/zh）各判一次，占位符数不一致本身就是「只补了一边」。
     */
    @Test
    void modelLayerKeysAreInTheBundlesAndAgreeWithTheirArguments() throws IOException {
        Properties en = bundle(Locale.ENGLISH);
        Properties zh = bundle(Locale.CHINESE);
        Set<String> keys = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        int parameterized = 0;

        for (Path file : modelSources()) {
            String source = stripComments(read(file));
            Matcher matcher = MODEL_KEY_LITERAL.matcher(source);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
            for (CallSite site : modelKeyCallSites(source)) {
                String enText = en.getProperty(site.key());
                String zhText = zh.getProperty(site.key());
                if (enText == null || zhText == null) {
                    // 缺键由存在性那一段统一报，这里不重复报同一笔。
                    continue;
                }
                int expectedEn = placeholders(enText);
                int expectedZh = placeholders(zhText);
                if (expectedEn > 0 || expectedZh > 0 || site.args() > 0) {
                    parameterized++;
                }
                if (site.args() != expectedEn) {
                    problems.add(relative(file) + " " + site.key()
                            + " 递了 " + site.args() + " 个参数，en 侧有 " + expectedEn + " 个占位符");
                }
                if (site.args() != expectedZh) {
                    problems.add(relative(file) + " " + site.key()
                            + " 递了 " + site.args() + " 个参数，zh 侧有 " + expectedZh + " 个占位符");
                }
            }
        }

        assertThat(keys)
                .as("模型层一个键字面量都没扫到 —— 命名空间白名单或构造名失效，这条门禁在空转")
                .isNotEmpty();
        List<String> missing = new ArrayList<>();
        for (String key : new TreeSet<>(keys)) {
            if (!en.containsKey(key)) {
                missing.add(key + " 缺 en");
            }
            if (!zh.containsKey(key)) {
                missing.add(key + " 缺 zh");
            }
        }
        assertThat(missing)
                .as("模型层交出但资源包里没有的键（界面上会长出 ⚠key）：\n  %s",
                        String.join("\n  ", missing))
                .isEmpty();
        assertThat(parameterized)
                .as("一个带参数的模型层调用点都没判到 —— 形状解析失效，这条门禁在空转")
                .isGreaterThan(10);
        assertThat(problems)
                .as("模型层交出的参数个数与占位符个数对不上：\n  %s",
                        String.join("\n  ", problems))
                .isEmpty();
    }

    /**
     * 解出模型层构造点的「键 + 参数个数」。写不出来的形状返回空 —— 宁可不判，
     * 也不要把门禁做成误报机（误报的门禁最终会被人关掉）。
     */
    private static List<CallSite> modelKeyCallSites(String source) {
        List<CallSite> sites = new ArrayList<>();
        for (ModelConstructor constructor : MODEL_KEY_CONSTRUCTORS) {
            int at = 0;
            while ((at = source.indexOf(constructor.marker(), at)) >= 0) {
                int open = at + constructor.marker().length() - 1;
                List<String> raw = splitTopLevel(source, open);
                for (int position : constructor.keyPositions()) {
                    if (position >= raw.size()) {
                        continue;
                    }
                    String key = stringLiteral(raw.get(position));
                    if (key != null && !key.isEmpty()) {
                        // 模型层构造点没有复数形：它们最终都走 I18n.t(单键)。
                        sites.add(new CallSite(key,
                                modelKeyArguments(constructor.marker(), raw), false));
                    }
                }
                at = open;
            }
        }
        return sites;
    }

    /**
     * {@code ColumnProblem} 是变长实参（第 2..n 个都是参数）；
     * {@code StructurePlan} 的参数装在第 2 个位置的 {@code List.of(...)} 里 ——
     * 五参数便捷构造没有参数列表，六参数才有；
     * {@code Diagnosis} 的两个键<b>都是死文案</b>（没有占位符），递任何参数都是错。
     */
    private static int modelKeyArguments(String marker, List<String> raw) {
        if (raw.size() < 2) {
            return 0;
        }
        if (marker.equals("new Diagnosis(")) {
            return 0;
        }
        if (marker.equals("new StructurePlan(")) {
            if (raw.size() < 6) {
                return 0;
            }
            String second = raw.get(1).trim();
            if (!second.startsWith("List.of(")) {
                return 0;
            }
            int nested = second.indexOf('(');
            List<String> inner = splitTopLevel(second, nested);
            if (inner.size() == 1 && inner.get(0).trim().isEmpty()) {
                // List.of() —— 无参标题。
                return 0;
            }
            return inner.size();
        }
        return raw.size() - 1;
    }

    /** 纯字符串字面量（中间没有引号、没有拼接）才返回内容，否则返回 {@code null}。 */
    private static String stringLiteral(String expression) {
        String text = expression.trim();
        if (text.length() < 2 || text.charAt(0) != '"'
                || text.charAt(text.length() - 1) != '"'
                || text.substring(1, text.length() - 1).indexOf('"') >= 0) {
            return null;
        }
        return text.substring(1, text.length() - 1);
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
     *
     * <p>⭐ D-78 追加：{@code I18n.tPlural("k", n, a, b)} 的调用点<b>两形各判一次</b>
     * （{@code k.one} 与 {@code k.other}），且实参个数<b>不</b>把选形那个 {@code n} 算进去 ——
     * 它不进占位符。少这一步，「只给复数形补了 {@code {1}}」这种文案要到「恰好一条」那一刻
     * 才露出字面量，而那一刻通常没人看着。
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
                for (String key : keysSearchedBy(site)) {
                    String enText = en.getProperty(key);
                    String zhText = zh.getProperty(key);
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
                        problems.add(relative(file) + " " + key
                                + " 递了 " + site.args() + " 个实参，en 侧有 " + expectedEn + " 个占位符");
                    }
                    if (site.args() != expectedZh) {
                        problems.add(relative(file) + " " + key
                                + " 递了 " + site.args() + " 个实参，zh 侧有 " + expectedZh + " 个占位符");
                    }
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

    /**
     * 一个调用点：键名、<b>顶层实参个数</b>（不含键本身，也不含 tPlural 的选形数），
     * 以及它是不是 {@code tPlural}（键要展开成两形）。
     */
    private record CallSite(String key, int args, boolean plural) {
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
     * 括号/引号/花括号配平地扫一遍，取出每个 {@code I18n.t(}／{@code I18n.tPlural(} 的顶层参数。
     * 第一个参数不是纯字符串字面量的（拼出来的键）直接跳过 —— 那种由动作穷举那条门禁管。
     *
     * <p>{@code tPlural} 的<b>第二个</b>实参是「拿哪个数去选形」，它不进占位符，
     * 所以顶层参数要扣两下（键 + 选形数）才是真正递给文案的值。
     */
    private static List<CallSite> callSites(String source) {
        List<CallSite> sites = new ArrayList<>();
        for (String marker : List.of("I18n.t(", "I18n.tPlural(")) {
            boolean plural = marker.equals("I18n.tPlural(");
            int at = 0;
            while ((at = source.indexOf(marker, at)) >= 0) {
                int open = at + marker.length() - 1;
                List<String> args = splitTopLevel(source, open);
                if (!args.isEmpty()) {
                    String first = args.get(0).trim();
                    if (first.length() >= 2 && first.charAt(0) == '"'
                            && first.charAt(first.length() - 1) == '"'
                            && first.substring(1, first.length() - 1).indexOf('"') < 0) {
                        int passed = args.size() - 1 - (plural ? 1 : 0);
                        if (passed >= 0) {
                            sites.add(new CallSite(
                                    first.substring(1, first.length() - 1), passed, plural));
                        }
                    }
                }
                at = open;
            }
        }
        return sites;
    }

    /**
     * 一个调用点<b>真正查的键</b>：{@code tPlural} 有两个形，两形都要对账 ——
     * 只给 {@code .other} 补了 {@code {1}} 而 {@code .one} 漏了，「一条」那一屏就会露出字面量。
     */
    private static List<String> keysSearchedBy(CallSite site) {
        return site.plural()
                ? List.of(site.key() + ".one", site.key() + ".other")
                : List.of(site.key());
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
     * ⭐ 第三把尺：模型层的汉字字面量（理由见 {@link #MODEL_CJK_CEILING}）。
     *
     * <p>扫描失效的自证与 ui 侧同构：一个文件都没扫到就红，不给「永远绿」的机会。
     */
    @Test
    void modelLayerChineseIsRatchetedDown() throws IOException {
        int total = 0;
        List<String> byFile = new ArrayList<>();
        List<Path> sources = modelSources();
        for (Path file : sources) {
            int hits = count(CJK_LITERAL, stripComments(read(file)));
            total += hits;
            if (hits > 0) {
                byFile.add(relative(file) + " = " + hits);
            }
        }
        assertThat(sources)
                .as("没扫到模型层主源码 —— 路径过滤失效，这条门禁是假绿")
                .isNotEmpty();
        assertThat(byFile)
                .as("模型层一个含中文字面量的文件都没有：要么真清零了（把上限一并降到 0），"
                        + "要么扫描口径变了")
                .isNotEmpty();

        assertThat(total)
                .as("core/workbench 主源码里的 CJK 字面量超过棘轮上限。这一层的中文不在 ui 棘轮"
                        + "视野内，却能顺着 label()/title() 直接流到界面上（D-7 就是这么漏的）。"
                        + "新增请改成「键 + 参数」由界面渲染；清掉一批请把 "
                        + "MODEL_CJK_CEILING 调小。\n当前残留 %d 条（上限 %d，按文件）：%s",
                        total, MODEL_CJK_CEILING, String.join("  ", byFile))
                .isLessThanOrEqualTo(MODEL_CJK_CEILING);
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
     * ⭐ 界面**不许直接渲染模型层的中文标签**（第六批撞上的：棘轮只扫 {@code ui/}，
     * 而 {@code Danger.label()} 的「只读 / 写数据 / 改结构 / 管理操作」长在
     * {@code core/adb-model} 里 —— 默认英文的查询页上钉着一颗「只读」，
     * 汉字棘轮一条都不红）。
     *
     * <p>这条不是"再一把尺子"，它补的是**扫描范围的边界**：文案可以在 ui/ 之外，
     * 只要它最终进界面，就得走 {@link DangerLabels} 这样的键映射，而不是把枚举字段直接 {@code setText}。
     */
    @Test
    void modelLayerDangerLabelsNeverReachTheUiDirectly() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : uiSources()) {
            String source = stripComments(read(file));
            List<String> hits = new ArrayList<>();
            var matcher = java.util.regex.Pattern.compile(
                    "danger\\.label\\(\\)|Danger\\.[A-Z_]+\\.label\\(\\)").matcher(source);
            while (matcher.find()) {
                hits.add(matcher.group());
            }
            if (!hits.isEmpty()) {
                offenders.add(relative(file) + " → " + String.join(", ", hits));
            }
        }
        assertThat(offenders)
                .as("界面直接渲染了 Danger.label()（模型层的中文标签，不在棘轮扫描范围内）——"
                        + " 换成 DangerLabels.t(danger)：\n  %s",
                        String.join("\n  ", offenders))
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

    /**
     * ⭐ D-78：{@code tPlural} 的<b>取形</b>行为 —— 静态门禁的另一半。
     *
     * <p>{@code TerminologyGuardTest} 只证明「两形都在资源里、调用点走的是 tPlural」，
     * 证明不了<b>「正好一条」那一刻取到的是单数形</b>。那一幕要在运行时判：
     * 帧证里 {@code p9-favorites} 拍到的是 {@code 1 favorites}，而它只在收藏恰好一条时出现。
     */
    @Test
    void pluralFormsFollowTheCountTheyAreGiven() {
        I18n.setLocale(Locale.ENGLISH);

        assertThat(I18n.tPlural("fav.summary", 1, 1))
                .as("一条收藏页脚那句")
                .isEqualTo("1 favorite");
        assertThat(I18n.tPlural("fav.summary", 3, 3)).isEqualTo("3 favorites");
        assertThat(I18n.tPlural("fav.summary", 0, 0))
                .as("英文只有 1 是单数，0 走复数形")
                .isEqualTo("0 favorites");

        // 选形数与显示值分开递：名词跟的是「共几张」，不是「显示几张」。
        assertThat(I18n.tPlural("tableList.showingOf", 5, 1, 5))
                .as("把选形绑死在 {0} 上会写出 \"Showing 1 of 5 table\"")
                .isEqualTo("Showing 1 of 5 tables");
        assertThat(I18n.tPlural("tableList.showingOf", 1, 1, 1))
                .isEqualTo("Showing 1 of 1 table");

        // 缺形不静默：和缺键同一套可见行为。
        I18n.clearMissingKeys();
        assertThat(I18n.tPlural("no.such.key", 2, 2)).isEqualTo("⚠no.such.key.other");
        assertThat(I18n.missingKeys()).contains("no.such.key.other");
        I18n.clearMissingKeys();
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

    /**
     * 模型层（core / workbench）的主源码 —— {@link #MODEL_CJK_CEILING} 的扫描范围。
     *
     * <p>不含 bootstrap：那里 0 条，纳进来只会让「一个文件都没扫到」的自证更难判读。
     */
    private static List<Path> modelSources() throws IOException {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".java") && relative.contains("src/main/java")
                        && (relative.startsWith("core/") || relative.startsWith("workbench/"))) {
                    files.add(path);
                }
            }
        }
        assertThat(files)
                .as("没有扫到模型层主源码 —— 路径过滤失效，这条门禁是假绿")
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
