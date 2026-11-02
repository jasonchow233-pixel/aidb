package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ⭐ D-18：偏好键的组装门禁 —— 「长得写不下的键」不许再把整条功能静默作废。
 *
 * <p>这条是 R45 全量日志里捞出来的，不是设计出来的：崩机处理器打了
 * {@code Key too long: ui.query.colwidth./var/folders/…/demo.db.seven}。
 * {@code java.util.prefs} 对单个键只放行 80 个字符（实测：80 写得进、81 抛），
 * 而列宽记忆的键里拼了**连接名** —— SQLite 连接的显示名就是整条文件路径。
 * 抛在 FX 脉冲/防抖回调里，界面上一条提示都没有：
 * 长名字连接的列宽记忆**看起来像没做过**，实际每次写盘都在抛异常。
 *
 * <p>四条门禁的分工：
 * <ol>
 *   <li>{@link #shortKeysKeepTheirExactShape}：短键形状不变 —— 改法不能把
 *       用户已经存下的偏好全变成另一批键（那等于清空列宽记忆）；</li>
 *   <li>{@link #theKeyThatCrashedInTheR45LogNowRoundTrips}：日志里那条键
 *       真的能写进、读回（守「只截断不落盘」这类假修）；</li>
 *   <li>{@link #theDigestSeparatesTheVariableParts}：摘要稳定且分得开
 *       （守「一律砍到 80 字符」—— 前 80 字符相同的两个列会共用一份宽度）；</li>
 *   <li>{@link #noProductionPrefsCallConcatenatesANameIntoItsKey}：这条洞不再
 *       从别处开回来（守「修了两个调用点，第四个调用点照旧拼字符串」）。</li>
 * </ol>
 */
class SettingsStoreKeyTest {

    /** 日志里那条真出事的键（临时目录路径当连接名 + 列名 seven）。 */
    private static final String LONG_CONNECTION =
            "/var/folders/0l/wy1h1_wx60zbyn7z0m503bzc0000gn/T/adb-tools-test-16281144739327116496/demo.db";

    @Test
    void shortKeysKeepTheirExactShape() {
        assertThat(SettingsStore.key("ui.grid", "test.db", "users", "order"))
                .as("短键必须原样拼 —— 已有的列宽/列序偏好不能因为换组装方法而作废")
                .isEqualTo("ui.grid.test.db.users.order");
        assertThat(SettingsStore.key("ui.query.colwidth", "shop", "id"))
                .isEqualTo("ui.query.colwidth.shop.id");
    }

    @Test
    void theKeyThatCrashedInTheR45LogNowRoundTrips() {
        String key = SettingsStore.key("ui.query.colwidth", LONG_CONNECTION, "seven");

        assertThat(key)
                .as("拼出来的键必须落在 java.util.prefs 的上限内（实测 80 是最后一档）")
                .hasSizeLessThanOrEqualTo(80);
        SettingsStore settings = new SettingsStore();
        assertThatCode(() -> settings.setInt(key, 233))
                .as("写盘不该再把异常留给崩机处理器")
                .doesNotThrowAnyException();
        assertThat(settings.getInt(key, -1))
                .as("写进去的宽度要读得回来 —— 否则这条功能还是没修好")
                .isEqualTo(233);
    }

    @Test
    void theDigestSeparatesTheVariableParts() {
        String id = SettingsStore.key("ui.query.colwidth", LONG_CONNECTION, "id");
        String seven = SettingsStore.key("ui.query.colwidth", LONG_CONNECTION, "seven");
        String otherTable = SettingsStore.key("ui.query.colwidth", LONG_CONNECTION + "2", "seven");

        assertThat(seven)
                .as("同一张表的同一列两次组装必须是同一个键（否则宽度存了读不到）")
                .isEqualTo(SettingsStore.key("ui.query.colwidth", LONG_CONNECTION, "seven"));
        assertThat(id)
                .as("砍前缀是假修：两列前 80 字符完全相同，会共用一份宽度")
                .isNotEqualTo(seven);
        assertThat(otherTable)
                .as("换了连接名也必须是另一份宽度")
                .isNotEqualTo(seven);
    }

    /**
     * 生产代码里，**以字符串字面量开头的偏好调用**不许再把自由文本拼进键里。
     *
     * <p>口径说清楚，免得被当成「又一把尺子」：常量键（{@code "ui.theme"}）、
     * 走 {@link SettingsStore#key} 或 {@code layoutKey(...)} 这类组装函数的调用都不命中，
     * 命中的只有 {@code settings.setInt("ui.x." + name, …)} 这种形状 ——
     * 也就是 D-18 出事的那个形状。
     */
    @Test
    void noProductionPrefsCallConcatenatesANameIntoItsKey() throws IOException {
        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        for (Path file : shellSources()) {
            String source = Files.readString(file);
            var matcher = java.util.regex.Pattern
                    // 第一个实参是字符串字面量，且这一路拼上了变量 → 键里塞了自由文本。
                    .compile("settings\\.(?:get|set|getInt|setInt|getBoolean|setBoolean)\\(\\s*\"[^\"]*\"\\s*\\+")
                    .matcher(source);
            while (matcher.find()) {
                offenders.add(relative(file) + " → " + matcher.group().strip());
            }
            if (source.contains("settings.")) {
                inspected++;
            }
        }
        assertThat(inspected)
                .as("没有扫到任何用偏好存储的界面源码 —— 这条门禁在空转，假绿")
                .isGreaterThan(3);
        assertThat(offenders)
                .as("这些偏好键直接拼了自由文本：名字一长就撞上 java.util.prefs 的 80 字符上限，"
                        + "写盘抛在脉冲回调里，功能静默失效（D-18）。换成 SettingsStore.key(前缀, 部分…)：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    private static List<Path> shellSources() throws IOException {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".java") && relative.contains("src/main/java")
                        && relative.startsWith("ui/")) {
                    files.add(path);
                }
            }
        }
        assertThat(files)
                .as("没有扫到 UI 主源码 —— 路径过滤失效，这条门禁是假绿")
                .hasSizeGreaterThan(30);
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

    private static String relative(Path file) {
        return repositoryRoot().relativize(file).toString().replace('\\', '/');
    }
}
