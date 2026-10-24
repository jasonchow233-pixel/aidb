package io.agenticdb.ui.shell;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数字展示的门禁 —— 守的是「显示可以更干净，但值一个字节都不能动」。
 *
 * <h2>为什么这个测试必须存在</h2>
 * 演示库 {@code orders.amount} 是 {@code REAL}，JDBC 给回 {@code double}，
 * 旧的 {@code String.valueOf(double)} 把 IEEE754 尾数整段暴露成
 * {@code 22.990000000000002}。用户看到的第一反应是"数据坏了"，
 * 而不是"浮点精度" —— 一个数字显示错，会让人怀疑整个工具。
 *
 * <h2>本测试卡住的三条链路</h2>
 * <ol>
 *   <li><b>展示</b> {@link CellRenderer#doubleText(double)} —— 允许收敛，只影响眼睛。</li>
 *   <li><b>编辑回填</b> {@link DataGridPage#editorText(Object)} —— <b>必须</b>是原值。</li>
 *   <li><b>导出</b> {@link DataGridPage#csvValue(Object)} —— <b>必须</b>是原值。</li>
 * </ol>
 * 后两条一旦改成复用展示文本，用户"打开单元格再回车"就会把精度真正写坏，
 * 从显示问题升级成数据损坏。这比原来那个 bug 严重得多，所以必须钉死。
 */
class NumberFormattingTest {

    /** 截图里真实出现的那个值。注意它和 22.99 是<b>两个不同的 double</b>。 */
    private static final double NOISY_AMOUNT = 22.990000000000002d;

    @Test
    void stripsIeee754NoiseFromRealColumns() {
        ColumnDef amount = number("amount");

        assertThat(CellRenderer.render(amount, NOISY_AMOUNT).text()).isEqualTo("22.99");
        // 0.1 + 0.2 的经典噪声
        assertThat(CellRenderer.render(amount, 0.1d + 0.2d).text()).isEqualTo("0.3");
    }

    @Test
    void editorAndExportKeepTheRawValue() {
        // ⭐ 这条是本测试存在的理由：展示被收敛了，但另外两条链路绝不能跟着收敛。
        String displayed = CellRenderer.render(number("amount"), NOISY_AMOUNT).text();
        String raw = String.valueOf(NOISY_AMOUNT);

        assertThat(displayed).isEqualTo("22.99");
        assertThat(raw).isEqualTo("22.990000000000002");

        assertThat(DataGridPage.editorText(NOISY_AMOUNT)).isEqualTo(raw);
        assertThat(DataGridPage.csvValue(NOISY_AMOUNT)).isEqualTo(raw);

        // 反过来：任何一处偷用展示文本，立刻红。
        assertThat(DataGridPage.editorText(NOISY_AMOUNT)).isNotEqualTo(displayed);
        assertThat(DataGridPage.csvValue(NOISY_AMOUNT)).isNotEqualTo(displayed);
    }

    @Test
    void keepsBusinessPrecisionIntact() {
        ColumnDef amount = number("amount");

        // 真实业务精度：小数位远低于 12 位阈值，一个字都不许动。
        assertThat(CellRenderer.render(amount, 22.99d).text()).isEqualTo("22.99");
        assertThat(CellRenderer.render(amount, 0.123456d).text()).isEqualTo("0.123456");
        assertThat(CellRenderer.render(amount, 12345.6789d).text()).isEqualTo("12345.6789");
        // DECIMAL 列带声明 scale：100.00 就该显示 100.00，不能被 stripTrailingZeros 抹掉。
        assertThat(CellRenderer.render(amount, new BigDecimal("100.00")).text())
                .isEqualTo("100.00");
        // 整数类型根本不进浮点分支
        assertThat(CellRenderer.render(amount, 42).text()).isEqualTo("42");
        assertThat(CellRenderer.render(amount, 42L).text()).isEqualTo("42");
    }

    @Test
    void handlesEdgeCasesWithoutLosingData() {
        ColumnDef amount = number("amount");

        // 极大值：不能被写成科学计数法，也不能丢位
        assertThat(CellRenderer.render(amount, 1e20d).text())
                .isEqualTo("100000000000000000000");
        // 负零：显示成 0，而不是 -0.0
        assertThat(CellRenderer.render(amount, -0.0d).text()).isEqualTo("0");
        // ⭐ 极小值：收敛到 12 位会变成 0 —— 那是真丢数据，必须退回原样
        assertThat(CellRenderer.render(amount, 1e-20d).text())
                .isNotEqualTo("0")
                .contains("0.00000000000000000001");
        // 非有限值没有十进制表示，原样
        assertThat(CellRenderer.render(amount, Double.NaN).text()).isEqualTo("NaN");
        assertThat(CellRenderer.render(amount, Double.POSITIVE_INFINITY).text())
                .isEqualTo("Infinity");
    }

    @Test
    void tooltipExposesRawValueWhenDisplayWasRounded() {
        ColumnDef amount = number("amount");

        // 展示被收敛过 → tooltip 必须给出原值，不能瞒。
        assertThat(CellRenderer.render(amount, NOISY_AMOUNT).tooltip())
                .contains("22.990000000000002");
        // 没被收敛 → tooltip 就是展示值本身，不画蛇添足加「原值」前缀。
        assertThat(CellRenderer.render(amount, 22.99d).tooltip()).isEqualTo("22.99");
    }

    @Test
    void nullStillRendersAsNull() {
        // 数字列也一样：NULL 就是 NULL，不能因为「数字列不可能是空串」就画成空。
        assertThat(CellRenderer.render(number("amount"), null).text()).isEqualTo("(NULL)");
    }

    private static ColumnDef number(String name) {
        return new ColumnDef(name, ColumnType.NUMBER, true, false, null, null,
                null, null, null, null, false);
    }
}
