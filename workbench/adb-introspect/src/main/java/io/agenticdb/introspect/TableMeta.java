package io.agenticdb.introspect;

/**
 * 表的元信息 —— Navicat「表清单」那一行的内容。
 *
 * @param name       表名
 * @param rows       行数；{@code null} 表示没统计出来
 * @param sizeBytes  数据长度（字节）；{@code null} 表示这个库不提供
 * @param engine     引擎（InnoDB / SQLite / tablespace…）；{@code null} 表示不提供
 * @param createdAt  创建时间文本；{@code null} 表示不提供
 */
public record TableMeta(
        String name,
        Long rows,
        Long sizeBytes,
        String engine,
        String createdAt) {

    public TableMeta {
        name = name == null ? "" : name;
    }

    /** 供 UI 显示：没有值时给个占位符，不要留空白让人以为「忘了填」。 */
    public String rowsText() {
        return rows == null ? "—" : String.valueOf(rows);
    }

    public String sizeText() {
        if (sizeBytes == null) {
            return "—";
        }
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return (sizeBytes / 1024.0) + " KB";
        }
        return String.format("%.1f MB", sizeBytes / 1024.0 / 1024.0);
    }

    public String engineText() {
        return engine == null || engine.isBlank() ? "—" : engine;
    }

    public String createdText() {
        return createdAt == null || createdAt.isBlank() ? "—" : createdAt;
    }
}
