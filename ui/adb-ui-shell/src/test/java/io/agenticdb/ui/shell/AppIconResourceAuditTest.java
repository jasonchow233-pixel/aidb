package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 第十八轮门禁：**代码里点名要的图标资源必须真的存在**。
 *
 * <h2>为什么</h2>
 * {@code App} 注册窗口图标时写的是：
 * <pre>{@code
 * URL url = App.class.getResource("/io/agenticdb/ui/" + icon);
 * if (url != null) { ... }   // ← 缺文件时静默跳过，一行日志都没有
 * }</pre>
 * 于是「列表里写了 6 个、实际只加载 5 个」这种事不会有任何报错 ——
 * 实测就有一条：{@code app-icon-512.png} 从来没被生成过（按 {@code brand/README.md}
 * 的约定，512 这一档就叫 {@code app-icon.png}），它在列表里是个死名字。
 *
 * <p>这与 {@code ScreenshotScriptAuditTest} 是同一类问题：<b>失败路径被设计成静默降级</b>，
 * 单元测试只测「图标加载成功会怎样」，抓不到「名字写错了」。
 *
 * <h2>规则</h2>
 * 主源码里出现的每个 {@code app-icon*.png} 字面量，都必须能在
 * {@code ui/adb-ui-shell/src/main/resources/} 下找到同名文件。
 * （反过来不作断言：{@code 16/32} 两档按 {@code brand/README.md} 是给
 * 侧栏树节点 / 托盘预留的，尚未接进运行时，属正常。）
 */
class AppIconResourceAuditTest {

    private static final Pattern ICON_LITERAL = Pattern.compile("\"(app-icon[\\w-]*\\.png)\"");

    @Test
    void everyReferencedIconLiteralResolvesToARealResourceFile() throws IOException {
        Path root = repositoryRoot();

        List<String> referenced = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                // Windows 用 `\`：不归一化会一个文件都不扫，门禁静默变绿。
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.endsWith(".java") || !relative.contains("src/main/java")) {
                    continue;
                }
                Matcher matcher = ICON_LITERAL.matcher(
                        Files.readString(path, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (!referenced.contains(name)) {
                        referenced.add(name);
                    }
                }
            }
        }

        assertThat(referenced)
                .as("主源码至少要引用一个 app-icon（否则这份门禁没意义）")
                .isNotEmpty();

        Path resourceDir = root.resolve(
                "ui/adb-ui-shell/src/main/resources/io/agenticdb/ui");
        List<String> missing = referenced.stream()
                .filter(name -> !Files.isRegularFile(resourceDir.resolve(name)))
                .toList();
        assertThat(missing)
                .as("代码点名要的图标在资源目录里不存在 —— getResource 返回 null 后"
                        + "会被 `if (url != null)` 静默跳过，窗口/安装包悄悄少一张图")
                .isEmpty();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("core"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("从 " + System.getProperty("user.dir")
                + " 往上找不到仓库根（需要 pom.xml + core/）");
    }
}
