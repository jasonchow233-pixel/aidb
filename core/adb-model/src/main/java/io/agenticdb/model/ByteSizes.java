package io.agenticdb.model;

import java.util.Locale;

/**
 * 字节数 → 人扫得动的文本：**B / KB / MB / GB 四档，一位小数，1024 进制。**
 *
 * <p>以前这个格式化有两份拷贝（{@code TableMeta.sizeText} 三档、
 * {@code CellRenderer.sizeLabel} 四档），各改各的：
 * <ul>
 *   <li>KB 档直接拼 double —— 1365 字节显示成 {@code 1.3330078125 KB}；</li>
 *   <li>三档那份没有 GB —— 1.5 GB 的表显示成 {@code 1536.0 MB}。</li>
 * </ul>
 * 合到一处，两处调用点共用同一份语义。
 *
 * <p>两个刻意的取舍：
 * <ol>
 *   <li><b>一律 {@link Locale#ROOT}</b>：{@code String.format} 默认用本机
 *       Locale，在小数分隔符是逗号的地区会输出 {@code 1,5 KB}；</li>
 *   <li><b>字节档不补小数</b>：{@code 512 B} 而不是 {@code 512.0 B} ——
 *       整数个字节不该假装是测量出来的。</li>
 * </ol>
 */
public final class ByteSizes {

    private static final long STEP = 1024L;
    private static final String[] UNITS = {"B", "KB", "MB", "GB"};
    /** 负值不是「负的大小」，是这数据来源不可信 —— 如实说不知道。 */
    private static final String UNKNOWN = "—";

    private ByteSizes() {
    }

    /**
     * 格式化字节数。
     *
     * @param bytes 字节数；负数表示「这个来源没给出可信值」
     * @return 形如 {@code 512 B} / {@code 1.5 KB} / {@code 2.0 GB}；负数返回 {@code —}
     */
    public static String format(long bytes) {
        if (bytes < 0) {
            return UNKNOWN;
        }
        if (bytes < STEP) {
            return bytes + " B";
        }
        int unit = 1;
        double scaled = bytes;
        // 先降到 KB 档（scaled ∈ [1024, 1048576) 时再降一档）
        while (unit < UNITS.length - 1 && scaled >= STEP * STEP) {
            scaled /= STEP;
            unit++;
        }
        // 现在 scaled ∈ [1024, 1048576)，再除一次落到 [1, 1024)
        scaled /= STEP;
        if (Math.round(scaled * 10) >= STEP * 10 && unit < UNITS.length - 1) {
            // 1048575 B → 1023.999 KB → 保留一位小数就是 1024.0 KB。
            // 档位没有 1024 这一说，进一档讲 1.0 MB 才不荒谬。
            unit++;
            scaled /= STEP;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, UNITS[unit]);
    }
}
