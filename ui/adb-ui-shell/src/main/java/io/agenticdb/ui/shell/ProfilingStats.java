package io.agenticdb.ui.shell;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ⭐ Data Profiling 的**统计内核**（纯函数，不碰 JavaFX、不碰 JDBC）。
 *
 * <p>为什么把它从 {@link DataProfilingPage} 里抽出来：画像页真正会算错的地方不是
 * 「按钮点下去有没有反应」，而是<b>数字算得对不对</b> —— null 率、去重数、
 * 数值列的最小/最大/均值、样例的取法。这些错一次不会报错，只会给用户一个
 * 看起来很专业的错数字，比报错更难发现。
 *
 * <p>所以抽成纯函数：一列一行地喂进去，拿到一列一行地出来，测试不用起场景图、
 * 不用连库、不会因为 UI 改动而失效。
 *
 * <p>口径与页面一致，**不美化**：
 * <ul>
 *   <li>取值一律按<b>字符串</b>读（JDBC 的 {@code getString}），所以「007」这类值
 *       在去重与样例里保留原样，只有数值聚合才尝试解析；</li>
 *   <li>数值列里混进了非数（空串、���文字）→ <b>该列的聚合标记为不可用</b>，
 *       输出「—」，而不是悄悄把那一行当成 0 拉低均值；</li>
 *   <li>全为 NULL 的列：null 率 100%，去重 0，均值「—」；</li>
 *   <li>样例最多 5 个，按**首次出现顺序**（不是排序）；</li>
 *   <li>采样上限 200 行是页面决定的（一条 {@code SELECT * LIMIT 200}），
 *       页面文案如实写「基于样本」。</li>
 * </ul>
 */
final class ProfilingStats {

    /** 一列的统计结果。 */
    record Column(String name,
                  String typeName,
                  long total,
                  long nulls,
                  double nullPercent,
                  long distinct,
                  Double min,
                  Double max,
                  Double average,
                  List<String> samples) {

        /** 数值列的聚合是否可用（非数值列 / 全 NULL 列 / 混进非数 → 不可用）。 */
        boolean hasNumericSummary() {
            return average != null;
        }
    }

    /** 采样行数上限 —— 与页面那条 {@code LIMIT 200} 一致。 */
    static final int SAMPLE_LIMIT = 200;

    /** 样例最多保留几个。 */
    static final int SAMPLE_COUNT = 5;

    private ProfilingStats() {
    }

    /**
     * 按列统计一批行。
     *
     * @param headers    列名（顺序即列顺序）
     * @param typeNames  列类型名（JDBC 的 {@code getColumnTypeName}）
     * @param numeric    每列是否按数值列聚合（长度与 {@code headers} 一致）
     * @param rows       行数据，每行与 {@code headers} 等长；元素可为 {@code null}
     * @return 每列一个 {@link Column}，顺序与 {@code headers} 一致
     */
    static List<Column> summarize(List<String> headers,
                                  List<String> typeNames,
                                  boolean[] numeric,
                                  List<List<String>> rows) {
        int columns = headers.size();
        long[] total = new long[columns];
        long[] nulls = new long[columns];
        @SuppressWarnings("unchecked")
        Set<String>[] distinct = new Set[columns];
        @SuppressWarnings("unchecked")
        Set<String>[] samples = new Set[columns];
        Double[] min = new Double[columns];
        Double[] max = new Double[columns];
        double[] sum = new double[columns];
        boolean[] tainted = new boolean[columns];

        for (int c = 0; c < columns; c++) {
            distinct[c] = new LinkedHashSet<>();
            samples[c] = new LinkedHashSet<>();
        }

        for (List<String> row : rows) {
            for (int c = 0; c < columns; c++) {
                String raw = c < row.size() ? row.get(c) : null;
                total[c]++;
                if (raw == null) {
                    nulls[c]++;
                    continue;
                }
                distinct[c].add(raw);
                if (samples[c].size() < SAMPLE_COUNT) {
                    samples[c].add(raw);
                }
                if (numeric[c]) {
                    try {
                        double v = Double.parseDouble(raw.trim());
                        sum[c] += v;
                        if (min[c] == null || v < min[c]) {
                            min[c] = v;
                        }
                        if (max[c] == null || v > max[c]) {
                            max[c] = v;
                        }
                    } catch (NumberFormatException e) {
                        tainted[c] = true;
                    }
                }
            }
        }

        List<Column> result = new ArrayList<>(columns);
        for (int c = 0; c < columns; c++) {
            double nullPercent = total[c] == 0 ? 0 : (nulls[c] * 100.0) / total[c];
            boolean usable = numeric[c] && !tainted[c] && total[c] > nulls[c];
            Double average = usable ? sum[c] / (total[c] - nulls[c]) : null;
            result.add(new Column(
                    headers.get(c),
                    c < typeNames.size() ? typeNames.get(c) : "",
                    total[c],
                    nulls[c],
                    nullPercent,
                    distinct[c].size(),
                    usable ? min[c] : null,
                    usable ? max[c] : null,
                    average,
                    List.copyOf(samples[c])));
        }
        return result;
    }

    /** 页面表格里的单元格文本：不可用的聚合统一显示「—」，不显示 {@code null}。 */
    static String cell(Double value) {
        return value == null ? "—" : String.valueOf(value);
    }

    /** null 率的展示文本（一位小数 + 百分号）。 */
    static String nullPercentText(double percent) {
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }
}
