package io.agenticdb.ui.shell;

import io.agenticdb.editing.EditValues;
import io.agenticdb.ui.I18n;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;

import java.util.Locale;

/**
 * 把用户输入转成与列类型匹配的绑定值。
 *
 * <p>⭐ R55：空的输入<b>不再</b>是空串 —— 它走 {@link EditValues#emptiedCell}，和
 * 「用户把一格删干净」同一条规则（文本档 {@code ""}，其余档 {@code null}）。
 * 之前这里自己返回 {@code ""}，于是列头值筛选那只「精确值」框里输入一个空格就能把
 * {@code ""} 绑进 {@code DECIMAL} 列 —— 那正是 R53 在真库里量出来的形状
 * （四家服务端整批拒，SQL Server 的 {@code BIT} 静默折成 0）。
 * 规则只有一处，界面这边再也不能单方面造它。
 */
final class CellValueParser {

    private CellValueParser() {
    }

    static Object parse(ColumnDef column, String raw) {
        String text = raw == null ? "" : raw;
        if (text.isEmpty()) {
            return EditValues.emptiedCell(column);
        }
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
            default -> throw new IllegalArgumentException(I18n.t("parse.notBoolean", text));
        };
    }
}
