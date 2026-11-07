package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
     * ⭐ R57 画面债：摆态**拍不成**时必须走弃帧出口，而不是打一行日志然后照常拍。
     *
     * <p>与 D-37 / D-39 同一族病：候选没读回来、或选区没找到那句时，只要快门还在，
     * 脚本就会产出一张尺寸正常、字节数正常、「看着像那一格」的图，返回码 0 ——
     * 而这一格恰恰因为它没有画面证据才欠了这么多轮。所以这里的放弃必须是
     * {@code ScreenshotSession.abortMatrixFrame}（不写文件 + 退出码 3）。
     */
    @Test
    void shotPosesGiveUpByAbortingNotByShooting() throws IOException {
        Path root = repositoryRoot();
        String page = Files.readString(root.resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/QueryPage.java"),
                StandardCharsets.UTF_8);
        String shell = Files.readString(root.resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/AppShell.java"),
                StandardCharsets.UTF_8);

        assertThat(methodBody(page, "private void awaitCompletionPose"))
                .as("补全帧读不到候选时必须弃帧，不能拍一张没有弹层的查询页")
                .contains("ScreenshotSession.abortMatrixFrame");
        assertThat(methodBody(page, "void poseSelectionForShots"))
                .as("选区帧找不到那句时必须弃帧，不能拍一张圈着别的东西的选区")
                .contains("ScreenshotSession.abortMatrixFrame");
        assertThat(methodBody(shell, "private void poseQueryCompletionsWhenReady"))
                .as("补全帧的连接没开成 / 页没建起来，两条都要弃帧")
                .contains("ScreenshotSession.abortMatrixFrame");
        assertThat(methodBody(shell, "private void poseQuerySelectionWhenReady"))
                .as("选区帧的连接没开成 / 页没建起来，两条都要弃帧")
                .contains("ScreenshotSession.abortMatrixFrame");
        // ⭐ D-96 新加的摆态：页签那一格的两个失败分支都要弃帧 —— 只写一条等于另一条可以静默拍。
        String structurePose = methodBody(shell, "private void poseStructureTabWhenReady");
        assertThat(structurePose)
                .as("结构页那一帧：页没建出来要弃帧，不能拍一张停在 Fields 却声称 Indexes 的图")
                .contains("never got built");
        assertThat(structurePose)
                .as("结构页那一帧：id 不在页签清单里也要弃帧 —— 这正是 D-96 拆掉的静默 no-op")
                .contains("no structure tab with id");

        // ⭐ 编辑器「还没挂上窗口」不等于「摆不成」：页刚建好那一刻编辑器常常还不在场景图上，
        //   实测就写过一版「直接 return」，于是一张没有弹层的查询页安静地顶替了证据帧。
        String await = methodBody(page, "private void awaitCompletionPose");
        int firstRetry = await.indexOf("schedule(");
        int firstEvaluate = await.indexOf("completions.evaluateForTest");
        assertThat(firstRetry)
                .as("没挂上窗口的那一跳要接着等，不能原样退出")
                .isGreaterThan(0);
        assertThat(firstRetry)
                .as("「继续等」必须排在第一次求值之前 —— 没挂载就摸弹层会抛异常")
                .isLessThan(firstEvaluate);
    }

    /** 取一个方法的正文（按大括号配对数到方法结束）；找不到方法名就是空串。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
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

    /**
     * ⭐ R31：设计器的<b>摆态值</b>必须真的被 {@code poseForShots} 认得。
     *
     * <p>和「开关写了没人读」同一类病：pose 名打错一个字母，那一帧照常拍出默认页、
     * 返回码照样是 0 —— 而这几帧是 G15~G18 唯一的画面证据。
     */
    @Test
    void everyDesignerPoseUsedByTheScriptIsHandled() throws IOException {
        Path root = repositoryRoot();
        String script = Files.readString(root.resolve("scripts/shots.sh"), StandardCharsets.UTF_8);
        String dialog = Files.readString(root.resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/NewTableDialog.java"),
                StandardCharsets.UTF_8);

        List<String> used = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        Matcher matcher = Pattern.compile("ADB_NEW_TABLE_POSE=([A-Za-z0-9_-]+)").matcher(script);
        while (matcher.find()) {
            String pose = matcher.group(1);
            if (!used.contains(pose)) {
                used.add(pose);
                if (!dialog.contains("case \"" + pose + "\"")) {
                    dangling.add(pose);
                }
            }
        }

        assertThat(used)
                .as("shots.sh 至少要有一帧摆态截图（否则这份门禁没意义）")
                .isNotEmpty();
        assertThat(dangling)
                .as("这些摆态在 shots.sh 里被用了，poseForShots 却没有对应分支 —— "
                        + "那一帧会静默拍成默认的字段页")
                .isEmpty();
    }

    /**
     * ⭐ R33：导入 / 导出 / 连接向导的<b>摆态值</b>必须真的被 {@code poseForShots} 认得。
     *
     * <p>与设计器那条同型：pose 打错一个字母，那一帧照常拍出默认屏、返回码照样是 0，
     * 而这几帧是 G5 / G6 / G8 / G20 唯一的画面证据。
     */
    @Test
    void everyWizardPoseUsedByTheScriptIsHandled() throws IOException {
        Path root = repositoryRoot();
        String script = Files.readString(root.resolve("scripts/shots.sh"), StandardCharsets.UTF_8);

        assertThat(assertPosesHandled(script, "ADB_CONNECT_WIZARD",
                root.resolve("ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ConnectionWizard.java")))
                .as("连接向导帧的摆态名必须在 poseForShots 里有对应分支")
                .isEmpty();
        assertThat(assertPosesHandled(script, "ADB_EXPORT",
                root.resolve("ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ExportWizard.java")))
                .as("导出向导帧的格式摆态必须在 poseForShots 里有对应分支")
                .isEmpty();
    }

    /** 脚本里 {@code SWITCH=value} 的每个 value，源码里都要有 {@code case "value"}。 */
    private static List<String> assertPosesHandled(String script, String switchName, Path source)
            throws IOException {
        String code = Files.readString(source, StandardCharsets.UTF_8);
        List<String> used = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        Matcher matcher = Pattern.compile(switchName + "=([A-Za-z0-9_-]+)").matcher(script);
        while (matcher.find()) {
            String pose = matcher.group(1);
            if (pose.equals("1") || pose.equalsIgnoreCase("true")) {
                continue;
            }
            if (!used.contains(pose)) {
                used.add(pose);
                if (!code.contains("case \"" + pose + "\"")) {
                    dangling.add(pose);
                }
            }
        }
        assertThat(used).as("shots.sh 至少要用一帧 %s 摆态（否则这份门禁没意义）", switchName)
                .isNotEmpty();
        return dangling;
    }

    /**
     * ⭐ R47-b：库选择器两帧的摆态值必须真的被外壳认得。
     *
     * <p>与「开关写了没人读」同型病：pose 名打错一个字母，那一帧照样拍出默认查询页、
     * 返回码照样是 0 —— 而这两张是「库▾」唯一的<b>画面</b>证据（它交付时一张帧都没有，
     * 恰恰因为过去只有行为门禁、没有画面门禁）。
     */
    @Test
    void everyCatalogPoseUsedByTheScriptIsHandled() throws IOException {
        Path root = repositoryRoot();
        String script = Files.readString(root.resolve("scripts/shots.sh"), StandardCharsets.UTF_8);

        assertThat(assertPosesHandled(script, "ADB_CATALOG_POSE",
                root.resolve("ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/AppShell.java")))
                .as("库▾ 帧的摆态名必须在 AppShell 的 pose 分派里有对应分支")
                .isEmpty();
    }

    /**
     * ⭐ R57 画面债：补全弹层 / 选区执行两帧的摆态名必须真的被外壳认得。
     *
     * <p>与「库▾」那条同型病：pose 打错一个字母，那一帧照样拍出默认查询页、返回码照样是 0 ——
     * 而这两张是 R57-a / R57-b 唯一的<b>画面</b>证据（交付时一帧都没有，正是「只有门禁、
     * 没人看过画面」这笔欠账本身）。
     */
    @Test
    void everyQueryPoseUsedByTheScriptIsHandled() throws IOException {
        Path root = repositoryRoot();
        String script = Files.readString(root.resolve("scripts/shots.sh"), StandardCharsets.UTF_8);
        Path shell = root.resolve("ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/AppShell.java");

        assertThat(assertPosesHandled(script, "ADB_COMPLETION_POSE", shell))
                .as("补全弹层帧的摆态名必须在 AppShell 的 pose 分派里有对应分支")
                .isEmpty();
        assertThat(assertPosesHandled(script, "ADB_SELECTION_POSE", shell))
                .as("选区执行帧的摆态名必须在 AppShell 的 pose 分派里有对应分支")
                .isEmpty();
        // ⭐ R58-d：结果区回写三态（blocked / dirty / preview）同型病 —— pose 打错一个字母，
        //   那一帧照样拍出默认查询页、返回码照样是 0，而这三张是 R58 唯一的画面证据。
        assertThat(assertPosesHandled(script, "ADB_RESULT_EDIT_POSE", shell))
                .as("结果区回写帧的摆态名必须在 AppShell 的 pose 分派里有对应分支")
                .isEmpty();
    }

    /**
     * ⭐ R47-b：真库帧的两条红线 —— <b>不静默冒充</b>、<b>不带口令</b>。
     *
     * <p>① 环境没配时必须**跳过并说明**：否则 {@code ADB_CONNECTION=matrix-mysql} 找不到档案，
     * 那一格会退回演示库（SQLite，没有库一层）拍出一张「看着像真库」的图 ——
     * 那正是这颗控件欠帧的原因，不能用一张假帧把它填掉。
     * ② 脚本里不许出现任何 {@code ADB_TEST_*_PASSWORD=<字面量>}：口令只从环境读，
     * 读也只读 {@code ${VAR:-}}，一次都不写进脚本、帧名或提交。
     */
    @Test
    void matrixFramesAreGatedAndCarryNoPasswords() throws IOException {
        Path root = repositoryRoot();
        String script = Files.readString(root.resolve("scripts/shots.sh"), StandardCharsets.UTF_8);

        assertThat(script).as("矩阵行必须存在（库▾ 与 MySQL 单句式视图都靠它）")
                .contains("ADB_MATRIX=mysql");
        assertThat(script).as("没配矩阵环境时必须跳过真库帧，而不是退回演示库拍一张")
                .contains("*ADB_MATRIX=*)")
                // ⭐ D-37：查的是**那一家自己**的口令，不是永远存在的 MySQL 那把 ——
                //   原来写死 ADB_TEST_MYSQL_PASSWORD，等于给 postgresql / oracle 那几格
                //   开了张永远成立的条件：只要 MySQL 起得来，缺环境也照样往下拍。
                .contains("ADB_TEST_${matrix_prefix}_PASSWORD:-}")
                .contains("eval \"matrix_password=\\${ADB_TEST_${matrix_prefix}_PASSWORD:-}\"");
        assertThat(Pattern.compile("ADB_TEST_[A-Z]+_PASSWORD=[^\"]")
                .matcher(script).find())
                .as("脚本里出现了 ADB_TEST_*_PASSWORD=<字面量> —— 口令只能从环境读，不能落进脚本")
                .isFalse();
        // 档案隔离：截图不得改写用户真实的 connections.json（R47-b 之前一直在改写）。
        assertThat(script).as("截图运行必须把连接档案指到临时文件")
                .contains("export ADB_CONNECTIONS_FILE=");
    }

    /**
     * ⭐ R47-c · D-37：矩阵行的<b>身份</b>必须自洽 —— 取值是真方言 id，且连的就是那一台。
     *
     * <p>为什么单独钉一条：{@code ADB_MATRIX} 的取值写错（{@code postgres} 少了 ql）、
     * 或 {@code ADB_CONNECTION} 与它配错（{@code matrix-mysq}），过去都会<b>静默退回演示库</b>
     * 把帧拍完、返回码 0 —— 于是一张 SQLite 的画面顶着「postgresql 真库帧」的名字进仓库，
     * 而它恰恰是这颗控件当初欠帧的原因（演示库没有库一层）。
     * 现在外壳遇到这两种情况直接放弃这一帧（{@code ScreenshotSession.abortMatrixFrame}），
     * 脚本也在起跑前按<b>那一家自己的</b>口令门控；这条门禁钉的是这两处不再靠人记得。
     */
    @Test
    void matrixRowsUseRealDialectIdsAndConnectToThatEngine() throws IOException {
        Set<String> dialectIds = Arrays.stream(io.agenticdb.jdbc.DialectKind.values())
                .map(io.agenticdb.jdbc.DialectKind::id)
                .collect(Collectors.toSet());

        List<String> unknown = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (Row row : shotRows()) {
            String matrix = envValue(row.envVars(), "ADB_MATRIX");
            if (matrix.isEmpty()) {
                continue;
            }
            if (!dialectIds.contains(matrix)) {
                unknown.add(row.name() + " → ADB_MATRIX=" + matrix);
            }
            String connection = envValue(row.envVars(), "ADB_CONNECTION");
            if (!("matrix-" + matrix).equals(connection)) {
                mismatched.add(row.name() + " → ADB_MATRIX=" + matrix
                        + " 但 ADB_CONNECTION=" + connection);
            }
        }
        assertThat(unknown).as("ADB_MATRIX 的取值必须是 DialectKind 的方言 id").isEmpty();
        assertThat(mismatched).as("矩阵帧必须连回自己那一台（否则拍的是演示库）").isEmpty();
    }

    /** 从一行帧的预置环境变量里取 {@code KEY=value} 的值；没有这个键就是空串。 */
    private static String envValue(String envVars, String key) {
        Matcher matcher = Pattern.compile("(?<=^| )" + key + "=([^ ]*)").matcher(envVars);
        return matcher.find() ? matcher.group(1) : "";
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
