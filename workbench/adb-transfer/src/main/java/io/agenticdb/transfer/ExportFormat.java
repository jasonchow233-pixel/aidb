package io.agenticdb.transfer;

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
    SQL("SQL", "INSERT 语句，可直接重放", '\0'),
    XLSX("XLSX", "Excel 工作簿（流式写出）", '\0'),
    XML("XML", "每行一个 <row>，列名写在 <c name> 上", '\0'),
    HTML("HTML", "带表头的 <table>，可直接用浏览器打开", '\0');

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

    /** 分隔符。JSON / SQL / XLSX / XML / HTML 没有分隔符，返回 {@code '\0'}。 */
    public char delimiter() {
        return delimiter;
    }

    /** 是否是「写入 Writer 的文本格式」；XLSX 是二进制，走 {@code exportXlsx}。 */
    public boolean binary() {
        return this == XLSX;
    }

    /** 只有 CSV / TSV 有可选的表头行 —— 其余格式的表头是结构的一部分。 */
    public boolean hasHeader() {
        return this == CSV || this == TSV;
    }

    /** 文件后缀（小写，带点）。 */
    public String extension() {
        return "." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
