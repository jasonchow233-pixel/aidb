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
