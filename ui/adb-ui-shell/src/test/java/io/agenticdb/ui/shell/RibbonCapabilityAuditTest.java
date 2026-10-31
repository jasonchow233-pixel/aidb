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
 * ⭐ R36 门禁：Ribbon 的 12 格磁贴「每格都是真的」。
 *
 * <p>12 格里有六格（User / Backup / Automation / Model / BI + Others 的一半）背后
 * 是 R42 才建的能力面。诚实条款允许「还没建」，不允许「装了个死按钮」——
 * 所以每格必须同时满足：
 * <ol>
 *   <li>磁贴排顺序 = Navicat 17 真源 §1.2（12 格，不多不少）；</li>
 *   <li>动作在 AppShell 里注册了，且落点处理器**不是空转** ——
 *       要么是真实方法/路由，要么明确走 {@code openRibbonTool} 的如实说明页；</li>
 *   <li>图标有彩色资产（{@code icons/color/*.svg}），不会在满排彩色里掉回单色。</li>
 * </ol>
 *
 * <p>⚠️ 这是**源码审计**（同 {@code IconSystemAuditTest} 的路子）而不是场景图断言：
 * 它比 FX 测试快，而且钉的是「装配真相」（AppBar 的格子 ↔ AppShell 的注册），
 * 场景图那一份由 {@code AppBarTest#theRibbonCarriesTheNavicatTwelveTiles} 负责。
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
        assertThat(actionIds).as("功能区必须恰好 12 格（真源 §1.2）").hasSize(12);

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
                .as("顺序 = 真源 §1.2：Connection·New Query·Table·View·Function·User·Others"
                        + "｜Query·Backup·Automation·Model·BI")
                .containsExactly("new-connection", "new-query", "object.new-table",
                        "ribbon.view", "ribbon.function", "ribbon.user", "ribbon.others",
                        "saved-queries", "ribbon.backup", "ribbon.automation",
                        "ribbon.model", "ribbon.bi");
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
                .as("12 格磁贴的图标必须全部有彩色资产（Navicat 功能区是全 UI 最彩的一排）：\n  %s",
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

    // ─────────────────────────── 工具 ───────────────────────────

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
