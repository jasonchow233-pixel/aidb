package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
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
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            List<Path> sources = paths.filter(Files::isRegularFile).toList();
            for (Path source : sources) {
                scanned++;
                String text = Files.readString(source);
                Matcher literal = STRING_LITERAL.matcher(text);
                while (literal.find()) {
                    for (String[] pair : BANNED) {
                        if (literal.group(1).contains(pair[1])) {
                            offenders.add(source.getFileName() + "：「" + literal.group(1)
                                    + "」应为「" + pair[0] + "」");
                        }
                    }
                }
            }
        }
        // 扫不到文件本身就是问题：一条永远为绿的门禁比没有门禁更坏。
        assertThat(scanned)
                .as("必须真的扫到 main 源码，否则这条门禁是假绿")
                .isGreaterThan(40);
        assertThat(offenders)
                .as("同一动作出现第二个名字（术语漂移会让用户以为点了不同的地方）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }
}
