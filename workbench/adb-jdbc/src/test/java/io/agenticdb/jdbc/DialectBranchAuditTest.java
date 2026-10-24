package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 的<b>结构性</b>门禁：方言分支不许再散落到别的文件里。
 *
 * <p>A1 的承诺是「<b>加一种数据库 = 写一个 {@code Dialect} 实现 + 登记一项</b>」。
 * 只要还有文件写着 {@code dialect == DialectKind.XXX} 或者
 * {@code switch (session.dialect())}，这个承诺就是假的 ——
 * 加一种库还是得去改那些文件，漏一处就是「某个方言下功能悄悄不对」。
 *
 * <p>所以这里扫源码，把散落的方言分支挡在门外。
 */
class DialectBranchAuditTest {

    /**
     * 允许出现方言枚举项的文件。
     *
     * <p>这些地方要么就是方言本身，要么在做「反查枚举」（按 URL 猜方言）、
     * 工厂方法（造一个 SQLite 连接）、或默认值（向导的初始选择）。
     */
    private static final Set<String> ALLOWED = Set.of(
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/DialectKind.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/SqliteDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/PostgresDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/MySqlDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/JdbcSession.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/ConnectionSpec.java",
            "workbench/adb-session/src/main/java/io/agenticdb/session/ConnectionProfile.java",
            "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ConnectionWizard.java");

    /**
     * 散落的方言分支。
     *
     * <p>三种形态都要抓：
     * <ol>
     *   <li>提到具体方言项（{@code DialectKind.SQLITE}，含全限定名写法）；</li>
     *   <li>拿方言做相等比较；</li>
     *   <li>拿方言做 switch。</li>
     * </ol>
     */
    private static final Pattern BRANCH = Pattern.compile(
            "DialectKind\\s*\\.\\s*(SQLITE|POSTGRESQL|MYSQL)"
                    + "|(?:dialect|dialectKind)\\(\\)\\s*(?:==|!=)"
                    + "|switch\\s*\\(\\s*(?:session\\.)?dialect\\(\\)");

    @Test
    void noDialectBranchesScatteredOutsideTheDialectLayer() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                // ⚠️ Windows 的 Path.toString() 用 `\`，白名单与排除规则用 `/`。
                //    不归一化的话，ALLOWED 一个都命中不了、`/target/` 也过滤不掉 ——
                //    这个门禁在 Windows 上会**把所有合法文件都报成违规**。
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.endsWith(".java")
                        || relative.contains("/target/")
                        || relative.contains("/test/")
                        || ALLOWED.contains(relative)) {
                    continue;
                }
                scanned++;
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (BRANCH.matcher(source).find()) {
                    violations.add(relative);
                }
            }
        }

        assertThat(scanned)
                .as("审计一个文件都没扫到 —— 路径过滤失效，门禁是假绿")
                .isGreaterThan(100);
        assertThat(violations)
                .as("方言分支散落在这些文件里 —— 加一种数据库就要改它们，"
                        + "A1 的承诺（一个 Dialect 实现就够）就破了：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    void everyDialectKindIsCoveredByAnImplementation() {
        // 每个枚举项都必须有实现 —— 少了实现，方言层就是残的。
        for (DialectKind kind : DialectKind.values()) {
            assertThat(kind.dialect())
                    .as("%s 必须有 Dialect 实现", kind)
                    .isNotNull();
        }
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("workbench"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
