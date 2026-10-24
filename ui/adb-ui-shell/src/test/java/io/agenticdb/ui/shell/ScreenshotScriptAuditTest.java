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
 * ⭐ 第十八轮门禁：**验收截图脚本里的点击目标必须真的存在**。
 *
 * <h2>为什么</h2>
 * {@code ScreenshotSession.click()} 找不到节点时只往 stderr 打一行
 * {@code 找不到节点 #xxx，跳过点击}，然后<b>照常截图</b> —— 于是一张名叫
 * {@code p5-connections.png} 的图，拍到的其实是对象首页。
 * 脚本返回码是 0、字节数也正常，肉眼不核对就发现不了。
 *
 * <p>实测踩过：U-B7 侧栏重构（{@code a353dc1}）删掉了 {@code sidebar-connections}
 * 这颗按钮，{@code scripts/shots.sh} 里的引用没跟着删，那张图连续拍错。
 *
 * <h2>规则</h2>
 * {@code scripts/shots.sh} 每一条 {@code 名称|点击id|等待|环境变量} 里的
 * 点击 id，都必须是 UI 源码里 {@code setId("...")} 出现过的节点。
 * 多个 id 用逗号分隔（{@link ScreenshotSession} 支持），空串表示不点击。
 */
class ScreenshotScriptAuditTest {

    private static final Pattern SHOT_ROW = Pattern.compile(
            "^\\s*\"([a-z0-9-]+)\\|([^|]*)\\|", Pattern.MULTILINE);

    @Test
    void everyClickTargetInShotScriptExistsInTheUiSource() throws IOException {
        Path root = repositoryRoot();
        Path script = root.resolve("scripts/shots.sh");
        assertThat(Files.isRegularFile(script)).as("找不到 %s", script).isTrue();

        List<String> ids = new ArrayList<>();
        Matcher matcher = SHOT_ROW.matcher(Files.readString(script, StandardCharsets.UTF_8));
        while (matcher.find()) {
            String targets = matcher.group(2).trim();
            if (targets.isEmpty()) {
                continue;
            }
            for (String one : targets.split(",")) {
                if (!one.isBlank()) {
                    ids.add(one.trim());
                }
            }
        }

        assertThat(ids)
                .as("shots.sh 至少要有一条带点击目标的截图（否则这份门禁没意义）")
                .isNotEmpty();

        String source = readUiSources(root);
        List<String> missing = ids.stream()
                .filter(id -> !source.contains("setId(\"" + id + "\")"))
                .distinct()
                .toList();

        assertThat(missing)
                .as("shots.sh 引用的节点在 UI 源码里不存在 —— 这些截图会拍到错误的页面"
                        + "（ScreenshotSession 找不到节点时只打一行 stderr 就继续截图）")
                .isEmpty();
    }

    /**
     * ⭐ UX-0：截图必须钉住主题、密度、连接与偏好根。
     *
     * <p>少钉任何一个，同一份脚本都会拍出不同画面（实拍踩过：
     * {@code p2-users} 行高 34 而不是默认 28，因为真实偏好里留着 {@code comfort}）。
     */
    @Test
    void shotsScriptPinsThemeDensityConnectionAndPrefs() throws IOException {
        Path script = repositoryRoot().resolve("scripts/shots.sh");
        assertThat(Files.isRegularFile(script)).as("找不到 %s", script).isTrue();
        String text = Files.readString(script, StandardCharsets.UTF_8);

        assertThat(text)
                .as("截图必须用 ADB_DENSITY 钉住密度")
                .contains("export ADB_DENSITY=");
        assertThat(text)
                .as("截图必须使用独立偏好根，并在每次启动时清空")
                .contains("export ADB_PREFS_NODE=")
                .contains("export ADB_PREFS_RESET=");
        assertThat(text)
                .as("截图必须显式指定连接，不能依赖「上次用的连接」")
                .contains("export ADB_CONNECTION=");
        assertThat(text)
                .as("主题仍必须用 ADB_THEME_FORCE 钉住")
                .contains("ADB_THEME_FORCE=");
    }

    /**
     * ⭐ UX-5-F：交互态必须有一帧真的被拍到，而且这一帧的开关与点击都还在。
     *
     * <p>选中行、光标环、抽屉编辑器只在点下去时出现，以前的 12 帧一帧没覆盖 ——
     * 这三样改了三版，画面没人看过（那颗灰星就是这么漏的：门禁全绿不等于用户看得见）。
     * 现在由 {@code p2-cell} 负责：状态靠 {@code ADB_CELL} 摆，编辑态靠真点「编辑」按钮进入。
     */
    @Test
    void interactionStatesHaveTheirOwnFrame() throws IOException {
        List<Row> rows = shotRows();
        Row cell = rows.stream().filter(row -> row.envVars().contains("ADB_CELL")).findFirst()
                .orElse(null);
        assertThat(cell)
                .as("数据页要有一帧摆出「选中行 + 光标格 + 抽屉编辑器」三个交互态")
                .isNotNull();
        assertThat(cell.clickTargets())
                .as("这一帧要点抽屉那颗「编辑」，否则编辑器（应用 / 取消 + 可写文本区）不会出现")
                .isEqualTo("data-details-edit");
    }

    /**
     * ⭐ UX-5-F：脚本里用到的每一个 {@code ADB_*} 开关，主源码必须真的读它。
     *
     * <p>和第一列门禁同一类病：{@code ADB_CELL} 处理器一旦被删，这一帧会照常拍出一张
     * 平平无奇的表、返回码照样是 0 —— <b>开关写了没人读</b>是最难发现的静默降级。
     */
    @Test
    void everyEnvSwitchUsedByTheScriptIsActuallyReadByTheUi() throws IOException {
        String source = readUiSources(repositoryRoot());
        List<String> dangling = new ArrayList<>();
        for (Row row : shotRows()) {
            for (String name : ENV_NAME.matcher(row.envVars()).results()
                    .map(match -> match.group()).distinct().toList()) {
                if (!source.contains("\"" + name + "\"")) {
                    dangling.add(row.name() + " → " + name);
                }
            }
        }
        assertThat(dangling)
                .as("这些截图开关在 shots.sh 里被设了，却没有任何主源码读它 —— "
                        + "对应的那一帧会静默拍成默认界面")
                .isEmpty();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 一条截图帧：名称 / 点击目标 / 等待 / 该帧预置的环境变量。 */
    private record Row(String name, String clickTargets, String envVars) {
    }

    private static final Pattern SHOT_TUPLE = Pattern.compile(
            "^\\s*\"([a-z0-9-]+)\\|([^|]*)\\|[^|]*\\|([^\"]*)\"", Pattern.MULTILINE);

    private static final Pattern ENV_NAME = Pattern.compile("ADB_[A-Z0-9_]+");

    private static List<Row> shotRows() throws IOException {
        Path script = repositoryRoot().resolve("scripts/shots.sh");
        assertThat(Files.isRegularFile(script)).as("找不到 %s", script).isTrue();
        List<Row> rows = new ArrayList<>();
        Matcher matcher = SHOT_TUPLE.matcher(Files.readString(script, StandardCharsets.UTF_8));
        while (matcher.find()) {
            rows.add(new Row(matcher.group(1), matcher.group(2).trim(), matcher.group(3).trim()));
        }
        assertThat(rows).as("shots.sh 解析出来的截图帧（解析不到就是格式变了）").isNotEmpty();
        return rows;
    }

    /** 拼接全部 UI 主源码，用来查 {@code setId("...")}。 */
    private static String readUiSources(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                // Windows 用 `\`：不归一化会一个文件都不扫，门禁静默变绿。
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".java") && relative.contains("src/main/java")) {
                    all.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return all.toString();
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
        throw new IllegalStateException("从 " + System.getProperty("user.dir")
                + " 往上找不到仓库根（需要 pom.xml + core/）");
    }
}
