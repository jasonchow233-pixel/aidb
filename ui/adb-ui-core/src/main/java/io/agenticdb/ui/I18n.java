package io.agenticdb.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⭐ 文案层 —— 2026-10-28「和 Navicat 一模一样」裁定（拍板 3）之后必须有的一层。
 *
 * <h2>为什么现在才做</h2>
 * 此前全应用是**中文硬编码**：77 个主源码文件、1404 条去重后的 CJK 字面量、零 ResourceBundle。
 * 实拍对照的 Navicat 是英文界面（{@code File / Edit / View / Query / Format / Favorites /
 * Tools / Window / Help}、{@code Open Table / Design Table / Import Wizard}），
 * 所以「一模一样」的第一步是把语言抽出来，而不是先把中文翻译成英文 ——
 * 抽完之后再改布局，否则每一轮视觉改动都要重做一遍字符串。
 *
 * <h2>默认英文，可切中文</h2>
 * 裁定原文是「建 i18n 层，中英双语，<b>默认英文</b>」。所以 {@link #DEFAULT_LOCALE} 是英文，
 * <b>不跟随系统 locale</b> —— 跟了系统 locale 的话，中文 Windows 上打开仍然是中文，
 * 与实拍对不上，而且验收帧会在不同机器上拍出不同语言（本项目的验收口径是逐张目视帧）。
 * 切换走 {@link #setLocale}（由 ui-shell 的主题/设置入口调用），或
 * {@code -Dadb.locale=zh}（给 {@code scripts/shots.sh} 与测试用，见 {@link #resolveFromProperty}）。
 *
 * <h2>三个实现上的坑，都是刻意绕开的</h2>
 * <ol>
 *   <li><b>{@code Properties.load(InputStream)} 永远是 ISO-8859-1</b>（规范写死的，与 JDK 版本无关）。
 *       中文资源里有大量非 ASCII，用字节流入口会静默变成 {@code ?} 或乱码，
 *       而且<b>不会抛异常</b> —— 只有肉眼才能发现。所以这里一律
 *       {@code load(new InputStreamReader(in, UTF_8))}。
 *       本仓库另一处同类坑是 {@code scripts/} 的 GBK 控制台输出，同一个根因族。</li>
 *   <li><b>不用 {@link MessageFormat} 做占位符。</b>它的规则是<b>单引号</b>开启一段
 *       literal 区，于是英文里最常见的缩写会把文案吃掉：
 *       {@code "Don't delete"} 经 MessageFormat 变成 {@code "Dont delete"}，
 *       要写成 {@code "Don''t delete"} 才对 —— 而这条规则对写文案的人完全不可见，
 *       且中文侧根本没有撇号，两套资源会不对称地坏。
 *       这里改用字面量 {@code {0}}/{@code {1}} 直替换（{@link #format}），
 *       行为可预测、两侧一致。需要真正的复数/日期/数字格式化时再单独引入，
 *       不要顺手把 {@code t()} 换成 MessageFormat。</li>
 *   <li><b>不走 {@code ResourceBundle} 的 locale 级联。</b>
 *       {@code getBundle(base, Locale.ENGLISH)} 在找不到 {@code messages_en} 时会
 *       回落到 JVM 默认 locale 再回落到基包，于是「默认英文」这条裁定会被
 *       一台中文机器上的中文基包悄悄推翻。这里只有两份具名文件，
 *       直接按名读，没有级联，也就没有意外。</li>
 * </ol>
 *
 * <h2>缺键不崩，但会记账</h2>
 * {@link #t} 找不到键时返回<b>看得见的标记</b>（{@code ⚠key}）并把键记进 {@link #missingKeys()}，
 * 而不是抛异常。理由：一个漏抽的键不该让整个窗口打不开（那会让验收帧全部拍不出来，
 * 反而掩盖真正的问题）；但也不能静默回落到键名 —— 界面上出现 {@code grid.toolbar.save}
 * 和出现 {@code ⚠grid.toolbar.save} 一样难看，区别是后者带 ⚠，人眼一眼能扫到。
 * 真正的门禁是 {@code I18nAuditTest}：建完整棵 UI 之后断言 {@link #missingKeys()} 为空。
 */
public final class I18n {

    /** 英文资源（本层的规范语言标识，`DEFAULT_LOCALE` 与切换逻辑都用它，不再各处写 `Locale.ENGLISH`）。 */
    public static final Locale ENGLISH = Locale.ENGLISH;

    /** 裁定口径：默认英文（与 Navicat 实拍一致）。 */
    public static final Locale DEFAULT_LOCALE = ENGLISH;

    /** 中文资源。 */
    public static final Locale CHINESE = Locale.CHINESE;

    /** 资源前缀（classpath 相对路径）。测试与 {@link #availableLocales()} 共用这两个常量，不再各写一遍。 */
    public static final String EN_RESOURCE = "/io/agenticdb/ui/messages_en.properties";
    public static final String ZH_RESOURCE = "/io/agenticdb/ui/messages_zh.properties";

    /** 给 {@code scripts/shots.sh} 与测试覆盖语言用的系统属性名。 */
    public static final String LOCALE_PROPERTY = "adb.locale";

    /** 当前语言包。volatile：{@link #setLocale} 可能在 FX 线程之外被测试调用。 */
    private static volatile Map<String, String> table;

    /** 当前生效语言。 */
    private static volatile Locale locale;

    static {
        // ⚠️ 类初始化就要读一次系统属性，否则 {@code -Dadb.locale=zh} 永远不生效 ——
        // 它只在有人调用 configure() 时才起作用，而启动链上目前没人调。
        // scripts/shots.sh 拍中文帧依赖这个属性，所以必须在静态初始化里就兑现。
        Locale fromProperty = resolveFromProperty();
        locale = fromProperty == null ? DEFAULT_LOCALE : fromProperty;
        table = load(locale == CHINESE ? ZH_RESOURCE : EN_RESOURCE);
    }

    /** 缺键账本。见类注释「缺键不崩，但会记账」。 */
    private static final Set<String> MISSING = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private I18n() {
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /**
     * 决定生效语言。优先级：{@code -Dadb.locale} &gt; 显式 {@code preferred} &gt; {@link #DEFAULT_LOCALE}。
     *
     * <p>系统属性排在最前是为了截图与测试：{@code scripts/shots.sh} 需要分别拍中英文两套帧，
     * 而不必为了换语言去改偏好存储。
     */
    public static synchronized Locale configure(Locale preferred) {
        Locale resolved = resolveFromProperty();
        if (resolved == null) {
            resolved = preferred == null ? DEFAULT_LOCALE : preferred;
        }
        return setLocale(resolved);
    }

    /** {@code -Dadb.locale} 解析结果；未设置或无法识别时为 {@code null}。 */
    private static Locale resolveFromProperty() {
        String raw = System.getProperty(LOCALE_PROPERTY, "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.startsWith("zh") ? CHINESE : ENGLISH;
    }

    /**
     * 切换语言并重新加载。
     *
     * <p>⚠️ 只影响<b>之后</b>构造的节点：JavaFX 的 {@code setText} 是一次性取值，
     * 已经建好的场景不会自动跟着变。运行时换语言必须像换主题那样重建场景
     * （见 {@code ThemeManager} 的调用方），这是 ui-shell 的职责，不在本层。
     */
    public static synchronized Locale setLocale(Locale requested) {
        Locale effective = requested != null && requested.getLanguage().startsWith("zh") ? CHINESE : ENGLISH;
        table = load(effective == CHINESE ? ZH_RESOURCE : EN_RESOURCE);
        locale = effective;
        MISSING.clear();
        return effective;
    }

    /** 当前生效语言。 */
    public static Locale locale() {
        return locale;
    }

    /** 全部可选语言。供设置面板枚举，避免界面里硬写「English / 中文」。 */
    public static Locale[] availableLocales() {
        return new Locale[]{ENGLISH, CHINESE};
    }

    private static Map<String, String> load(String resource) {
        Properties props = new Properties();
        try (InputStream in = I18n.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "语言包缺失：" + resource + "（classpath 资源没打进包里，检查 pom 的 resources 配置）");
            }
            // ⚠️ 必须是 Reader 入口。InputStream 入口永远按 ISO-8859-1 解，中文静默变乱码。
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读不了语言包：" + resource, e);
        }
        Map<String, String> copy = new TreeMap<>();
        for (String name : props.stringPropertyNames()) {
            copy.put(name, props.getProperty(name));
        }
        return Collections.unmodifiableMap(copy);
    }

    // ─────────────────────────── 取文案 ───────────────────────────

    /**
     * 取文案。缺键返回 {@code ⚠key} 并记账（见类注释）。
     *
     * @param key 资源键，例如 {@code menu.file}
     */
    public static String t(String key) {
        String value = table.get(key);
        if (value == null) {
            MISSING.add(key);
            return "⚠" + key;
        }
        return value;
    }

    /** 取文案并填 {@code {0}}/{@code {1}}… 占位符。 */
    public static String t(String key, Object... args) {
        return format(t(key), args);
    }

    /**
     * 字面量 {@code {i}} 替换 —— 不是 {@link MessageFormat}，理由见类注释第 2 条。
     *
     * <p>只替换严格形如 <code>{数字}</code> 的片段，其余字符原样输出，
     * 所以文案里出现撇号、花括号、百分号都不会被吃掉。
     */
    public static String format(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) {
            return pattern;
        }
        String out = pattern;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }

    /** 本进程内所有取不到的键。 */
    public static Set<String> missingKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(MISSING));
    }

    /** 清空缺键账本（测试用：先清再建 UI，才能断言「这一轮没漏键」）。 */
    public static synchronized void clearMissingKeys() {
        MISSING.clear();
    }

    /** 某语言包的全部键。{@code I18nAuditTest} 用它比对 en/zh 键集。 */
    public static Set<String> keys(Locale forLocale) {
        return new LinkedHashSet<>(
                load(forLocale.getLanguage().startsWith("zh") ? ZH_RESOURCE : EN_RESOURCE).keySet());
    }

    /** 当前语言包的键。 */
    public static Set<String> keys() {
        return new LinkedHashSet<>(table.keySet());
    }
}
