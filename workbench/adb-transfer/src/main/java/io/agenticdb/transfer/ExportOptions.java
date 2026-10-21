package io.agenticdb.transfer;

/**
 * 导出参数。
 *
 * @param format        格式
 * @param includeHeader 是否写表头（CSV / TSV）
 * @param batchSize     每多少行提交一次（导出时是写出的分组大小）
 * @param maxRows       最多导出多少行；{@code <= 0} 表示不限
 * @param quoteValues   SQL 格式下是否给字符串值加引号
 */
public record ExportOptions(
        ExportFormat format,
        boolean includeHeader,
        int batchSize,
        long maxRows,
        boolean quoteValues) {

    public ExportOptions {
        format = format == null ? ExportFormat.CSV : format;
        batchSize = batchSize <= 0 ? 500 : batchSize;
    }

    public static ExportOptions of(ExportFormat format) {
        return new ExportOptions(format, true, 500, 0, true);
    }

    /** 限制行数（用于「当前页」「选中行」这类小范围导出）。 */
    public ExportOptions withMaxRows(long rows) {
        return new ExportOptions(format, includeHeader, batchSize, rows, quoteValues);
    }
}
