package io.agenticdb.introspect;

import io.agenticdb.model.ByteSizes;

/**
 * 表的元信息 —— Navicat「表清单」那一行的内容。
 *
 * <p>Navicat 的表清单是<b>六列</b>：名称 / 行 / 数据长度 / 引擎 / 修改日期 / 注释
 * （两张官方帧 {@code navicat-table-list-2026-10-24.png}、
 * {@code navicat17-object-list-2026-10-27.png} 都是这六列，<b>没有创建日期、
 * 没有排序规则</b>）。本记录多带 {@link #createdAt} 与 {@link #collation} 两个字段，
 * 不是因为清单上有这两列，而是<b>对象详情面板</b>要摆这两项事实
 * （{@code AppShell} 用 {@code column.collation} / {@code detail.createdAt} 当标签）；
 * 它们没有 {@code *Text()} 显示 helper —— 那条路以前只有测试在走，界面从不经过，
 * 属于「取到了但没人显示」的死通道，D-70 清理时删掉。
 *
 * <p>方言拿不到的字段保持 {@code null}，由 UI 显示「—」——**绝不编造一个看起来像真的值**。
 *
 * @param name        表名
 * @param rows        行数；{@code null} 表示没统计出来
 * @param sizeBytes   数据长度（字节）；{@code null} 表示这个库不提供
 * @param engine      引擎（InnoDB / SQLite / tablespace…）；{@code null} 表示不提供
 * @param createdAt   创建时间文本；{@code null} 表示不提供（只上对象详情，不是清单列）
 * @param updatedAt   最后修改时间文本；{@code null} 表示不提供（清单六列之一）
 * @param collation   排序规则；{@code null} 表示不提供（只上对象详情，不是清单列）
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
     * <p>旧版本只填到第 5 个参数（{@code createdAt}），后面三项留空。保留这个构造器，
     * 避免为了补字段去改一堆只关心行数 / 大小的调用点 —— 走这条路的调用点
     * 本来也不显示创建时间。
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
        // 与数据网格的 BLOB 标签共用一份格式化 —— ByteSizes 是这条语义的唯一真相
        // （负值 = 来源不可信，讲「—」；进位后保留一位小数会顶成 1024.0 的先升一档）。
        return sizeBytes == null ? "—" : ByteSizes.format(sizeBytes);
    }

    public String engineText() {
        return textOrDash(engine);
    }

    public String updatedText() {
        return textOrDash(updatedAt);
    }

    public String commentText() {
        return textOrDash(comment);
    }

    private static String textOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
