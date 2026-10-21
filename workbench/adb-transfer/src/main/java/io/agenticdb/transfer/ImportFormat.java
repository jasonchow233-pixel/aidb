package io.agenticdb.transfer;

/**
 * 导入格式。
 */
public enum ImportFormat {

    CSV("CSV", "逗号分隔，RFC 4180", ','),
    TSV("TSV", "制表符分隔", '\t');

    private final String label;
    private final String hint;
    private final char delimiter;

    ImportFormat(String label, String hint, char delimiter) {
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

    public char delimiter() {
        return delimiter;
    }
}
