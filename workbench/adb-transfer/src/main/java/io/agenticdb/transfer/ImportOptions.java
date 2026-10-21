package io.agenticdb.transfer;

import java.util.List;

/**
 * 导入参数。
 *
 * @param format      格式
 * @param hasHeader   第一行是不是表头（表头名用来自动匹配列）
 * @param batchSize   每多少行提交一次
 * @param columnMap   文件第 i 列 → 目标表的第 j 列；{@code null} 表示按顺序一一对应
 * @param skipRows    开头跳过的行数（没有表头但前面有几行说明时用）
 * @param stopOnError 遇到坏行就停；{@code false} 时跳过坏行继续
 */
public record ImportOptions(
        ImportFormat format,
        boolean hasHeader,
        int batchSize,
        List<int[]> columnMap,
        int skipRows,
        boolean stopOnError) {

    public ImportOptions {
        format = format == null ? ImportFormat.CSV : format;
        batchSize = batchSize <= 0 ? 200 : batchSize;
        columnMap = columnMap == null ? List.of() : List.copyOf(columnMap);
    }

    public static ImportOptions of(ImportFormat format) {
        return new ImportOptions(format, true, 200, List.of(), 0, false);
    }

    /** 按顺序一一对应。 */
    public ImportOptions withSequentialMapping() {
        return new ImportOptions(format, hasHeader, batchSize, List.of(), skipRows, stopOnError);
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
