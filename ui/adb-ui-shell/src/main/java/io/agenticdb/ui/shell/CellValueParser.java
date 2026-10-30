package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;

import java.util.Locale;

/** 把用户输入转成与列类型匹配的绑定值。空串始终是空串，不会变成 NULL。 */
final class CellValueParser {

    private CellValueParser() {
    }

    static Object parse(ColumnDef column, String raw) {
        String text = raw == null ? "" : raw;
        return switch (column.type()) {
            case NUMBER -> parseNumber(text);
            case BOOLEAN -> parseBoolean(text);
            case DATE, TIMESTAMP, TIME -> text;
            default -> text;
        };
    }

    static String display(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String text && text.isEmpty()) {
            return I18n.t("parse.emptyStringLabel");
        }
        return String.valueOf(value);
    }

    private static Object parseNumber(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            return Long.valueOf(trimmed);
        } catch (NumberFormatException ignored) {
            // 小数、科学计数法继续尝试 Double
        }
        try {
            return Double.valueOf(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(I18n.t("parse.notNumber", text));
        }
    }

    private static Object parseBoolean(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "1", "true", "yes", "y", "on", "是", "真" -> true;
            case "0", "false", "no", "n", "off", "否", "假" -> false;
            case "" -> "";
            default -> throw new IllegalArgumentException(I18n.t("parse.notBoolean", text));
        };
    }
}
