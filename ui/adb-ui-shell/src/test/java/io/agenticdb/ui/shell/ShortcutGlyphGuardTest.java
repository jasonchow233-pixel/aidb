package io.agenticdb.ui.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快捷键符号门禁（U1，2026-10-26）。
 *
 * <p>问题：macOS 的 ⌘ ⌥ ⇧ ⌃ 曾被**手写**进用户可见文案里（菜单项、tooltip、
 * 状态栏提示共 28 处）。本仓库的实拍截图全部来自 Windows，那里的键盘上没有
 * ⌘ 这个键 —— 用户看到只会愣住。
 *
 * <p>口径：源码只写语义（{@code "Mod+K"}），符号交给
 * {@link io.agenticdb.ui.Shortcuts#of(String)} 按平台产出。因此**除
 * Shortcuts.java 本身**，任何生产代码里都不该再出现这些符号。
 *
 * <p>注释不计：注释是给读代码的人看的，用 ⌘ 说明「按平台主修饰键」反而更贴切；
 * 这里只查会被渲染给用户的那部分。
 */
@DisplayName("快捷键文案：macOS 符号只允许出现在 Shortcuts 里")
class ShortcutGlyphGuardTest {

    /** macOS 专属键位符号：出现在 Windows/Linux 的用户文案里就是错。 */
    private static final String MAC_ONLY_GLYPHS = "⌘⌥⌃⇧⌫⌦␣↵";

    /** 唯一被允许写符号的地方 —— 它自己就是做平台化的那一层。 */
    private static final String ALLOWED_FILE = "io/agenticdb/ui/Shortcuts.java";

    @Test
    @DisplayName("生产代码的用户可见文案里没有手写 macOS 符号")
    void macGlyphsLiveOnlyInShortcuts() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> sources = paths
                    .filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.endsWith(".java") && p.contains("/src/main/java/"))
                    .filter(p -> !p.contains("/target/"))
                    .map(p -> root.resolve(p))
                    .toList();
            for (Path source : sources) {
                String relative = root.relativize(source).toString().replace('\\', '/');
                scanned++;
                if (relative.endsWith(ALLOWED_FILE)) {
                    continue;
                }
                String text = userVisibleSource(Files.readString(source, StandardCharsets.UTF_8));
                for (int i = 0; i < text.length(); i++) {
                    if (MAC_ONLY_GLYPHS.indexOf(text.charAt(i)) >= 0) {
                        violations.add(relative + "：" + snippet(text, i));
                        break;
                    }
                }
            }
        }

        assertThat(scanned)
                .as("一个生产源码都没扫到 —— 路径过滤失效，门禁是假绿")
                .isGreaterThan(50);
        assertThat(violations)
                .as("手写 macOS 符号必须走 Shortcuts.of(\"Mod+…\")，"
                        + "否则 Windows 用户看到的是键盘上没有的键")
                .isEmpty();
    }

    /** 去掉注释：行注释截到 {@code //}，块注释（javadoc）整行丢弃。 */
    private static String userVisibleSource(String source) {
        StringBuilder kept = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("*") || stripped.startsWith("/*")) {
                continue;
            }
            int comment = stripped.indexOf("//");
            kept.append(comment < 0 ? stripped : stripped.substring(0, comment)).append('\n');
        }
        return kept.toString();
    }

    private static String snippet(String text, int at) {
        int from = Math.max(0, at - 30);
        int to = Math.min(text.length(), at + 30);
        return "…" + text.substring(from, to).replace('\n', ' ').strip() + "…";
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
