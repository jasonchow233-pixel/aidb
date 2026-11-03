package io.agenticdb.editing;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;

/**
 * 界面把「这一格被清空了」翻译成要绑进 SQL 的那个值。
 *
 * <p>这条规则原来只写在界面里（{@code DataGridPage.parseCellValue} 的一个私有分支），
 * 于是真库契约只能<b>猜</b>它长什么样 —— 第一版就猜错了：契约把空串直接绑进
 * {@code DECIMAL} 列，五家里四家报服务端错误、SQL Server 的 {@code BIT} 静默折成 0，
 * 看上去像四个缺陷，实际上是契约自己造出来的形状。规则放进这里之后，
 * 界面和契约调的是<b>同一个方法</b>，谁改了另一边立刻跟着红。
 *
 * <p>为什么文本档和非文本档要走两头：见 {@link ColumnType#textual()} —— 非文本档
 * 没有「空串」这个值，服务端要么整批拒绝，要么静默折成 0。
 */
public final class EditValues {

    private EditValues() {
    }

    /**
     * 用户把这一格的内容删干净了 —— 要绑进 SQL 的是什么。
     *
     * @return 文本档给空串 {@code ""}（那一格确实是空字符串）；其余档给 {@code null}
     *         （那一格变成 NULL）。非空列（{@code nullable() == false}）由调用方在
     *         <b>打到服务器之前</b>拒掉，别把原生完整性错误当提示发给用户。
     */
    public static Object emptiedCell(ColumnDef column) {
        return column.type().textual() ? "" : null;
    }
}
