package io.agenticdb.transfer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
 */
public record ExportOptions(
        ExportFormat format,
        boolean includeHeader,
        int batchSize,
        long maxRows,
        boolean quoteAll,
        Charset charset) {

    public ExportOptions {
        format = format == null ? ExportFormat.CSV : format;
        batchSize = batchSize <= 0 ? 500 : batchSize;
        charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    public static ExportOptions of(ExportFormat format) {
        return new ExportOptions(format, true, 500, 0, false, StandardCharsets.UTF_8);
    }

    /** 限制行数（用于「当前页」「选中行」这类小范围导出）。 */
    public ExportOptions withMaxRows(long rows) {
        return new ExportOptions(format, includeHeader, batchSize, rows, quoteAll, charset);
    }

    public ExportOptions withCharset(Charset value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, quoteAll, value);
    }

    public ExportOptions withQuoteAll(boolean value) {
        return new ExportOptions(format, includeHeader, batchSize, maxRows, value, charset);
    }

    public ExportOptions withHeader(boolean value) {
        return new ExportOptions(format, value, batchSize, maxRows, quoteAll, charset);
    }
}
