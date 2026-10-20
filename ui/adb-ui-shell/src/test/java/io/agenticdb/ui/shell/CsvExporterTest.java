package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExporterTest {

    @Test
    void escapesCommasQuotesAndNewlines() {
        String csv = CsvExporter.csv(List.of("name", "note"),
                List.of(List.of("O'Reilly", "a,b"),
                        List.of("quote", "say \"hi\""),
                        List.of("line", "one\ntwo"),
                        List.of("", "")));

        assertThat(csv).isEqualTo(
                "name,note\r\n"
                        + "O'Reilly,\"a,b\"\r\n"
                        + "quote,\"say \"\"hi\"\"\"\r\n"
                        + "line,\"one\ntwo\"\r\n"
                        + ",\r\n");
    }
}
