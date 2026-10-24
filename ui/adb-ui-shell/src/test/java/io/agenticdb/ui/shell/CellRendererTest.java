package io.agenticdb.ui.shell;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CellRendererTest {

    @Test
    void keepsNullAndEmptyStringDistinct() {
        ColumnDef text = column("bio", ColumnType.TEXT, null);

        assertThat(CellRenderer.render(text, null).text()).isEqualTo("(NULL)");
        assertThat(CellRenderer.render(text, null).styleClass()).contains("null");
        assertThat(CellRenderer.render(text, "").text()).isEqualTo("\"\"");
        assertThat(CellRenderer.render(text, "").styleClass()).contains("empty");
    }

    @Test
    void summarizesJsonAndFormatsDatesAndEnums() {
        ColumnDef json = column("payload", ColumnType.JSON, null);
        ColumnDef date = column("created_at", ColumnType.TIMESTAMP, null);
        ColumnDef level = column("level", ColumnType.ENUM, "1=待支付,2=已支付");
        ColumnDef dateOnly = column("day", ColumnType.DATE, null);

        assertThat(CellRenderer.render(json, "{\"a\":1,\"b\":2}").text())
                .startsWith("{…} 2 字段");
        assertThat(CellRenderer.render(date, LocalDateTime.of(2026, 10, 20, 9, 5, 3)).text())
                .isEqualTo("2026-10-20 09:05:03");
        assertThat(CellRenderer.render(dateOnly, LocalDate.of(2026, 10, 20)).text())
                .isEqualTo("2026-10-20");
        assertThat(CellRenderer.render(level, 2).text()).isEqualTo("已支付");
        assertThat(CellRenderer.render(level, 2).tooltip()).contains("已支付");
    }

    @Test
    void rendersBinaryAsSizeChipAndProducesHexDump() {
        ColumnDef binary = column("avatar", ColumnType.BINARY, null);
        byte[] bytes = new byte[]{0x01, 0x02, 0x41, 0x42};

        CellRenderer.Rendered rendered = CellRenderer.render(binary, bytes);

        assertThat(rendered.text()).isEqualTo("⬢ 4 B");
        assertThat(rendered.styleClass()).contains("binary");
        assertThat(CellRenderer.hexDump(bytes))
                .contains("00000000")
                .contains("01 02 41 42")
                .contains("|..AB|");
    }

    @Test
    void compactsLongTextAndPrettyPrintsJson() {
        ColumnDef text = column("body", ColumnType.TEXT, null);
        String longText = "line one\nline two " + "x".repeat(100);

        CellRenderer.Rendered rendered = CellRenderer.render(text, longText);

        assertThat(rendered.text()).endsWith("…");
        assertThat(rendered.tooltip()).isEqualTo(longText);
        assertThat(CellRenderer.prettyJson("{\"a\":1}")).contains("\n").contains("\"a\" : 1");
    }

    private static ColumnDef column(String name, ColumnType type, String enumValues) {
        return new ColumnDef(name, type, true, false, null, null,
                enumValues, null, null, null, false);
    }
}
