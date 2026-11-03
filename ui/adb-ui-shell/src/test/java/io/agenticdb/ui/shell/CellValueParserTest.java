package io.agenticdb.ui.shell;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellValueParserTest {

    @Test
    void convertsEditorTextToColumnTypes() {
        assertThat(CellValueParser.parse(column(ColumnType.NUMBER), "42")).isEqualTo(42L);
        assertThat(CellValueParser.parse(column(ColumnType.NUMBER), "42.5")).isEqualTo(42.5);
        assertThat(CellValueParser.parse(column(ColumnType.BOOLEAN), "true")).isEqualTo(true);
        assertThat(CellValueParser.parse(column(ColumnType.BOOLEAN), "0")).isEqualTo(false);
        assertThat(CellValueParser.parse(column(ColumnType.TEXT), "")).isEqualTo("");
        assertThat(CellValueParser.parse(column(ColumnType.TIMESTAMP), "2026-10-20 10:00:00"))
                .isEqualTo("2026-10-20 10:00:00");
    }

    @Test
    void rejectsInvalidNumbersAndBooleans() {
        assertThatThrownBy(() -> CellValueParser.parse(column(ColumnType.NUMBER), "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(io.agenticdb.ui.I18n.t("parse.notNumber", "abc"));
        assertThatThrownBy(() -> CellValueParser.parse(column(ColumnType.BOOLEAN), "maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(io.agenticdb.ui.I18n.t("parse.notBoolean", "maybe"));
    }

    @Test
    void nullAndEmptyStringHaveDistinctDisplay() {
        assertThat(CellValueParser.display(null)).isEqualTo("NULL");
        assertThat(CellValueParser.display("")).isEqualTo(io.agenticdb.ui.I18n.t("parse.emptyStringLabel"));
    }

    /**
     * ⭐ R53：用户把一格的内容<b>删干净</b>之后，格子往服务器绑的是什么。
     *
     * <p>这一条量的是<b>接线</b>，不是 {@code EditValues} 的规则本身（规则由
     * {@code EditValuesTest} 钉）：界面曾经在这里有一个私有分支自己判断「非文本档给 null」，
     * 于是真库契约只能猜那个分支的形状 —— 第一版猜成「界面交空串」，
     * 直接往 {@code DECIMAL} 列绑 {@code ""}，四家服务端拒、SQL Server 的 {@code BIT}
     * 静默折成 0，看着像四个缺陷，实际是契约自己造的形状。
     * 现在规则和契约共用 {@code adb-editing} 那一个方法，这条测试守住的是
     * 「界面有没有真的去调它」。
     */
    @Test
    void emptiedTypedCellBindsNullNotEmptyString() {
        assertThat(DataGridPage.parseCellValue(column(ColumnType.NUMBER), ""))
                .as("数字格清空 = 真 NULL。绑 \"\" 会被 MySQL/MariaDB/PG/Oracle 整批拒掉")
                .isNull();
        assertThat(DataGridPage.parseCellValue(column(ColumnType.BOOLEAN), ""))
                .as("布尔格清空 = 真 NULL。SQL Server 的 BIT 会把 \"\" 静默折成 0，那是改数不是清空")
                .isNull();
        assertThat(DataGridPage.parseCellValue(column(ColumnType.TIMESTAMP), ""))
                .as("日期格清空 = 真 NULL")
                .isNull();
        assertThat(DataGridPage.parseCellValue(column(ColumnType.TEXT), ""))
                .as("文本格清空 = 空串：和 NULL 是两件事，网格里分开显示")
                .isEqualTo("");
        // 非空档不是这里拦的：调用方（commitEditor / 批量 / 表单）在打到服务器之前
        // 用 grid.batch.notNull 拒掉，别把原生完整性错误当提示发给用户。
        assertThat(DataGridPage.parseCellValue(column(ColumnType.NUMBER), "42"))
                .isEqualTo(42L);
    }

    private static ColumnDef column(ColumnType type) {
        return new ColumnDef("value", type, true, false,
                null, null, null, null, null, null, false);
    }
}
