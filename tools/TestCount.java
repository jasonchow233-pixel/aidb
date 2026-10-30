import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统计门禁测试数（4.5-28）。
 *
 * <h2>为什么不能用 surefire XML 的 {@code tests=} 属性</h2>
 * 用 {@code @Nested} 写的测试类，surefire 会把外层类写成 {@code tests="0"}，
 * 真实用例落在<b>内层类</b>的报告条目里。于是按 {@code tests=} 统计会少算一大截
 * （本项目当时是 159，而真实值是 268）。
 *
 * <p>所以这里一律按 {@code <testcase>} 元素计数。
 *
 * <h2>为什么还要区分「跳过」</h2>
 * surefire 跳过一个测试类时，**仍会为每个 {@code @Test} 写一个
 * {@code <testcase><skipped/></testcase>}**。只按 {@code <testcase>} 元素计数，
 * 会把「根本没跑」算成「跑过了」—— 那样一个类被大面积跳过，门禁却还是绿的。
 * 所以这里只把**没带 {@code <skipped/>} 的** {@code <testcase>} 算作已执行，
 * 跳过的单列一行提示，**不计入下限**。
 *
 * <h2>用法</h2>
 * <pre>
 * ./mvnw -B clean verify
 * java tools/TestCount.java          # 打印明细并校验下限
 * java tools/TestCount.java 300      # 自定义下限
 * </pre>
 *
 * <p>下限的作用是**防回归**：一旦有人把统计口径改回 {@code tests=}，
 * 这里会立刻失败，而不是让「少算 100 多条」悄悄发生。
 */
public final class TestCount {

    /** 默认下限。低于这个数说明统计口径坏了，或测试被大面积跳过。 */
    private static final int DEFAULT_MINIMUM = 300;

    private TestCount() {
    }

    /**
     * 这份报告所属的模块还在不在（{@code <module>/target/surefire-reports/}）。
     *
     * <p>{@code clean} 只会清掉**还在的**模块；删掉的模块会把
     * {@code target/} 整个留在磁盘上，于是它上一次的报告会永久地计入总数 ——
     * 数字看着在涨，其实有一部分来自一个已经不存在的模块。
     */
    private static boolean moduleStillExists(Path report) {
        Path module = report.getParent() == null ? null
                : report.getParent().getParent() == null ? null
                : report.getParent().getParent().getParent();
        return module != null && Files.exists(module.resolve("pom.xml"));
    }

    public static void main(String[] args) throws IOException {
        int minimum = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_MINIMUM;
        Path root = findRoot();
        List<Path> reports = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml")
                            && p.getParent() != null
                            && p.getParent().getFileName().toString().equals("surefire-reports")
                            // ⭐ 删掉的模块会把 target/ 留在磁盘上，里面的旧报告会被
                            //    算进总数（实测 declarations 已删，却仍贡献 40 条）。
                            //    模块目录里没有 pom.xml 就是「这个模块不存在了」。
                            && moduleStillExists(p))
                    .forEach(reports::add);
        }
        if (reports.isEmpty()) {
            System.err.println("找不到 surefire 报告，请先跑 ./mvnw -B clean verify");
            System.exit(2);
        }

        Pattern testcase = Pattern.compile("<testcase\\b[^>]*/>|<testcase\\b[^>]*>.*?</testcase>",
                Pattern.DOTALL);
        Pattern skipped = Pattern.compile("<skipped\\b");
        Pattern failure = Pattern.compile("<failure\\b|<error\\b");
        Map<String, Integer> perModule = new LinkedHashMap<>();
        Map<String, Integer> skippedPerModule = new LinkedHashMap<>();
        int total = 0;
        int skippedTotal = 0;
        int problems = 0;

        for (Path report : reports) {
            String xml = Files.readString(report, StandardCharsets.UTF_8);
            Matcher cases = testcase.matcher(xml);
            int count = 0;
            int skippedCount = 0;
            while (cases.find()) {
                // ⚠️ 跳过的用例**不算执行过**。
                //    surefire 跳过一个类时，仍会为每个 @Test 写一个 <testcase><skipped/></testcase>，
                //    只按 <testcase> 元素计数会把「没跑的」算成「跑过的」——
                //    那样一个类被大面积跳过，门禁却还是绿的。
                if (skipped.matcher(cases.group()).find()) {
                    skippedCount++;
                    continue;
                }
                count++;
                Matcher bad = failure.matcher(cases.group());
                if (bad.find()) {
                    problems++;
                }
            }
            skippedTotal += skippedCount;
            total += count;
            String module = moduleOf(root, report);
            perModule.merge(module, count, Integer::sum);
            skippedPerModule.merge(module, skippedCount, Integer::sum);
        }

        System.out.println("── 测试门禁统计 ──────────────────────────────────");
        perModule.forEach((module, count) -> {
            int moduleSkipped = skippedPerModule.getOrDefault(module, 0);
            System.out.printf("%-32s %4d%s%n", module, count,
                    moduleSkipped > 0 ? "  （跳过 " + moduleSkipped + "）" : "");
        });
        System.out.printf("%-32s %4d  （%d 个测试类）%n", "合计", total, reports.size());
        System.out.println("─────────────────────────────────────────────────");
        if (skippedTotal > 0) {
            System.out.println("ℹ️  另有 " + skippedTotal + " 条被跳过（不计入门禁）");
        }

        if (problems > 0) {
            System.err.println("⛔ 有 " + problems + " 条失败 / 错误");
            System.exit(1);
        }
        if (total < minimum) {
            System.err.println("⛔ 测试数 " + total + " 低于下限 " + minimum
                    + "。统计口径可能坏了（surefire 的 tests= 属性不计 @Nested 用例）。");
            System.exit(1);
        }
        System.out.println("✅ 执行 " + total + " 条 ≥ 下限 " + minimum + "，0 失败");
    }

    private static String moduleOf(Path root, Path report) {
        Path relative = root.relativize(report);
        return relative.getName(0) + "/" + relative.getName(1);
    }

    private static Path findRoot() {
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
