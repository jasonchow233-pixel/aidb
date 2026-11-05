package io.agenticdb.transfer;

/**
 * 导入模式。
 *
 * <p>Navicat 的导入模式有一长串（追加 / 更新 / 追加或更新 …）。这里只做
 * 「不改变已有数据」与「先清空再写入」两档 —— 这两档的语义在一条事务里
 * 可以严格保证，而 upsert 需要知道主键、在 SQLite / MySQL / PG 上语法各不相同，
 * 猜错的后果是静默改数据。
 *
 * <p>⭐ D-59：本层不依赖界面，所以只存<b>消息键</b>；渲染由界面
 * （{@code ImportWizard} 的 StringConverter）用当前语言解析。
 */
public enum ImportMode {

    APPEND("import.mode.append"),
    /** 先 {@code DELETE FROM 表} 再导入；与导入同一条事务，失败一起回滚。 */
    REPLACE("import.mode.replace");

    private final String labelKey;

    ImportMode(String labelKey) {
        this.labelKey = labelKey;
    }

    public String labelKey() {
        return labelKey;
    }
}
