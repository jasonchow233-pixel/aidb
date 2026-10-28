package io.agenticdb.transfer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导入参数。
 *
 * @param format      格式
 * @param hasHeader   第一行（{@link #skipRows 跳过说明行之后}）是不是表头
 * @param batchSize   每多少行提交一次
 * @param columnMap   文件第 i 列 → 目标表的第 j 列；{@code null} 表示按顺序一一对应
 * @param skipRows    开头跳过的行数（表头在第 N 行时 = N-1，或表头前有几行说明时）
 * @param stopOnError 遇到坏行就停；{@code false} 时跳过坏行继续
 * @param charset     文本格式的读取编码；文件开头的 BOM 会被吞掉
 * @param mode        追加 / 覆盖（先清空目标表，与导入同一条事务）
 */
public record ImportOptions(
        ImportFormat format,
        boolean hasHeader,
        int batchSize,
        List<int[]> columnMap,
        int skipRows,
        boolean stopOnError,
        Charset charset,
        ImportMode mode) {

    public ImportOptions {
        format = format == null ? ImportFormat.CSV : format;
        batchSize = batchSize <= 0 ? 200 : batchSize;
        columnMap = columnMap == null ? List.of() : List.copyOf(columnMap);
        charset = charset == null ? StandardCharsets.UTF_8 : charset;
        mode = mode == null ? ImportMode.APPEND : mode;
        skipRows = Math.max(0, skipRows);
    }

    public static ImportOptions of(ImportFormat format) {
        return new ImportOptions(format, true, 200, List.of(), 0, false,
                StandardCharsets.UTF_8, ImportMode.APPEND);
    }

    /** 按顺序一一对应。 */
    public ImportOptions withSequentialMapping() {
        return new ImportOptions(format, hasHeader, batchSize, List.of(), skipRows, stopOnError,
                charset, mode);
    }

    public ImportOptions withCharset(Charset value) {
        return new ImportOptions(format, hasHeader, batchSize, columnMap, skipRows, stopOnError,
                value, mode);
    }

    public ImportOptions withMode(ImportMode value) {
        return new ImportOptions(format, hasHeader, batchSize, columnMap, skipRows, stopOnError,
                charset, value);
    }

    /** 表头在第 {@code row} 行（1 起）；{@code 0} 表示没有表头。 */
    public ImportOptions withHeaderRow(int row) {
        int skip = Math.max(0, row - 1);
        return new ImportOptions(format, row > 0, batchSize, columnMap, skip, stopOnError,
                charset, mode);
    }

    /** 表头所在行（1 起）；没有表头时为 {@code 0}。 */
    public int headerRow() {
        return hasHeader ? skipRows + 1 : 0;
    }

    public ImportOptions withFormat(ImportFormat value) {
        return new ImportOptions(value, hasHeader, batchSize, columnMap, skipRows, stopOnError,
                charset, mode);
    }

    /**
     * 目标列的来源下标。
     *
     * @param fileColumn  文件里的第几列（0 起）
     * @param targetColumn 目标表的第几列（0 起）
     */
    public static int[] map(int fileColumn, int targetColumn) {
        return new int[]{fileColumn, targetColumn};
    }
}
