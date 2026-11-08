package io.agenticdb.transfer;

/**
 * 导入格式。
 */
public enum ImportFormat {

    CSV("CSV", "import.format.csv.hint", ','),
    TSV("TSV", "import.format.tsv.hint", '\t'),
    XLSX("XLSX", "import.format.xlsx.hint", '\0'),
    JSON("JSON", "import.format.json.hint", '\0'),
    SQL("SQL", "import.format.sql.hint", '\0');

    private final String label;
    private final String hintKey;
    private final char delimiter;

    ImportFormat(String label, String hintKey, char delimiter) {
        this.label = label;
        this.hintKey = hintKey;
        this.delimiter = delimiter;
    }

    public String label() {
        return label;
    }

    /** ⭐ D-59：说明文案只存键，由界面用当前语言渲染。 */
    public String hintKey() {
        return hintKey;
    }

    public char delimiter() {
        return delimiter;
    }

    /** XLSX 是二进制格式，不能按文本行读取。 */
    public boolean binary() {
        return this == XLSX;
    }

    /**
     * ⭐ H4-a：表头内嵌于格式 —— JSON 每个对象自带键、SQL 自带列清单，
     * 表头行 / 跳过行这两个 CSV 概念对它们没有意义；编码仍然适用（也是文本文件）。
     */
    public boolean implicitHeader() {
        return this == JSON || this == SQL;
    }
}
