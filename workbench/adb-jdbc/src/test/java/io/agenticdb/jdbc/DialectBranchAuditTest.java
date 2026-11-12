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
     *
     * <p>自资产层（{@code adb-asset}）引入后，<b>SQL 资产到 {@code DialectKind}
     * 的映射唯一定点是 {@code AssetKind}</b>——它取代原先散落在 {@code ConnectionProfile}
     * /{@code ConnectionCatalog} 里的方言枚举引用，成为 A1 承诺「加一种数据库只写
     * 一个 Dialect 实现 + 登记一项」在资产层的具体落点。因此那两个旧文件已不再持有
     * 方言枚举项，被移出白名单以收紧门禁；任何新的方言引用都必须回到 {@code AssetKind}。
     */
    private static final Set<String> ALLOWED = Set.of(
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/DialectKind.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/SqliteDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/PostgresDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/MySqlDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/MariaDbDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/SqlServerDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/OracleDialect.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/JdbcSession.java",
            "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/ConnectionSpec.java",
            // 资产层判别轴唯一定点：SQL 资产 → DialectKind 的映射只写在这里。
            "workbench/adb-asset/src/main/java/io/agenticdb/asset/AssetKind.java",
            // 向导同时承载 SQL / Redis / 对象存储三族，但仍需要一处 DialectKind
            // 默认值与 SQL 方言注册引用（非 SQL 族一律走 AssetFamily 的步骤 id，
            // 不在这里反查方言）。P4-1 起「UI 不得硬编码资产族常量」由
            // uiLayerDoesNotHardcodeAssetFamilyConstants 与
            // AssetFamilyFilterAuditTest 的新族覆盖断言共同守着。
            "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ConnectionWizard.java");

    /**
     * UI（表现层）目录，禁止硬编码资产族常量。
     *
     * <p>资产族的判别逻辑必须全部收敛到 {@code adb-asset} 注册表；UI 只能从
     * {@code AssetFamilyRegistry} / {@code ConnectionCatalog} 取得磁贴与实例委派
     * （如 {@code entry.kind().dialectKind()}），<b>不得</b>直接写
     * {@code AssetKind.MYSQL} 之类常量去做分支 —— 否则加一种连接类型就要回来改 UI，
     * 正是规划里「UI 不得出现资产族分支」要杜绝的。
     */
    private static final String UI_LAYER = "ui/adb-ui-shell/src/main/java";

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
            "DialectKind\\s*\\.\\s*(SQLITE|POSTGRESQL|MYSQL|MARIADB|SQLSERVER|ORACLE)"
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

    /**
     * 升级版承诺：资产族判别逻辑必须收敛到 {@code adb-asset} 注册表，
     * UI 表现层不得硬编码 {@code AssetKind.XXX} 常量去做分支。
     *
     * <p>UI 只允许从注册表取得磁贴与实例委派（如 {@code entry.kind().dialectKind()}、
     * {@code AssetKind.from(session.dialect().kind())}），不得出现
     * {@code AssetKind.MYSQL} / {@code AssetKind.REDIS} 这类常量引用 ——
     * 一旦出现在 UI，加一种连接类型就得回来改界面，违背了「一个族只在注册表登记」的架构。
     */
    @Test
    void uiLayerDoesNotHardcodeAssetFamilyConstants() throws IOException {
        Path uiRoot = repositoryRoot().resolve(UI_LAYER);
        Pattern assetConstant = Pattern.compile(
                "AssetKind\\s*\\.\\s*(REDIS|OBJECT_STORE|SQLITE|POSTGRESQL|MYSQL"
                        + "|MARIADB|SQLSERVER|ORACLE)");
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> paths = Files.walk(uiRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = uiRoot.relativize(path).toString().replace('\\', '/');
                if (!relative.endsWith(".java") || relative.contains("/target/")) {
                    continue;
                }
                scanned++;
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (assetConstant.matcher(source).find()) {
                    violations.add(uiRoot.relativize(path).toString().replace('\\', '/'));
                }
            }
        }

        assertThat(scanned)
                .as("UI 层没扫到任何文件 —— 路径错了，门禁是假绿")
                .isGreaterThan(20);
        assertThat(violations)
                .as("UI 层硬编码了资产族常量 —— 资产族分支漏进了表现层，"
                        + "规划承诺「UI 不得出现资产族分支」破了：\n  %s",
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
