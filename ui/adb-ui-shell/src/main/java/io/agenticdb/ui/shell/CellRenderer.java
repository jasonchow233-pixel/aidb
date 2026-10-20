package io.agenticdb.ui.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单元格值 → 展示文本。所有渲染规则集中在这里，避免每个 Cell 各写一套。
 *
 * <p>规则：NULL 与空串分开；BLOB 显示大小；JSON 显示结构摘要；
 * 枚举显示声明里的 label；日期时间按列类型格式化；长文本压成单行摘要。
 */
final class CellRenderer {

    static final int TEXT_LIMIT = 80;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<DateTimeFormatter> DATE_TIME_INPUTS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));

    record Rendered(String text, String styleClass, String tooltip, boolean truncated) {
        static Rendered plain(String text) {
            return new Rendered(text, "", null, false);
        }
    }

    private CellRenderer() {
    }

    static Rendered render(ColumnDef definition, Object value) {
        if (value == null) {
            return new Rendered("NULL", "adb-grid__cell--null", "NULL", false);
        }
        if (value instanceof String text && text.isEmpty()) {
            return new Rendered("\"\"", "adb-grid__cell--empty", "空串（不是 NULL）", false);
        }
        if (value instanceof byte[] bytes) {
            return new Rendered("⬢ " + sizeLabel(bytes.length), "adb-grid__cell--binary",
                    "BLOB · " + bytes.length + " 字节 · 双击查看详情", false);
        }
        if (value instanceof Boolean bool) {
            return new Rendered(bool ? "✓" : "✗", "adb-grid__cell--boolean",
                    bool ? "true" : "false", false);
        }
        if (definition.type() == ColumnType.BOOLEAN) {
            Boolean bool = booleanValue(value);
            if (bool != null) {
                return new Rendered(bool ? "✓" : "✗", "adb-grid__cell--boolean",
                        String.valueOf(value), false);
            }
        }
        if (definition.isEnum()) {
            String label = enumLabel(definition, value);
            return new Rendered(label, "adb-grid__cell--enum",
                    label.equals(String.valueOf(value)) ? "枚举值 " + value
                            : value + " → " + label, false);
        }
        if (definition.type() == ColumnType.NUMBER || value instanceof Number) {
            String text = numberText(value);
            return new Rendered(text, "adb-grid__cell--number", text, false);
        }
        if (definition.type() == ColumnType.DATE
                || definition.type() == ColumnType.TIME
                || definition.type() == ColumnType.TIMESTAMP) {
            String text = formatTemporal(definition.type(), value);
            return new Rendered(text, "adb-grid__cell--date", String.valueOf(value), false);
        }
        if (definition.type() == ColumnType.JSON || looksLikeJson(value)) {
            String summary = jsonSummary(value);
            String full = jsonText(value);
            return new Rendered(summary, "adb-grid__cell--json", full,
                    !summary.equals(full));
        }

        String full = String.valueOf(value);
        String summary = compact(full);
        boolean truncated = summary.length() < full.length()
                || full.contains("\n") || full.contains("\r");
        if (summary.length() > TEXT_LIMIT) {
            summary = summary.substring(0, TEXT_LIMIT - 1) + "…";
            truncated = true;
        }
        return new Rendered(summary, truncated ? "adb-grid__cell--long-text" : "",
                truncated ? full : null, truncated);
    }

    static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "1", "true", "yes", "y", "on", "是", "真" -> true;
                case "0", "false", "no", "n", "off", "否", "假" -> false;
                default -> null;
            };
        }
        return null;
    }

    static boolean isNullish(Object value) {
        return value == null || (value instanceof String text && text.isEmpty());
    }

    static String sizeLabel(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        if (value < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", value);
        }
        value /= 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", value);
    }

    static String compact(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return value.replaceAll("\\s{2,}", " ").trim();
    }

    static String jsonText(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (Exception ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    static String prettyJson(Object value) {
        String raw = jsonText(value);
        try {
            JsonNode node = JSON.readTree(raw);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ignored) {
            return raw;
        }
    }

    static String hexDump(Object value) {
        byte[] bytes = value instanceof byte[] data
                ? data : String.valueOf(value == null ? "" : value)
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return "(空)";
        }
        StringBuilder text = new StringBuilder(bytes.length * 4);
        for (int offset = 0; offset < bytes.length; offset += 16) {
            if (offset > 0) {
                text.append('\n');
            }
            text.append(String.format(Locale.ROOT, "%08X  ", offset));
            for (int i = 0; i < 16; i++) {
                if (i == 8) {
                    text.append(' ');
                }
                if (offset + i < bytes.length) {
                    text.append(String.format(Locale.ROOT, "%02X ", bytes[offset + i] & 0xFF));
                } else {
                    text.append("   ");
                }
            }
            text.append(" |");
            for (int i = 0; i < 16 && offset + i < bytes.length; i++) {
                int valueByte = bytes[offset + i] & 0xFF;
                text.append(valueByte >= 32 && valueByte < 127
                        ? (char) valueByte : '.');
            }
            text.append('|');
        }
        return text.toString();
    }

    private static String numberText(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    private static String enumLabel(ColumnDef definition, Object value) {
        String raw = String.valueOf(value);
        String declared = definition.enumValues();
        if (declared == null || declared.isBlank()) {
            return raw;
        }
        for (String item : declared.split("[,;]")) {
            int separator = item.indexOf('=');
            if (separator < 0) {
                separator = item.indexOf(':');
            }
            if (separator <= 0 || separator == item.length() - 1) {
                continue;
            }
            String key = item.substring(0, separator).trim();
            String label = item.substring(separator + 1).trim();
            if (key.equals(raw)) {
                return label;
            }
        }
        return raw;
    }

    private static boolean looksLikeJson(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private static String jsonSummary(Object value) {
        String raw = jsonText(value);
        try {
            JsonNode node = JSON.readTree(raw);
            if (node.isObject()) {
                return "{…} " + node.size() + " 字段 · " + truncate(JSON.writeValueAsString(node));
            }
            if (node.isArray()) {
                return "[…] " + node.size() + " 项 · " + truncate(JSON.writeValueAsString(node));
            }
            return truncate(JSON.writeValueAsString(node));
        } catch (Exception ignored) {
            return truncate(compact(raw));
        }
    }

    private static String truncate(String text) {
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT - 1) + "…";
    }

    private static String formatTemporal(ColumnType type, Object value) {
        if (type == ColumnType.DATE) {
            LocalDate date = toLocalDate(value);
            return date == null ? String.valueOf(value) : DATE.format(date);
        }
        if (type == ColumnType.TIME) {
            LocalTime time = toLocalTime(value);
            return time == null ? String.valueOf(value) : TIME.format(time);
        }
        LocalDateTime timestamp = toLocalDateTime(value);
        return timestamp == null ? String.valueOf(value) : TIMESTAMP.format(timestamp);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        String text = String.valueOf(value);
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            // try datetime below
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toLocalDate();
        } catch (DateTimeParseException ignored) {
            // try slash format
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalTime toLocalTime(Object value) {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof Time time) {
            return time.toLocalTime();
        }
        String text = String.valueOf(value);
        try {
            return LocalTime.parse(text, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toLocalDateTime();
        }
        if (value instanceof ZonedDateTime dateTime) {
            return dateTime.toLocalDateTime();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().atStartOfDay();
        }
        String text = String.valueOf(value);
        for (DateTimeFormatter formatter : DATE_TIME_INPUTS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
