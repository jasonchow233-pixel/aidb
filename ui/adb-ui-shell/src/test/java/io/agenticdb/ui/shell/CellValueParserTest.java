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

    private static ColumnDef column(ColumnType type) {
        return new ColumnDef("value", type, true, false,
                null, null, null, null, null, null, false);
    }
}
