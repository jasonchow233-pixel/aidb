package io.agenticdb.ui.shell;

/**
 * 表元数据的「占位符」判定。
 *
 * <p>⭐ R37 N14：清单固定六列、方言拿不到的格显示「—」，列本身不再按数据隐藏 ——
 * 所以这里只剩一条判据：一段渲染文本是不是「没有值」。
 *
 * <p>判的是 {@code *Text()} 的渲染结果（它已经把 {@code null} 规范成「—」），
 * 所以「显示什么」和「据此换个说法（如 tooltip）」用的是同一套文本，不会漂移。
 */
final class MetadataColumns {

    /** 占位符：与 {@code TableMeta.textOrDash} 保持一致。 */
    static final String DASH = "—";

    /**
     * ⭐ T7：引擎拿不到这项元数据时挂在「—」格上的解释（tooltip 与读屏同一句）。
     *
     * <p>「—」不是「没有值」，是「此引擎不提供该元数据」—— 这句只解释，不编造数据。
     * 用 ASCII 字面量：既不自造 i18n 键，也不碰硬编码中文棘轮。
     */
    static final String UNAVAILABLE_HINT = "This engine does not provide this metadata";

    private MetadataColumns() {
    }

    /** 这段渲染文本是不是「占位符」（null / 空白 / 破折号）。 */
    static boolean isPlaceholder(String text) {
        return text == null || text.isBlank() || DASH.equals(text.trim());
    }
}
