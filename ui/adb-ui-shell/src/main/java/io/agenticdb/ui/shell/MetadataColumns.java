package io.agenticdb.ui.shell;

import java.util.List;
import java.util.function.Function;

/**
 * 元数据列的「有没有值」判定 —— 决定 Navicat 那 8 列里哪些该显示。
 *
 * <h2>为什么</h2>
 * Navicat 的表清单是 8 列（名称/行/数据长度/引擎/创建/修改/排序规则/注释）。
 * 我们照抄了列集以求对齐，但**方言拿不到的字段显示「—」**（{@code TableMeta} 的
 * {@code null} 约定）。在默认的 SQLite 演示库里，8 列有 6 列恒为「—」，
 * 占掉首选宽度的 72%（逐像素实测，见 {@code AIDB-UIUX-ANALYSIS.md} §10.3 C1）——
 * 用户看到的是一堵破折号墙，还得横向滚动才能看到真正有内容的「名称」。
 *
 * <h2>规则：看数据，不看方言</h2>
 * 决定一列显不显示，**只问「这一列在当前这批数据里有没有至少一行给出真值」**。
 * 这样比维护一张「哪个方言提供哪个字段」的白名单更稳：
 * <ul>
 *   <li>新方言接入自动正确，不用改这里；</li>
 *   <li>混合场景正确 —— MySQL 的表有引擎、视图没有，只要**有一行**有值，
 *       整列就留着（不会因为部分行是空的就把列藏掉）；</li>
 *   <li>诚实 —— 不是「这个方言大概没有」，而是「当前数据里确实一个值都没有」。</li>
 * </ul>
 *
 * <p>判定用的是 {@code *Text()} 渲染结果（它已经把 {@code null} 规范成「—」），
 * 所以「显示什么」和「据以隐藏什么」用的是同一套文本，不会漂移。
 *
 * <p>⚠️ 判定必须基于**整批已加载数据**（{@code rows}），<b>不能</b>基于搜索过滤后的
 * 子集 —— 否则用户一搜索，列就会凭空消失/出现，界面在脚下动。
 */
final class MetadataColumns {

    /** 占位符：与 {@code TableMeta.textOrDash} 保持一致。 */
    static final String DASH = "—";

    private MetadataColumns() {
    }

    /** 这段渲染文本是不是「占位符」（null / 空白 / 破折号）。 */
    static boolean isPlaceholder(String text) {
        return text == null || text.isBlank() || DASH.equals(text.trim());
    }

    /**
     * 这一列在整批数据里有没有**至少一行**给出真值。
     *
     * <p>返回 {@code false} 表示每一行都是占位符 → 这一列没信息量，应当整列隐藏。
     *
     * @param rows 判定依据的数据（应当是**全部已加载**的行，不是过滤后的子集）
     * @param text 取某一行在该列的**渲染后**文本（如 {@code TableMeta::engineText}）
     */
    static <T> boolean hasAnyValue(List<T> rows, Function<T, String> text) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        return rows.stream().anyMatch(row -> !isPlaceholder(text.apply(row)));
    }
}
