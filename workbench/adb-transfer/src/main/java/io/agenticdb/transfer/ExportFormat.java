package io.agenticdb.transfer;

import java.util.List;

/**
 * 导出格式。
 *
 * <p>SQL 格式导出的不是 {@code SELECT}，而是可重放的 {@code INSERT} 语句 ——
 * 这是 Navicat 的做法：导出的文件能直接拿去另一个库灌数据。
 */
public enum ExportFormat {

    CSV("CSV", "逗号分隔，RFC 4180", ','),
    TSV("TSV", "制表符分隔", '\t'),
    JSON("JSON", "数组，每行一个对象（JSON Lines）", '\0'),
    SQL("SQL", "INSERT 语句，可直接重放", '\0');

    private final String label;
    private final String hint;
    private final char delimiter;

    ExportFormat(String label, String hint, char delimiter) {
        this.label = label;
        this.hint = hint;
        this.delimiter = delimiter;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    /** 分隔符。JSON / SQL 没有分隔符，返回 {@code '\0'}。 */
    public char delimiter() {
        return delimiter;
    }

    public boolean hasHeader() {
        return this == CSV || this == TSV;
    }
}
