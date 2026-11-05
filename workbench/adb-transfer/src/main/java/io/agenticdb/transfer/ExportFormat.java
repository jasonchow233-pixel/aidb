package io.agenticdb.transfer;

/**
 * 导出格式。
 *
 * <p>SQL 格式导出的不是 {@code SELECT}，而是可重放的 {@code INSERT} 语句 ——
 * 这是 Navicat 的做法：导出的文件能直接拿去另一个库灌数据。
 */
public enum ExportFormat {

    CSV("CSV", "export.format.csv.hint", ','),
    TSV("TSV", "export.format.tsv.hint", '\t'),
    JSON("JSON", "export.format.json.hint", '\0'),
    SQL("SQL", "export.format.sql.hint", '\0'),
    XLSX("XLSX", "export.format.xlsx.hint", '\0'),
    XML("XML", "export.format.xml.hint", '\0'),
    HTML("HTML", "export.format.html.hint", '\0');

    private final String label;
    private final String hintKey;
    private final char delimiter;

    ExportFormat(String label, String hintKey, char delimiter) {
        this.label = label;
        this.hintKey = hintKey;
        this.delimiter = delimiter;
    }

    public String label() {
        return label;
    }

    /** ⭐ D-59：说明文案只存键，由界面用当前语言渲染（D-83 让它真的上屏）。 */
    public String hintKey() {
        return hintKey;
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
