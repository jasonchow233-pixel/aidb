package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R36 门禁：Ribbon 的磁贴「每格都是真的」。
 *
 * <p>R36 是 12 格；P2 接上 User / Backup、P3 补 Data Profiling / Cross-DB Transfer
 * 之后、2026-11-09 移除 BI 之后是 <b>13 格</b>。其中三格
 * （Function / Automation / Model）仍落说明页。
 * 诚实条款允许「还没建」，不允许「装了个死按钮」—— 所以每格必须同时满足：
 * <ol>
 *   <li>磁贴排顺序 = Navicat 17 真源 §1.2 的 12 格，后接 P3 两格（不多不少）；</li>
 *   <li>动作在 AppShell 里注册了，且落点处理器**不是空转** ——
 *       要么是真实方法/路由，要么明确走 {@code openRibbonTool} 的如实说明页；</li>
 *   <li>图标有彩色资产（{@code icons/color/*.svg}），不会在满排彩色里掉回单色。</li>
 * </ol>
 *
 * <p>⚠️ 这是**源码审计**（同 {@code IconSystemAuditTest} 的路子）而不是场景图断言：
 * 它比 FX 测试快，而且钉的是「装配真相」（AppBar 的格子 ↔ AppShell 的注册），
 * 场景图那一份由 {@code AppBarTest#theRibbonCarriesTheThirteenCommandTiles} 负责。
 */
class RibbonCapabilityAuditTest {

    /** AppBar 里的磁贴声明：{@code ribbonButton("动作id", Icons.Icon.XXX, ...)}。 */
    private static final Pattern TILE = Pattern.compile(
            "ribbonButton\\(\"([^\"]+)\",\\s*Icons\\.Icon\\.([A-Z_]+)");

    /** AppShell 里「注册了某条动作」的位置（处理器在后续若干字符里，跨行）。 */
    private static final Pattern REGISTER = Pattern.compile(
            "actions\\.register\\(\"([^\"]+)\"");

    /** 空转形态：{@code () -> { }} 这类占位处理器，或 null 当末位参数。 */
    private static final Pattern EMPTY_HANDLER = Pattern.compile(
            "->\\s*\\{\\s*\\}|,\\s*null\\s*\\)");

    @Test
    void everyRibbonTileHasARegisteredActionWithARealLandingPoint() throws IOException {
        String appBar = read(shellSource("AppBar.java"));
        // 注册点在装配层各处（AppShell / SideBar / …），审计扫整个 shell 包 ——
        // 只扫 AppShell 会把 SideBar 里注册的 object.new-table 误判成死格。
        String assembly = assemblySources();

        List<String> actionIds = new ArrayList<>();
        Matcher tiles = TILE.matcher(appBar);
        while (tiles.find()) {
            actionIds.add(tiles.group(1));
        }
        assertThat(actionIds)
                .as("功能区必须恰好 13 格（真源 §1.2 的 12 格 + P3 的 Profile / Transfer）")
                .hasSize(13);

        Map<String, String> registrations = new LinkedHashMap<>();
        Matcher registers = REGISTER.matcher(assembly);
        while (registers.find()) {
            registrations.putIfAbsent(registers.group(1), assembly.substring(
                    registers.end(), Math.min(assembly.length(), registers.end() + 400)));
        }

        List<String> violations = new ArrayList<>();
        for (String actionId : actionIds) {
            String call = registrations.get(actionId);
            if (call == null) {
                violations.add(actionId + " 没有注册动作（点了会抛异常或没反应）");
                continue;
            }
            // 空转处理器 = 死按钮。诚实条款允许「本版本未提供」的说明页，
            // 不允许一格磁贴点了什么都没发生（`() -> { }` 或 null 处理器）。
            if (EMPTY_HANDLER.matcher(call).find()) {
                violations.add(actionId + " 的处理器不是真实落点："
                        + call.substring(0, Math.min(call.length(), 120)).replaceAll("\\s+", " "));
            }
        }
        assertThat(violations)
                .as("诚实条款：磁贴可以指向「本版本未提供」的说明页，但不许是空转：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    void theTileOrderMatchesTheNavicatRibbon() throws IOException {
        String appBar = read(shellSource("AppBar.java"));
        List<String> actionIds = new ArrayList<>();
        Matcher tiles = TILE.matcher(appBar);
        while (tiles.find()) {
            actionIds.add(tiles.group(1));
        }
        assertThat(actionIds)
                .as("顺序 = 真源 §1.2（12 格）+ P3 两格：Connection·New Query·Table·View"
                        + "·Function·User·Others｜Query·Backup·Profile·Transfer"
                        + "·Automation·Model（BI 已移除）")
                .containsExactly("new-connection", "new-query", "object.new-table",
                        "ribbon.view", "ribbon.function", "ribbon.user", "ribbon.others",
                        "saved-queries", "ribbon.backup", "ribbon.profiling",
                        "ribbon.transfer", "ribbon.automation",
                        "ribbon.model");
    }

    @Test
    void everyTileIconHasAColoredAsset() throws IOException {
        String appBar = read(shellSource("AppBar.java"));
        List<String> missing = new ArrayList<>();
        Matcher tiles = TILE.matcher(appBar);
        while (tiles.find()) {
            Icons.Icon icon = Icons.Icon.valueOf(tiles.group(2));
            if (!Icons.hasColoredVariant(icon)) {
                missing.add(tiles.group(1) + " → " + icon + "（icons/color/ 下没有同名资产）");
            }
        }
        assertThat(missing)
                .as("13 格磁贴的图标必须全部有彩色资产（Navicat 功能区是全 UI 最彩的一排）：\n  %s",
                        String.join("\n  ", missing))
                .isEmpty();
    }

    /** 反向半边：说明页那条路的每一格都要有标题与「未提供」文案，不能开出一个无名空页签。 */
    @Test
    void everyUnavailableToolRouteHasHonestCopy() throws IOException {
        for (String route : SideBar.TOOL_UNAVAILABLE_ROUTES) {
            assertThat(hasMessage("tool.title." + route))
                    .as("说明页签 %s 必须有标题文案", route)
                    .isTrue();
        }
        assertThat(hasMessage("workspace.tool.unavailable"))
                .as("未提供的能力面必须有统一的如实说明文案")
                .isTrue();
    }

    /**
     * ⭐ R62-a H5-i（拍板 Q8 第②案）：「未提供」角标 ⇔ 「落的是如实说明页」，两件事必须一起变。
     *
     * <p>角标说的是一句可核对的陈述：「这格现在没有真页面」。所以判据不在界面上，
     * 而在装配里 —— <b>处理器走 {@code openRibbonTool(SideBar.TOOL_…)}、
     * 且那个路由常量落在 SideBar 的说明页集合里</b>（{@code TOOL_UNAVAILABLE_ROUTES}
     * 与 {@code TOOL_OBJECT_ROUTES}）。
     *
     * <p>⚠️ 只查「有没有 {@code openRibbonTool}」这条老判据在 P2/P3 之后已经**失效**：
     * 这条通道现在既开说明页也开真工具页（Backup / User / Profile / Transfer），
     * 照旧判会把四格刚落地的能力面误算成「未提供」。判据改成从 SideBar 的路由集合
     * 反推，能力面一落地、常量移出集合，角标名单就被逼着同批摘掉。
     *
     * <p>⚠️ 读处理器时不能按「注册点往后 400 字符」截：那一段会溢进**下一条**注册
     * （{@code ribbon.others} 紧跟着 {@code ribbon.user}，第一版就是这么误判的）——
     * 截到下一个 {@code actions.register(} 为止。
     */
    @Test
    void thePendingBadgesMatchTheHonestLandingPoints() throws IOException {
        String appBar = read(shellSource("AppBar.java"));
        Matcher declared = Pattern.compile("PENDING_TILES\\s*=\\s*[^;]+;", Pattern.DOTALL)
                .matcher(appBar);
        assertThat(declared.find())
                .as("AppBar 里找不到 PENDING_TILES —— 名单被删了，那角标这条门禁就在空转")
                .isTrue();
        List<String> badged = quoted(declared.group());

        // ⭐ 判据从 SideBar 的「说明页路由集合」反推，不手写名单 ——
        //    这样能力面落地时名单会自己收窄，门禁逼着角标同批摘掉。
        java.util.Set<String> honestRoutes = honestRouteConstants(read(shellSource("SideBar.java")));
        assertThat(honestRoutes)
                .as("说明页路由常量不能是空的 —— 空集合会让下面那条断言假绿")
                .isNotEmpty();

        String assembly = assemblySources();
        List<String> honestOnly = new ArrayList<>();
        Matcher tiles = TILE.matcher(appBar);
        while (tiles.find()) {
            String handler = registrationOf(assembly, tiles.group(1));
            if (handler == null) {
                continue;
            }
            Matcher route = Pattern.compile("openRibbonTool\\(SideBar\\.(TOOL_[A-Z_]+)")
                    .matcher(handler);
            if (route.find() && honestRoutes.contains(route.group(1))) {
                honestOnly.add(tiles.group(1));
            }
        }
        assertThat(honestOnly)
                .as("判据本身不能是空的 —— 空名单时下面那条 containsExactly 就成了两句空话")
                .isNotEmpty();
        assertThat(badged)
                .as("角标必须挂在「落如实说明页」的那几格上，一格不多、一格不落")
                .containsExactlyInAnyOrderElementsOf(honestOnly);

        // 「用 AI 新建表」不在功能区，在对象清单页的七键里 —— 同一句实话（同一个资源键），
        // 但那一档只有文字、没有环：横排按钮包一层会撑宽，整排跟着挪（PendingMark#saySo）。
        assertThat(read(shellSource("TableListPage.java")))
                .as("对象清单页的 AI 新建表按钮（落 new-table-ai 说明页）必须提前说同一句实话")
                .contains("PendingMark.saySo(createAi");
        assertThat(hasMessage("ribbon.pending"))
                .as("角标的提示语必须两个语言包都有 —— 只写一份就是另一种语言里没说")
                .isTrue();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 从 {@code SideBar.java} 源码里读出「落说明页」的路由常量名。
     *
     * <p>两个集合都算：{@code TOOL_UNAVAILABLE_ROUTES}（本版本未提供）与
     * {@code TOOL_OBJECT_ROUTES}（对象类说明页，Function 那一格走这条）。
     * 读 <b>声明右侧的常量名</b>而不是路由字符串值 —— 处理器里写的也是常量名，
     * 两边对得上，且能力面落地移出集合时判据自动收窄。
     */
    private static java.util.Set<String> honestRouteConstants(String sideBar) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String declaration : new String[]{"TOOL_UNAVAILABLE_ROUTES", "TOOL_OBJECT_ROUTES"}) {
            Matcher set = Pattern.compile(declaration + "\\s*=\\s*([^;]+);", Pattern.DOTALL)
                    .matcher(sideBar);
            assertThat(set.find())
                    .as("SideBar 里找不到 %s 的声明 —— 判据会退化成空集合", declaration)
                    .isTrue();
            Matcher constant = Pattern.compile("TOOL_[A-Z_]+").matcher(set.group(1));
            while (constant.find()) {
                out.add(constant.group());
            }
        }
        return out;
    }

    /** 某条动作的处理器文本：从它的注册点截到**下一条**注册之前。 */
    private static String registrationOf(String assembly, String actionId) {
        int at = assembly.indexOf("actions.register(\"" + actionId + "\"");
        if (at < 0) {
            return null;
        }
        int next = assembly.indexOf("actions.register(\"", at + 1);
        return next < 0 ? assembly.substring(at) : assembly.substring(at, next);
    }

    /** 一段源码文本里出现的全部双引号字符串（按出现顺序）。 */
    private static List<String> quoted(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private static Path shellSource(String file) {
        Path path = Path.of("src", "main", "java", "io", "agenticdb", "ui", "shell", file);
        if (!Files.exists(path)) {
            path = Path.of("ui", "adb-ui-shell", "src", "main", "java",
                    "io", "agenticdb", "ui", "shell", file);
        }
        assertThat(path).as(file + " 路径").exists();
        return path;
    }

    /** 整个 shell 包的主源码拼在一起 —— 动作注册点不保证都在 AppShell 里。 */
    private static String assemblySources() throws IOException {
        Path dir = shellSource("AppBar.java").getParent();
        StringBuilder all = new StringBuilder();
        try (var paths = Files.list(dir)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(read(path));
            }
        }
        assertThat(all.length()).as("没扫到装配源码 —— 路径过滤失效，这份审计是假绿")
                .isGreaterThan(10_000);
        return all.toString();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static boolean hasMessage(String key) throws IOException {
        for (String bundle : new String[]{"messages_en.properties", "messages_zh.properties"}) {
            try (InputStream in = RibbonCapabilityAuditTest.class
                    .getResourceAsStream("/io/agenticdb/ui/" + bundle)) {
                assertThat(in).as(bundle).isNotNull();
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (!text.contains("\n" + key + "=")) {
                    return false;
                }
            }
        }
        return true;
    }
}
