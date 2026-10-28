package io.agenticdb.transfer;

/**
 * 导入模式。
 *
 * <p>Navicat 的导入模式有一长串（追加 / 更新 / 追加或更新 …）。这里只做
 * 「不改变已有数据」与「先清空再写入」两档 —— 这两档的语义在一条事务里
 * 可以严格保证，而 upsert 需要知道主键、在 SQLite / MySQL / PG 上语法各不相同，
 * 猜错的后果是静默改数据。
 */
public enum ImportMode {

    APPEND("追加"),
    /** 先 {@code DELETE FROM 表} 再导入；与导入同一条事务，失败一起回滚。 */
    REPLACE("覆盖（先清空目标表）");

    private final String label;

    ImportMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
