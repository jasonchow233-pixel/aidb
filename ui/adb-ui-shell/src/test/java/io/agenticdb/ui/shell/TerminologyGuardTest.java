package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U15 门禁：同一个动作只有一个名字。
 *
 * <p>「查看数据 / 打开表」说的是同一件事，用户读到两个词会以为有两个去处；
 * 「总览 / 概览」「新建表 / 新建数据表」同理。这类漂移不会报错、不会变红，
 * 只会让界面读起来像不同的人各写了一半 —— 所以把它变成可执行的断言。
 *
 * <p>只检查**字符串字面量**里的文案：注释里提到旧名是有意的说明（历史、对照），
 * 不该被当成缺陷。
 */
class TerminologyGuardTest {

    /** 规范词 ← 禁用词。 */
    private static final List<String[]> BANNED = List.of(
            new String[]{"打开数据", "查看数据"},
            new String[]{"打开结构", "查看结构"},
            new String[]{"打开 DDL", "查看 DDL"},
            new String[]{"概览", "总览"},
            new String[]{"新建表", "新建数据表"});

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(.*?)\"", Pattern.DOTALL);

    /** 页签 helper 的**声明**：返回 Tab（或 void）且方法名叫 {@code tab}/{@code addTab}。 */
    private static final Pattern TAB_HELPER_DECL =
            Pattern.compile("\\bTab(?:<[^>]*>)?\\s+(addTab|tab)\\s*\\(([^)]*)\\)"
                    + "|\\bvoid\\s+(addTab|tab)\\s*\\(([^)]*)\\)");

    /** 形参名里表明「这一个就是上屏文案」的词。 */
    private static final Pattern TAB_LABEL_PARAM =
            Pattern.compile("\\bString\\s+(title|label|text|caption)\\b");

    /** 页签构造点：自己的 helper，和 JavaFX 的 {@code new Tab(}。 */
    private static final List<Pattern> TAB_CALLS = List.of(
            Pattern.compile("\\b(addTab|tab)\\s*\\("),
            Pattern.compile("\\bnew\\s+(Tab)\\s*\\("));

    @Test
    void oneActionHasExactlyOneName() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        // ⚠️ R34 起，文案搬进了 messages_*.properties —— 只扫 src/main/java 会让这条门禁
        // **静默失明**：禁用词从 Java 挪进资源文件就不再被看见，门禁全绿而界面照旧漂移。
        // 所以扫范围必须同时覆盖 ui 各模块的主源码与主资源。
        for (Path source : uiTextSources()) {
            scanned++;
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher literal = STRING_LITERAL.matcher(text);
            while (literal.find()) {
                check(source, literal.group(1), offenders);
            }
            if (source.toString().endsWith(".properties")) {
                // properties 的值不带引号，得按 key=value 逐行看值部分。
                for (String line : text.split("\\R")) {
                    int equals = line.indexOf('=');
                    if (equals < 0 || line.startsWith("#")) {
                        continue;
                    }
                    check(source, line.substring(equals + 1), offenders);
                }
            }
        }
        // 扫不到文件本身就是问题：一条永远为绿的门禁比没有门禁更坏。
        assertThat(scanned)
                .as("必须真的扫到 main 源码与资源，否则这条门禁是假绿")
                .isGreaterThan(40);
        assertThat(offenders)
                .as("同一动作出现第二个名字（术语漂移会让用户以为点了不同的地方）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    private static void check(Path source, String text, List<String> offenders) {
        for (String[] pair : BANNED) {
            if (text.contains(pair[1])) {
                offenders.add(source.getFileName() + "：「" + text + "」应为「" + pair[0] + "」");
            }
        }
    }

    /**
     * ⭐ D-77：省略号只有**一个**写法 —— `…`，不许再出现「三个句点」。
     *
     * <p>立案事实（2026-11-06 实测）：同一个 {@code messages_en.properties} 里
     * 「三个点」29 条、`…` 37 条并存（菜单项走前者、状态句走后者），
     * {@code messages_zh.properties} 则**全是** `…`（0 条三个点）——
     * 换语言时同一颗按钮的读法会跟着变，这是「一个口径一个出口」的反例。
     *
     * <p>⚠️ 判定写成「紧贴词字符的三个点」而不是 {@code contains("...")}：
     * {@code SELECT ... INTO} / {@code CREATE TABLE ...} 里的三个点是**省略语法**、
     * 两边带空格，不是省略号 —— 一刀切会把 SQL 例句改成读不通的 {@code SELECT … INTO}。
     */
    @Test
    void oneEllipsisStyle() throws IOException {
        Pattern asciiEllipsis = Pattern.compile("\\S\\.\\.\\.");
        List<String> offenders = new ArrayList<>();
        int nativeEllipsis = 0;
        for (Path source : uiTextSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher literal = STRING_LITERAL.matcher(text);
            while (literal.find()) {
                nativeEllipsis += countEllipsis(literal.group(1));
                hit(source, literal.group(1), asciiEllipsis, offenders);
            }
            if (source.toString().endsWith(".properties")) {
                for (String line : text.split("\\R")) {
                    int equals = line.indexOf('=');
                    if (equals < 0 || line.startsWith("#")) {
                        continue;
                    }
                    String value = line.substring(equals + 1);
                    nativeEllipsis += countEllipsis(value);
                    hit(source, value, asciiEllipsis, offenders);
                }
            }
        }
        // 反空转：扫描器要是压根没看见过 `…`，那「没有 offender」只是它没在扫。
        assertThat(nativeEllipsis)
                .as("必须真的扫到用 `…` 的文案，否则这条门禁是空转")
                .isGreaterThan(20);
        assertThat(offenders)
                .as("省略号两套写法并存（中英资源读法因此不一致）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    private static int countEllipsis(String text) {
        return text.replaceAll("[^\\u2026]", "").length();
    }

    /**
     * ⭐ D-80（第一批）：同一个概念的标签在**同一个界面里**只能有一种说法。
     *
     * <p>立案事实（2026-11-06 实测，两套 bundle 都漂）：设计器那张字段表里，
     * 列头是 {@code structure.col.nullable}（en `Nullable` / zh 「可空」），
     * 而它自己弹出的增改字段对话框里同一颗复选框是 {@code structure.dialog.nullable}
     * （en `Allow NULL` / zh 「允许 NULL」）—— 上下两屏两种说法，用户要先猜一次
     * 「Allow NULL 和 Nullable 是不是同一件事」。
     *
     * <p>⚠️ 这条只钉「同概念同叫法」，不钉正反极性：
     * 新建表对话框那列仍然叫「不为空 / Not Null」（{@code newtable.col.notNull}），
     * 它是**反着**绑定的（勾上 = NOT NULL，见 {@code NewTableDialog.java:680/1902}），
     * 跟这里的正向「可空」并存。把前者翻成正向要同时改绑定、列 id、SQL 生成与 67 条
     * {@code NewTableDialogTest}，属于行为改动，不属于本批文案改动 —— 已另立 D-88。
     */
    @Test
    void sameConceptUsesOneLabelPerBundle() throws IOException {
        List<String[]> pairs = List.<String[]>of(
                new String[]{"structure.col.nullable", "structure.dialog.nullable"});
        for (Path bundle : bundles()) {
            java.util.Properties props = new java.util.Properties();
            try (var in = Files.newInputStream(bundle)) {
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String[] pair : pairs) {
                String left = props.getProperty(pair[0]);
                String right = props.getProperty(pair[1]);
                assertThat(left).as("键 %s 在该 bundle 里不存在", pair[0]).isNotNull();
                assertThat(right).as("键 %s 在该 bundle 里不存在", pair[1]).isNotNull();
                assertThat(right)
                        .as("%s 的标签与 %s 是同一个概念，不该有两种叫法（bundle=%s）",
                                pair[1], pair[0], bundle.getFileName())
                        .isEqualTo(left);
            }
        }
    }

    /**
     * ⭐ D-78：计数汇总句必须有「一条」与「多条」两种写法。
     *
     * <p>立案事实（2026-11-06 实测）：en 侧把名词一律写死成复数 —— 帧证最狠的是
     * {@code p9-favorites} 那颗收藏，页脚写着 <b>「1 favorites」</b>；同一族的还有
     * {@code conn.status}（{@code {0} connections · {1} shown}）、{@code saved.summary}、
     * {@code tableList.showingOf}（{@code Showing {0} of {1} tables} → 1 of 1 也说 tables）。
     *
     * <p>⚠️ 为什么用两个键而不是 choice 语法：{@code I18n} 类注释第 2 条明确**不用
     * MessageFormat**（单引号会把 {@code Don't} 吃掉，中文侧还没撇号，两边会不对称地坏），
     * {@code I18nAuditTest#placeholdersAreSubstitutedLiterallyNotByMessageFormat} 就是把这条钉着的门禁。
     * 所以 {@code {0,choice,1#…|1<…}} 这条路是堵的，只能 {@code .one} / {@code .other} 两键，
     * 由 {@code I18n.tPlural} 按数取其一。
     *
     * <p>⚠️ 两形的**占位符集合必须一致**（{@code .one} 仍写 {@code {0}}，不把数字烤进文案）：
     * 一是实参个数由调用点决定，两形不同构就会有一半露出字面 {@code {1}}；
     * 二是 {@code I18nAuditTest} 那条「实参个数 ⊆ 占位符个数」的门禁继续按同一个数对账
     * （tPlural 的调用点现在<b>两形各判一次</b>，选形那个数不进占位符、也不算实参）。
     *
     * <p>⚠️ 选形数与显示值是<b>分开递</b>的，不是「第一个实参就是那个数」：
     * {@code tableList.showingOf} 那句 {@code Showing {0} of {1} tables} 里，
     * 名词跟的是<b>第二个</b>数（总数）—— 「显示 1 / 共 5 张表」仍是 tables。
     * 把选形绑死在 {@code {0}} 上会写出「Showing 1 of 5 table」。
     *
     * <p>⚠️ 本条只管**立案的四句**。全量实测另有 33 句同族（{@code N rows}、{@code N objects}、
     * {@code Dropped N tables}…），其中 {@code query.rowsAndDuration} 那一支归 D-75/Q13 未拍，
     * {@code Showing {0} of {1}} 这种「一个名词两个数」的还要先定句式 —— 已另立 D-89 带清单。
     */
    @Test
    void countSummariesHaveBothPluralForms() throws IOException {
        List<String> summaries = List.of(
                "conn.status", "fav.summary", "saved.summary", "tableList.showingOf");
        List<String> bareLeft = new ArrayList<>();
        List<String> sameShape = new ArrayList<>();
        java.util.Properties en = null;
        java.util.Properties zh = null;
        for (Path bundle : bundles()) {
            java.util.Properties props = new java.util.Properties();
            try (var in = Files.newInputStream(bundle)) {
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            if (bundle.getFileName().toString().equals("messages_en.properties")) {
                en = props;
            } else if (bundle.getFileName().toString().equals("messages_zh.properties")) {
                zh = props;
            }
            for (String key : summaries) {
                String one = props.getProperty(key + ".one");
                String other = props.getProperty(key + ".other");
                assertThat(one)
                        .as("%s.one 在 %s 里不存在 —— 缺一条就等于界面还在说「1 favorites」",
                                key, bundle.getFileName())
                        .isNotNull();
                assertThat(other)
                        .as("%s.other 在 %s 里不存在", key, bundle.getFileName())
                        .isNotNull();
                if (props.getProperty(key) != null) {
                    bareLeft.add(bundle.getFileName() + " → " + key);
                }
                // 两形必须同构：占位符集合一样，实参个数才对得上同一个数。
                if (!placeholderIndexes(one).equals(placeholderIndexes(other))) {
                    sameShape.add(bundle.getFileName() + " → " + key
                            + "：.one " + placeholderIndexes(one) + " / .other "
                            + placeholderIndexes(other));
                }
            }
        }
        assertThat(bareLeft)
                .as("裸键还留着就是第二出口（改句式或换语言时只改到一半）：\n  %s",
                        String.join("\n  ", bareLeft))
                .isEmpty();
        assertThat(sameShape)
                .as(".one 与 .other 的占位符集合不一致：\n  %s", String.join("\n  ", sameShape))
                .isEmpty();

        // en 侧才谈单复数：一条那形必须<b>真的是另一句话</b>，且不许照抄复数名词。
        assertThat(en)
                .as("没扫到 en 侧 bundle —— 单复数这半条门禁会空转")
                .isNotNull();
        assertThat(zh)
                .as("没扫到 zh 侧 bundle —— 「中文两形同文」那半条门禁会空转")
                .isNotNull();
        List<String> sameSaid = new ArrayList<>();
        List<String> zhSplit = new ArrayList<>();
        List<String> stillPlural = new ArrayList<>();
        for (String key : summaries) {
            String one = en.getProperty(key + ".one");
            String other = en.getProperty(key + ".other");
            if (one.equals(other)) {
                sameSaid.add(key + "：en 两形写成同一句「" + one + "」—— 换出口也救不了「1 favorites」");
            }
            if (!zh.getProperty(key + ".one").equals(zh.getProperty(key + ".other"))) {
                zhSplit.add(key + "：zh 两形不同 —— 中文没有单复数，同一个数不该有两种说法");
            }
            Matcher noun = COUNT_THEN_PLURAL.matcher(other);
            while (noun.find()) {
                String asWritten = "{" + noun.group(1) + "} " + noun.group(2);
                if (one.contains(asWritten)) {
                    stillPlural.add(key + "：.one 仍是「" + one + "」，该写成单数（" + asWritten + "）");
                }
            }
        }
        assertThat(sameSaid)
                .as("en 侧「一条」与「多条」是同一句话：\n  %s", String.join("\n  ", sameSaid))
                .isEmpty();
        assertThat(zhSplit)
                .as("zh 侧凭空造出第二种说法：\n  %s", String.join("\n  ", zhSplit))
                .isEmpty();
        assertThat(stillPlural)
                .as("en 侧「一条」那形仍读成复数：\n  %s", String.join("\n  ", stillPlural))
                .isEmpty();

        // 出口只许一个：调用点必须走 tPlural，不能再有 I18n.t("conn.status"…) 这种裸取。
        List<String> wrongExit = new ArrayList<>();
        for (Path source : shellSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String key : summaries) {
                if (Pattern.compile("I18n\\.t\\s*\\(\\s*\"" + java.util.regex.Pattern.quote(key) + "\"")
                        .matcher(text).find()) {
                    wrongExit.add(source.getFileName() + " → I18n.t(\"" + key + "\", …)");
                }
            }
        }
        assertThat(wrongExit)
                .as("这些调用点还在按裸键取文案（数词永远走 .other 那条复数）：\n  %s",
                        String.join("\n  ", wrongExit))
                .isEmpty();
    }

    /**
     * ⭐ D-70：表清单的列头**就是 Navicat 那六列**，且六列的字面写法要钉在帧上读到的原文。
     *
     * <p>立案时写的是「对象清单缺 Created 列」，依据是 {@code TableMeta} 的 javadoc
     * 那句「Navicat 的表清单是 8 列」。2026-11-06 直接读两张独立官方帧
     * （{@code docs/screenshots/navicat/navicat-table-list-2026-10-24.png}、
     * {@code navicat17-object-list-2026-10-27.png}）——<b>两张都只有六列</b>：
     * Name / Rows / Data Length / Engine / Modified Date / Comment，
     * <b>没有 Created，也没有 Collation</b>。那句 javadoc 是错的，条目据此翻案：
     * 要清的不是「补一列」，而是那句假事实留下的三件残骸 ——
     * 死键 {@code column.created}（en 侧注释还写着「N14 会撤掉」）、
     * 从没接上的 {@code object.column.created} / {@code object.column.updated}，
     * 以及 {@code TableMeta} 里两个只有测试在调的显示 helper。
     *
     * <p>⚠️ 这条断言<b>不许写成 {@code isEqualTo(I18n.t("column.rows"))}</b>：
     * 那种写法拿 bundle 自己当期望值，改错文案时两边一起错，门禁照绿
     * （同一形状见 {@code ObjectSectionsPanelTest} 里的提醒）。这里把六列原文
     * <b>按帧字面</b>写死在测试里，en 侧对帧，zh 侧钉我们自己的对译口径
     * （官方无中文帧，zh 文案漂移只能靠这条拦住）。
     *
     * <p>变异（2026-11-06 各自红自己）：M1 en {@code column.updated}→"Updated" 红在帧对账；
     * M2 zh {@code column.rows}→「行数」红在同一条款的 zh 侧；
     * M3 把 {@code column.created} 加回 en bundle、M5 把 {@code object.column.updated}
     * 加回 zh bundle —— 都红在死键复活那条款；
     * M4 在 {@code TableListPage} 的六列后加第七列（用 {@code column.collation}，
     * 这个键本来就在，所以只有源码那半条能抓住）红在列头键集合；
     * M6 让 {@code TableMeta.updatedText()} 改读 {@code createdAt}（拿创建时间冒充修改时间）
     * 红在 {@code TableMetaTest#carriesEveryFieldTheUiAsksFor}。
     */
    @Test
    void tableListHeadersMatchTheNavicatFrames() throws IOException {
        // 顺序 = 帧上从左到右的列序，也是 TableListPage 的列序。
        List<String> headerKeys = List.of("column.name", "column.rows", "column.dataLength",
                "column.engine", "column.updated", "column.comment");
        List<String> enHeaders = List.of("Name", "Rows", "Data Length", "Engine",
                "Modified Date", "Comment");
        List<String> zhHeaders = List.of("名称", "行", "数据长度", "引擎", "修改日期", "注释");
        // 帧上没有、代码也从没接过的列头键 —— 复活它们等于把界面拉回一个 Navicat 没有的布局。
        List<String> deadHeaderKeys = List.of("column.created",
                "object.column.created", "object.column.updated");

        List<String> drift = new ArrayList<>();
        List<String> resurrected = new ArrayList<>();
        Map<String, java.util.Properties> byBundle = new LinkedHashMap<>();
        for (Path bundle : bundles()) {
            java.util.Properties props = new java.util.Properties();
            try (var in = Files.newInputStream(bundle)) {
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String name = bundle.getFileName().toString();
            byBundle.put(name, props);
            List<String> expected = switch (name) {
                case "messages_en.properties" -> enHeaders;
                case "messages_zh.properties" -> zhHeaders;
                default -> null;
            };
            if (expected == null) {
                continue;
            }
            for (int i = 0; i < headerKeys.size(); i++) {
                String key = headerKeys.get(i);
                String asSaid = props.getProperty(key);
                if (asSaid == null) {
                    drift.add(name + " → " + key + " 整个不存在（帧上明明有这一列）");
                } else if (!asSaid.equals(expected.get(i))) {
                    drift.add(name + " → " + key + "＝「" + asSaid + "」，帧上写的是「"
                            + expected.get(i) + "」");
                }
            }
            for (String key : deadHeaderKeys) {
                if (props.getProperty(key) != null) {
                    resurrected.add(name + " → " + key + "＝「" + props.getProperty(key) + "」");
                }
            }
        }
        assertThat(byBundle.keySet())
                .as("没扫到 en/zh 两套主 bundle —— 列头对帧这半条门禁会空转")
                .contains("messages_en.properties", "messages_zh.properties");
        assertThat(drift)
                .as("列头文案与官方帧不一致：\n  %s", String.join("\n  ", drift))
                .isEmpty();
        assertThat(resurrected)
                .as("Navicat 没有 Created/Collation 列，这几个键是那句假 javadoc 的残骸：\n  %s",
                        String.join("\n  ", resurrected))
                .isEmpty();

        // 源码那半条：表清单这一页只许引这六个列头键（多引一个 = 第七列回来了）。
        String tableListSource = Files.readString(
                repositoryRoot().resolve("ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell"
                        + "/TableListPage.java"), StandardCharsets.UTF_8);
        Set<String> referenced = new LinkedHashSet<>();
        Matcher header = Pattern.compile("I18n\\.t\\s*\\(\\s*\"(column\\.[A-Za-z]+)\"")
                .matcher(tableListSource);
        while (header.find()) {
            referenced.add(header.group(1));
        }
        assertThat(referenced)
                .as("六列的键一个都没扫到 = 正则或路径失效，「恰好六列」那半条会空转")
                .containsAll(headerKeys);
        assertThat(referenced)
                .as("表清单引了六列之外的列头键（多一个 = Navicat 没有的第七列回来了）")
                .containsExactlyInAnyOrderElementsOf(headerKeys);
    }

    /** 值里出现的占位符下标集合（{@code {0}}/{@code {1}} → {0,1}）。 */
    private static Set<Integer> placeholderIndexes(String value) {
        Set<Integer> indexes = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\{(\\d+)}").matcher(value);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        return indexes;
    }

    /** 「占位符 + 复数名词」的形状。 */
    private static final Pattern COUNT_THEN_PLURAL =
            Pattern.compile("\\{(\\d)}\\s+([A-Za-z]+s)\\b");

    /** 两套主 bundle（en / zh）—— 文案口径必须两边都成立。 */
    private static List<Path> bundles() throws IOException {
        Path root = repositoryRoot();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.getFileName().toString().matches("messages_[a-z]{2}\\.properties"))
                    .filter(p -> p.toString().replace('\\', '/').contains("src/main/resources"))
                    .forEach(found::add);
        }
        assertThat(found)
                .as("一个 bundle 都没扫到 = 这条门禁空转")
                .hasSizeGreaterThanOrEqualTo(2);
        return found;
    }

    /**
     * ⭐ D-76：页签标签也只能从 bundle 里取，不许在源码里硬贴一个词。
     *
     * <p>立案事实（2026-11-06 实测）：{@code StructurePage} 那七个页签里六个走
     * {@code I18n.t("structure.tab.*")}，最后一个硬写 {@code tab("DDL", …)}；
     * {@code DataGridPage} 的对象切面同理，{@code addTab(SECTION_SQL, "SQL")}。
     * 一整排标签里有一颗不走同一出口 —— 换语言时它不会跟着换，
     * 而门禁全绿（其余六颗都好）。
     *
     * <p>⚠️ 这条钉的是**出口**，不是名字：数据页那颗「SQL」摆的是
     * 「库返回的 DDL + 按当前方言生成的一条 SELECT」（{@code ObjectSectionsPanel.buildSqlText}），
     * 结构页那颗「DDL」只摆 DDL 本身，且明确只读（{@code StructurePage} 里
     * {@code ddlEditor.area().setEditable(false)}，注释写着「DDL 是内省/生成出来的，不允许在这里改」）。
     * 两者内容不同，把它们改成同一个词才是新的错 —— 原条目里
     * 「SQL/DDL 命名分叉要统一成一个」这半句<b>翻案</b>（2026-11-06 回代码核过）。
     *
     * <p>顺手扫出来的第二族（同一缺陷、原条目没写）：{@code CellDetailsDrawer} 的
     * {@code new Tab("JSON")}／{@code new Tab("Hex")} 也是字面量标签 —— 一整排五个页签里
     * Text/Image/Metadata 走 bundle，这两颗不走。四颗一起接进 bundle：
     * {@code grid.tab.sql}、{@code structure.tab.ddl}、{@code details.tab.json}、
     * {@code details.tab.hex}（中文侧 Hex 翻成「十六进制」，正是这条门禁要保住的那点差异）。
     */
    @Test
    void everyTabLabelComesFromABundle() throws IOException {
        List<Path> sources = shellSources();
        int declarationsRead = 0;
        // 「标签在第几个参数」这件事**从声明里读**，不在测试里再造第二份签名知识：
        //   ObjectTabBar.addTab(id, label)     → 1
        //   StructurePage.tab(title, content)  → 0
        //   ConnectionWizard.tab(id, title, …) → 1（第一位是测试 id，不是文案）
        //   NewTableDialog.tab(title, id, …)   → 0
        // 三个同名私有 helper 的签名互不相同，按名字硬编一条规则必然看错位置 ——
        // 上一版就是按「第一位是不是引号」判，把连接向导的 7 个 id 全报成缺陷。
        Map<String, java.util.Set<Integer>> declaredGlobally = new LinkedHashMap<>();
        Map<Path, Map<String, Integer>> declaredInFile = new LinkedHashMap<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Map<String, Integer> labels = new LinkedHashMap<>();
            Matcher declaration = TAB_HELPER_DECL.matcher(text);
            while (declaration.find()) {
                boolean tabReturning = declaration.group(1) != null;
                int position = labelPosition(tabReturning
                        ? declaration.group(2) : declaration.group(4));
                if (position >= 0) {
                    declarationsRead++;
                    labels.put(tabReturning ? declaration.group(1) : declaration.group(3),
                            position);
                    declaredGlobally
                            .computeIfAbsent(tabReturning
                                    ? declaration.group(1) : declaration.group(3),
                                    k -> new LinkedHashSet<>())
                            .add(position);
                }
            }
            declaredInFile.put(source, labels);
        }

        Set<Integer> positionsRead = new LinkedHashSet<>();
        declaredInFile.values().forEach(labels -> positionsRead.addAll(labels.values()));
        assertThat(declarationsRead)
                .as("一条 helper 声明都没读出来 = 签名解析失效，下面全部断言都失去前提")
                .isGreaterThanOrEqualTo(4);
        assertThat(positionsRead)
                .as("读出来的标签位置里没有 0 也没有 1 —— 说明「标签在第几位」根本没按文件区分开，"
                        + "上一版把连接向导的 id 当文案报的那种错就是这么来的")
                .contains(0, 1);

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String[] call : tabCalls(text)) {
                checked++;
                String arguments = call[1];
                int position;
                if ("new Tab".equals(call[0])) {
                    position = 0; // JavaFX 自己的构造器：第一个参数就是 text
                } else {
                    Integer local = declaredInFile.get(source).get(call[0]);
                    java.util.Set<Integer> global = declaredGlobally.getOrDefault(
                            call[0], Set.of());
                    position = local != null ? local
                            : global.size() == 1 ? global.iterator().next() : -1;
                    if (position < 0) {
                        offenders.add(source.getFileName() + "：" + call[0]
                                + "(…) 的标签位置读不出来（声明里没找到 title/label 形参）："
                                + arguments.replace("\n", " ").strip());
                        continue;
                    }
                }
                List<String> parts = splitTopLevel(arguments);
                if (parts.size() > position && parts.get(position).strip().startsWith("\"")) {
                    offenders.add(source.getFileName() + "：" + call[0] + "("
                            + parts.get(position).strip() + ") —— 字面量当标签，换语言不跟着换");
                }
            }
        }
        assertThat(checked)
                .as("一个页签构造点都没扫到 = 这条门禁空转")
                .isGreaterThan(15);
        assertThat(offenders)
                .as("页签标签没走 bundle（换语言时这一颗不跟着换）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    /** 形参表里 {@code title}/{@code label}/{@code text}/{@code caption} 排第几；没有则 -1。 */
    private static int labelPosition(String parameterList) {
        List<String> parts = splitTopLevel(parameterList);
        for (int i = 0; i < parts.size(); i++) {
            if (TAB_LABEL_PARAM.matcher(parts.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 这一页里所有页签构造点：{@code addTab(…)}、{@code tab(…)}、{@code new Tab(…)}。
     * 每项是 {@code {方法名, 括号内的原文}}；括号没闭合的形状（跨行截断、注释里的半句）跳过。
     */
    private static List<String[]> tabCalls(String text) {
        List<String[]> calls = new ArrayList<>();
        for (Pattern pattern : TAB_CALLS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int open = text.indexOf('(', matcher.end() - 1);
                String arguments = topLevelParenContent(text, open);
                if (arguments == null) {
                    continue;
                }
                String name = "Tab".equals(matcher.group(1)) ? "new Tab" : matcher.group(1);
                calls.add(new String[]{name, arguments});
            }
        }
        return calls;
    }

    /** 从 {@code open}（左括号下标）走到配对右括号，返回括号内的原文；没闭合返回 null。 */
    private static String topLevelParenContent(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(open + 1, i);
                }
            }
        }
        return null;
    }

    /** 按**顶层**逗号切一段实参/形参：嵌套括号与字符串字面量里的逗号不算分隔。 */
    private static List<String> splitTopLevel(String segment) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '<') {
                depth++;
            } else if (c == ')' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(segment.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(segment.substring(start));
        return parts;
    }

    private static List<Path> shellSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repositoryRoot())) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("ui/adb-ui-shell/src/main/java"))
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(sources::add);
        }
        return sources;
    }

    private static void hit(Path source, String text, Pattern pattern, List<String> offenders) {
        if (pattern.matcher(text).find()) {
            offenders.add(source.getFileName() + "：「" + text + "」");
        }
    }

    /**
     * ⭐ D-79：`Select All` / `Select None` / `Deselect All` 这一族按钮一个大小写。
     *
     * <p>立案事实（2026-11-06 实测）：同一族里三种写法并存 ——
     * {@code action.table-list.select-all=Select All}、{@code export.columns.none=Select None}、
     * {@code filter.selectNone=Deselect all}。最后一条是**句首大写**，前两条是**词词大写**，
     * 排在一排按钮上时看着像来自两个不同的产品。
     *
     * <p>⚠️ 只管**两个词的按钮名**：{@code Select all rows on this page}
     * （无障碍标签、快捷键说明）是句子，句子就该用句首大写 —— 把它一起改成
     * {@code Select All Rows On This Page} 才是新的错。
     */
    @Test
    void selectButtonsUseOneCasing() throws IOException {
        Pattern family = Pattern.compile("(?i)^(deselect|select)\\s+(all|none)$");
        List<String> offenders = new ArrayList<>();
        int conforming = 0;
        for (Path source : uiTextSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            List<String> values = new ArrayList<>();
            Matcher literal = STRING_LITERAL.matcher(text);
            while (literal.find()) {
                values.add(literal.group(1));
            }
            if (source.toString().endsWith(".properties")) {
                for (String line : text.split("\\R")) {
                    int equals = line.indexOf('=');
                    if (equals >= 0 && !line.startsWith("#")) {
                        values.add(line.substring(equals + 1));
                    }
                }
            }
            for (String value : values) {
                if (!family.matcher(value).matches()) {
                    continue;
                }
                String canonical = switch (value.toLowerCase(java.util.Locale.ROOT)) {
                    case "select all" -> "Select All";
                    case "select none" -> "Select None";
                    case "deselect all" -> "Deselect All";
                    default -> null;
                };
                if (canonical == null || value.equals(canonical)) {
                    conforming++;
                    continue;
                }
                offenders.add(source.getFileName() + "：「" + value + "」应为「" + canonical + "」");
            }
        }
        assertThat(conforming)
                .as("必须真的扫到这族按钮名（否则「没有 offender」是没扫到）")
                .isGreaterThanOrEqualTo(2);
        assertThat(offenders)
                .as("同一族按钮两套大小写：\n  %s", String.join("\n  ", offenders))
                .isEmpty();
    }

    /** ui 两个模块的主源码 + 主资源（含 .properties）。 */
    private static List<Path> uiTextSources() throws IOException {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                // Windows 用 `\`：不归一化会一个文件都不扫，门禁静默变绿。
                String relative = root.relativize(path).toString().replace('\\', '/');
                boolean underUi = relative.startsWith("ui/");
                boolean text = relative.endsWith(".java") && relative.contains("src/main/java");
                boolean bundle = relative.endsWith(".properties")
                        && relative.contains("src/main/resources");
                if (underUi && (text || bundle)) {
                    files.add(path);
                }
            }
        }
        assertThat(files)
                .as("没有扫到 UI 的源码/资源 —— 路径过滤失效，这份审计是假绿")
                .isNotEmpty();
        return files;
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
