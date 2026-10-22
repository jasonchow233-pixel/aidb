package io.agenticdb.introspect;

/**
 * 表的元信息 —— Navicat「表清单」那一行的内容。
 *
 * <p>Navicat 的表清单是 8 列：**名称 / 行 / 数据长度 / 引擎 / 创建日期 /
 * 修改日期 / 排序规则 / 注释**。本记录按这 8 列建模；方言拿不到的字段保持
 * {@code null}，由 UI 显示「—」——**绝不编造一个看起来像真的值**。
 *
 * @param name        表名
 * @param rows        行数；{@code null} 表示没统计出来
 * @param sizeBytes   数据长度（字节）；{@code null} 表示这个库不提供
 * @param engine      引擎（InnoDB / SQLite / tablespace…）；{@code null} 表示不提供
 * @param createdAt   创建时间文本；{@code null} 表示不提供
 * @param updatedAt   最后修改时间文本；{@code null} 表示不提供
 * @param collation   排序规则；{@code null} 表示不提供
 * @param comment     表注释；{@code null} 表示不提供
 */
public record TableMeta(
        String name,
        Long rows,
        Long sizeBytes,
        String engine,
        String createdAt,
        String updatedAt,
        String collation,
        String comment) {

    /**
     * 兼容旧调用点的 5 参数构造器。
     *
     * <p>旧版本只知道前 5 列。保留这个构造器，避免为了补 3 列去改一堆
     * 只关心行数 / 大小的测试与调用点。
     */
    public TableMeta(String name, Long rows, Long sizeBytes, String engine, String createdAt) {
        this(name, rows, sizeBytes, engine, createdAt, null, null, null);
    }

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
        return textOrDash(engine);
    }

    public String createdText() {
        return textOrDash(createdAt);
    }

    public String updatedText() {
        return textOrDash(updatedAt);
    }

    public String collationText() {
        return textOrDash(collation);
    }

    public String commentText() {
        return textOrDash(comment);
    }

    private static String textOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
