package io.agenticdb.ui.shell;

import java.util.List;

/** 最小 CSV 写出器：RFC 4180 引号/换行转义，不引入额外依赖。 */
final class CsvExporter {

    private CsvExporter() {
    }

    static String csv(List<String> headers, List<List<String>> rows) {
        StringBuilder text = new StringBuilder();
        appendRow(text, headers);
        for (List<String> row : rows) {
            appendRow(text, row);
        }
        return text.toString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return quote ? '"' + escaped + '"' : escaped;
    }

    private static void appendRow(StringBuilder text, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(escape(values.get(i)));
        }
        text.append("\r\n");
    }
}
