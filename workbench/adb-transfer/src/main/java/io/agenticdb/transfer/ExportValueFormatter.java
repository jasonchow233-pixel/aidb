package io.agenticdb.transfer;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;

/**
 * D-86 Formats：把 JDBC 原始值按 {@link ExportOptions} 的六个值格式选项渲染成文本。
 *
 * <p>⭐ <b>默认逐字节等于旧行为</b>：每个分支都以 {@code String.valueOf(value)} 为底，
 * 默认选项下的变换是恒等的 ——
 * <ul>
 *   <li>{@code java.sql.Date} / {@code LocalDate} 的 {@code toString()} 本来就是
 *       {@code yyyy-MM-dd}（YMD / {@code -} / 补零）；{@link #renderDate} 按这三样重组后
 *       拼回同一个串。对 {@code LocalTime.toString()} 省略秒这种细节，
 *       时间分支只替换 {@code ':'}，不强行补秒，所以形态也原样保留。</li>
 *   <li>{@code BigDecimal} / {@code Double} / {@code Float} 默认小数符号是 {@code '.'}，
 *       {@code replace('.', '.')} 什么也不改。</li>
 *   <li>其它（String / 整型 / BigInteger / Boolean / 枚举 / 未知对象）根本不进任何
 *       变换分支，直接 {@code String.valueOf}。</li>
 * </ul>
 * 唯一例外是 {@code byte[]} / {@code Blob}：旧行为输出对象标识 {@code [B@1a2b...}，
 * 本身不可用；默认 HEX 是 D-86 明确要求的新能力，不是「悄悄改了旧输出」。
 *
 * <p>⚠️ 领域层字符串字面量不带中文（I18nAuditTest 的 CJK 棘轮）：异常消息统一英文。
 */
public final class ExportValueFormatter {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private ExportValueFormatter() {
    }

    /**
     * 渲染单个值。返回 {@code null} 当且仅当入参是 {@code null}（由各编码器按
     * 自己的 NULL 规则处理）。可能抛 {@code RuntimeException}（例如某个对象的
     * {@code toString()} 抛错）——{@code continueOnError} 正是在这一层接管。
     */
    public static String formatText(Object value, ExportOptions options) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date || value instanceof LocalDate) {
            return renderDate(String.valueOf(value), options);
        }
        if (value instanceof Time || value instanceof LocalTime) {
            // toString() 的形态（含 LocalTime 省略秒）原样保留，只把 ':' 换成分隔符。
            return String.valueOf(value).replace(":", options.timeDelimiter());
        }
        if (value instanceof BigDecimal || value instanceof Double || value instanceof Float) {
            return String.valueOf(value).replace('.', options.decimalSymbol());
        }
        if (value instanceof byte[] bytes) {
            return renderBinary(bytes, options.binaryEncoding());
        }
        if (value instanceof Blob blob) {
            // 读取失败抛 unchecked，让 continueOnError 决定跳过还是照旧抛出；
            // 不声明 checked SQLException 是因为 exportRows 的既有签名不抛它。
            try {
                return renderBinary(blob.getBytes(1, Math.toIntExact(blob.length())),
                        options.binaryEncoding());
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read BLOB value for export", e);
            }
        }
        return String.valueOf(value);
    }

    /** 以 {@code yyyy-MM-dd} 为输入，按 dateOrder / dateDelimiter / zeroPaddingDate 重组。 */
    private static String renderDate(String iso, ExportOptions options) {
        String[] parts = iso.split("-", -1);
        if (parts.length != 3) {
            // 负年份 / 驱动自定义格式：解析不了就原样返回，保旧行为，不猜。
            return iso;
        }
        String year = parts[0];
        String month = options.zeroPaddingDate() ? parts[1] : stripZeros(parts[1]);
        String day = options.zeroPaddingDate() ? parts[2] : stripZeros(parts[2]);
        String d = options.dateDelimiter();
        return switch (options.dateOrder()) {
            case "DMY" -> day + d + month + d + year;
            case "MDY" -> month + d + day + d + year;
            default -> year + d + month + d + day;   // YMD
        };
    }

    private static String stripZeros(String part) {
        int i = 0;
        while (i < part.length() - 1 && part.charAt(i) == '0') {
            i++;
        }
        return part.substring(i);
    }

    private static String renderBinary(byte[] bytes, String encoding) {
        if ("BASE64".equals(encoding)) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(HEX[(value >> 4) & 0xF]).append(HEX[value & 0xF]);
        }
        return hex.toString();
    }
}
