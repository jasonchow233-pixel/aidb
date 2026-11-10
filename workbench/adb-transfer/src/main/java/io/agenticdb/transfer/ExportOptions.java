package io.agenticdb.transfer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导出参数。
 *
 * @param format        格式
 * @param includeHeader CSV / TSV 是否写表头行
 * @param batchSize     每多少行提交一次（导出时是写出的分组大小）
 * @param maxRows       最多导出多少行；{@code <= 0} 表示不限
 * @param quoteAll      CSV / TSV 是否给<b>所有</b>字段加文本限定符。
 *                      默认关：Navicat 也只在字段里出现分隔符 / 引号 / 换行时才加引号，
 *                      无脑加引号会让文件难读，也会让严格的消费方把数字当字符串。
 *                      （SQL 格式的字符串字面量恒加单引号 —— 那是语法要求，不受此开关影响。）
 * @param charset       文本格式的写出编码（xlsx 是二进制，忽略）
 * @param masks         ⭐ P-3 列级脱敏规则；空列表 = 原样导出。规则随选项一起走，
 *                      七种格式与「整表 / 当前页 / 选中行」三种范围才不会漏掉一条
 * @param maskSalt      ⭐ P-3 加固：HASH 模式的导出级盐值。空串 = 不加盐（兼容旧行为），
 *                      非空时摘要输入为 {@code salt + ":" + value}。盐值<b>不写进导出文件</b>，
 *                      所以拿到文件的人无法用低熵值（手机号 / 身份证）的彩虹表反推；
 *                      想跨次导出继续比对，就把同一个盐值留好再填回来。
 */
public record ExportOptions(
        ExportFormat format,
        boolean includeHeader,
        int batchSize,
        long maxRows,
        boolean quoteAll,
        Charset charset,
        List<MaskRule> masks,
        String maskSalt) {

    public ExportOptions {
        format = format == null ? ExportFormat.CSV : format;
        batchSize = batchSize <= 0 ? 500 : batchSize;
        charset = charset == null ? StandardCharsets.UTF_8 : charset;
        masks = masks == null ? List.of() : List.copyOf(masks);
        maskSalt = maskSalt == null ? "" : maskSalt;
    }

    /**
     * ⭐ 兼容构造器：P-3 之前所有调用点都是 6 参，不逼着二十多处测试与
     * {@code BackupDumper} 为「没有脱敏」这种默认情形改签名。
     */
    public ExportOptions(ExportFormat format, boolean includeHeader, int batchSize,
                         long maxRows, boolean quoteAll, Charset charset) {
        this(format, includeHeader, batchSize, maxRows, quoteAll, charset, List.of(), "");
    }

    /** 7 参兼容构造器（P-3 首版：有掩码规则、还没有盐值）。 */
    public ExportOptions(ExportFormat format, boolean includeHeader, int batchSize,
                         long maxRows, boolean quoteAll, Charset charset,
                         List<MaskRule> masks) {
        this(format, includeHeader, batchSize, maxRows, quoteAll, charset, masks, "");
    }

    public static ExportOptions of(ExportFormat format) {
        return new ExportOptions(format, true, 500, 0, false, StandardCharsets.UTF_8,
                List.of(), "");
    }

    /** 限制行数（用于「当前页」「选中行」这类小范围导出）。 */
    public ExportOptions withMaxRows(long rows) {
        return new ExportOptions(format, includeHeader, batchSize, rows, quoteAll, charset,
                masks, maskSalt);
    }

    public ExportOptions withCharset(Charset value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, value,
                masks, maskSalt);
    }

    public ExportOptions withQuoteAll(boolean value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, value, charset,
                masks, maskSalt);
    }

    public ExportOptions withHeader(boolean value) {
        return new ExportOptions(format, value, batchSize, maxRows, quoteAll, charset,
                masks, maskSalt);
    }

    /** ⭐ P-3：挂上列级脱敏规则（不会丢掉前面 with* 设过的选项）。 */
    public ExportOptions withMasks(List<MaskRule> value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                value, maskSalt);
    }

    /** ⭐ P-3 加固：设置 HASH 盐值；与 masks 一样，任何后续 with* 都必须带上它。 */
    public ExportOptions withMaskSalt(String value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, charset,
                masks, value);
    }
}
