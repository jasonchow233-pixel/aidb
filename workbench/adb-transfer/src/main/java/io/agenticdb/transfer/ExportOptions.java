package io.agenticdb.transfer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 导出参数。
 *
 * <p>⭐ D-86：补齐 Navicat 参照帧里的导出选项（append / continueOnError /
 * recordDelimiter / textQualifier 与一组 Formats 值格式）。这些字段不是「存着好看」的，
 * {@link TableExporter} 的七种写出路径逐条落实或写明忽略理由，见那里的注释。
 *
 * <p>⚠️ 兼容口径：6 / 7 / 8 参构造器保持原语义（新字段一律取默认值），
 * 所有 {@code withXxx} 都是「复制全部 18 个组件再覆盖一个」——任何一个 with 漏带
 * 新字段，都会让链式调用悄悄退回默认，正是 P-3 盐值当初踩过的坑（见
 * {@code MaskedExportTest#withHelpersNeverDropTheRules} 同族门禁）。
 *
 * @param format            格式
 * @param includeHeader     CSV / TSV 是否写表头行
 * @param batchSize         每多少行提交一次（导出时是写出的分组大小）
 * @param maxRows           最多导出多少行；{@code <= 0} 表示不限
 * @param quoteAll          CSV / TSV 是否给<b>所有</b>字段加文本限定符。与
 *                          {@link #textQualifier} 是两件事：本开关决定「要不要加」，
 *                          textQualifier 决定「用哪个字符加」。
 *                          默认关：Navicat 也只在字段里出现分隔符 / 引号 / 换行时才加引号。
 * @param charset           文本格式的写出编码（xlsx 是二进制，忽略）
 * @param masks             ⭐ P-3 列级脱敏规则；空列表 = 原样导出
 * @param maskSalt          ⭐ P-3 加固：HASH 模式的导出级盐值，不写进导出文件
 * @param append            是否追加到已有目标文件（不清空、不重复写表头）。UI 只在
 *                          「行式文本」格式下放开；XML / HTML / xlsx 追加会产出坏文档。
 * @param continueOnError   单行 / 单值渲染抛异常时跳过该行并累计错误数（在
 *                          {@link ExportResult} 里回报）；关闭时异常照旧抛出。
 * @param recordDelimiter   文本格式的记录结尾：CSV / TSV 默认 CRLF，其余默认 LF；
 *                          显式值（包括 LF）原样保留。
 * @param textQualifier     CSV / TSV 的引用字符，默认 {@code '"'}。转义规则随它变化：
 *                          字段内出现的该字符写成两个。
 * @param dateOrder         java.sql.Date / LocalDate 的年月日顺序：YMD / DMY / MDY
 * @param dateDelimiter     日期各部分之间的分隔符，默认 {@code "-"}
 * @param zeroPaddingDate   月 / 日是否补前导零，默认 {@code true}
 * @param timeDelimiter     java.sql.Time / LocalTime 的时分秒分隔符，默认 {@code ":"}
 * @param decimalSymbol     BigDecimal / Double / Float 的小数符号，默认 {@code '.'}
 * @param binaryEncoding    byte[] / Blob 的编码：HEX（默认）或 BASE64
 */
public record ExportOptions(
        ExportFormat format,
        boolean includeHeader,
        int batchSize,
        long maxRows,
        boolean quoteAll,
        Charset charset,
        List<MaskRule> masks,
        String maskSalt,
        boolean append,
        boolean continueOnError,
        String recordDelimiter,
        char textQualifier,
        String dateOrder,
        String dateDelimiter,
        boolean zeroPaddingDate,
        String timeDelimiter,
        char decimalSymbol,
        String binaryEncoding) {

    /** 默认值集中在这里：兼容构造器、UI 初始选中值、门禁断言共用一份，不许三处各写。 */
    static final String DEFAULT_RECORD_DELIMITER = "\n";
    static final char DEFAULT_TEXT_QUALIFIER = '"';
    static final String DEFAULT_DATE_ORDER = "YMD";
    static final String DEFAULT_DATE_DELIMITER = "-";
    static final boolean DEFAULT_ZERO_PADDING_DATE = true;
    static final String DEFAULT_TIME_DELIMITER = ":";
    static final char DEFAULT_DECIMAL_SYMBOL = '.';
    static final String DEFAULT_BINARY_ENCODING = "HEX";

    public ExportOptions {
        format = format == null ? ExportFormat.CSV : format;
        batchSize = batchSize <= 0 ? 500 : batchSize;
        charset = charset == null ? StandardCharsets.UTF_8 : charset;
        masks = masks == null ? List.of() : List.copyOf(masks);
        maskSalt = maskSalt == null ? "" : maskSalt;
        recordDelimiter = normalizeRecordDelimiter(recordDelimiter, format);
        textQualifier = normalizeTextQualifier(textQualifier);
        dateOrder = normalizeDateOrder(dateOrder);
        dateDelimiter = dateDelimiter == null ? DEFAULT_DATE_DELIMITER : dateDelimiter;
        timeDelimiter = timeDelimiter == null ? DEFAULT_TIME_DELIMITER : timeDelimiter;
        decimalSymbol = normalizeDecimalSymbol(decimalSymbol);
        binaryEncoding = normalizeBinaryEncoding(binaryEncoding);
    }

    private static String normalizeRecordDelimiter(String value, ExportFormat format) {
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return format == ExportFormat.CSV || format == ExportFormat.TSV
                ? "\r\n" : DEFAULT_RECORD_DELIMITER;
    }

    private static char normalizeTextQualifier(char value) {
        // NUL / 控制字符 / 换行不能当引用字符：会写出解析不出来的字段。
        return value == '\0' || value == '\n' || value == '\r' || Character.isISOControl(value)
                ? DEFAULT_TEXT_QUALIFIER : value;
    }

    private static String normalizeDateOrder(String value) {
        if (value == null) {
            return DEFAULT_DATE_ORDER;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "YMD", "DMY", "MDY" -> upper;
            default -> DEFAULT_DATE_ORDER;
        };
    }

    private static char normalizeDecimalSymbol(char value) {
        // 数字 / 字母 / 控制字符当小数点会写出非法数字字面量。
        return value == '\0' || Character.isLetterOrDigit(value) || Character.isISOControl(value)
                ? DEFAULT_DECIMAL_SYMBOL : value;
    }

    private static String normalizeBinaryEncoding(String value) {
        if (value == null) {
            return DEFAULT_BINARY_ENCODING;
        }
        return "BASE64".equalsIgnoreCase(value) ? "BASE64" : DEFAULT_BINARY_ENCODING;
    }

    /**
     * ⭐ 8 参兼容构造器：D-86 之前的 canonical 形状。新字段一律取默认值，
     * 这样 BackupDumper / 一批既有测试不必为「没有新选项」改签名。
     */
    public ExportOptions(ExportFormat format, boolean includeHeader, int batchSize,
                         long maxRows, boolean quoteAll, Charset charset,
                         List<MaskRule> masks, String maskSalt) {
        this(format, includeHeader, batchSize, maxRows, quoteAll, charset, masks, maskSalt,
                false, false, null, DEFAULT_TEXT_QUALIFIER,
                DEFAULT_DATE_ORDER, DEFAULT_DATE_DELIMITER, DEFAULT_ZERO_PADDING_DATE,
                DEFAULT_TIME_DELIMITER, DEFAULT_DECIMAL_SYMBOL, DEFAULT_BINARY_ENCODING);
    }

    /** 7 参兼容构造器（P-3 首版：有掩码规则、还没有盐值）。 */
    public ExportOptions(ExportFormat format, boolean includeHeader, int batchSize,
                         long maxRows, boolean quoteAll, Charset charset,
                         List<MaskRule> masks) {
        this(format, includeHeader, batchSize, maxRows, quoteAll, charset, masks, "");
    }

    /** 6 参兼容构造器：P-3 之前所有调用点都是 6 参，不逼着它们改签名。 */
    public ExportOptions(ExportFormat format, boolean includeHeader, int batchSize,
                         long maxRows, boolean quoteAll, Charset charset) {
        this(format, includeHeader, batchSize, maxRows, quoteAll, charset, List.of(), "");
    }

    public static ExportOptions of(ExportFormat format) {
        return new ExportOptions(format, true, 500, 0, false, StandardCharsets.UTF_8,
                List.of(), "");
    }

    // ─────────────── 链式方法：每个都把 18 个组件全部带上 ───────────────
    // ⚠️ 新增字段后最容易出的错：某个 with 只 new 了旧的那 8 个参数，把新字段
    //    悄悄清零。下面每一条都显式列出全部组件，只有目标字段换成实参。

    /** 限制行数（用于「当前页」「选中行」这类小范围导出）。 */
    public ExportOptions withMaxRows(long rows) {
        return new ExportOptions(format, includeHeader, batchSize, rows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withCharset(Charset value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, value,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withQuoteAll(boolean value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, value, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withHeader(boolean value) {
        return new ExportOptions(format, value, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    /** ⭐ P-3：挂上列级脱敏规则（不会丢掉前面 with* 设过的选项）。 */
    public ExportOptions withMasks(List<MaskRule> value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                value, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    /** ⭐ P-3 加固：设置 HASH 盐值；与 masks 一样，任何后续 with* 都必须带上它。 */
    public ExportOptions withMaskSalt(String value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, value, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    /** D-86：追加到已有目标文件（不清空、不重复写表头）。 */
    public ExportOptions withAppend(boolean value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, value, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    /** D-86：坏行跳过并累计错误数。 */
    public ExportOptions withContinueOnError(boolean value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, value, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    /** D-86：文本格式的记录结尾。 */
    public ExportOptions withRecordDelimiter(String value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, value, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    /** D-86：CSV / TSV 的引用字符（与 quoteAll 语义分开）。 */
    public ExportOptions withTextQualifier(char value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, value,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    /** D-86：一次性设置 Formats 分区的六个值格式选项。 */
    public ExportOptions withFormats(String dateOrder, String dateDelimiter,
                                     boolean zeroPaddingDate, String timeDelimiter,
                                     char decimalSymbol, String binaryEncoding) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withDateOrder(String value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                value, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withDateDelimiter(String value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, value, zeroPaddingDate, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withZeroPaddingDate(boolean value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, value, timeDelimiter, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withTimeDelimiter(String value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, value, decimalSymbol,
                binaryEncoding);
    }

    public ExportOptions withDecimalSymbol(char value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, value,
                binaryEncoding);
    }

    public ExportOptions withBinaryEncoding(String value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt, append, continueOnError, recordDelimiter, textQualifier,
                dateOrder, dateDelimiter, zeroPaddingDate, timeDelimiter, decimalSymbol,
                value);
    }
}
