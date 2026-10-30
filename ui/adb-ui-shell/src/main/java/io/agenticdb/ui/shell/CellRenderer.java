package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticdb.model.ByteSizes;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * 浮点**展示**的最大小数位数，见 {@link #doubleText(double)}。
     * 12 远超业务精度（金额最多 6 位），又远低于噪声出现的位次（第 15 位后）。
     */
    static final int MAX_FRACTION_DIGITS = 12;

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

    /**
     * 一格渲染结果。
     *
     * <p>⭐ U9（2026-10-26）：{@code icon} 非空表示这一格用**图标**表达，
     * {@code text} 退成它的纯文本读法（{@code true} / {@code false}）。
     * 以前布尔渲染成文本「✓ / ✗」，于是同一个 boolean 值在可编辑网格里是
     * 实心勾选框、在查询结果与结构页里是一个对勾 —— 两套语法，
     * 而且文本 ✓ 不吃主题、不吃字号、还和「内容就是 ✓ 的文本」撞车。
     * 现在：只读态一律图标，可编辑态才出现 checkbox。
     * 宽度测量与转置视图继续读 {@code text}（那里要的是值，不是字形）。
     */
    record Rendered(String text, String styleClass, String tooltip, boolean truncated,
                    io.agenticdb.ui.Icons.Icon icon) {

        Rendered(String text, String styleClass, String tooltip, boolean truncated) {
            this(text, styleClass, tooltip, truncated, null);
        }

        static Rendered plain(String text) {
            return new Rendered(text, "", null, false);
        }

        /** U9：只读布尔 —— 图标 + tooltip 给真值。 */
        static Rendered bool(boolean value, String tooltip) {
            return new Rendered(value ? "true" : "false", "adb-grid__cell--boolean",
                    tooltip, false,
                    value ? io.agenticdb.ui.Icons.Icon.CHECK : io.agenticdb.ui.Icons.Icon.CLOSE);
        }
    }

    /**
     * U9：布尔 / 标记格共用的图标节点。颜色跟着字形走 ——
     * ✓ 上成功色，✗ 用禁用灰（数据值本身就是 false，不是错误）。
     */
    static javafx.scene.shape.SVGPath checkGlyph(io.agenticdb.ui.Icons.Icon icon) {
        javafx.scene.shape.SVGPath glyph =
                io.agenticdb.ui.Icons.of(icon, io.agenticdb.ui.Icons.SIZE_BADGE);
        glyph.getStyleClass().add("adb-check");
        if (icon != io.agenticdb.ui.Icons.Icon.CHECK) {
            glyph.getStyleClass().add("adb-check--off");
        }
        return glyph;
    }

    private CellRenderer() {
    }

    static Rendered render(ColumnDef definition, Object value) {
        // ⭐ UX-4：NULL 显示成 **(NULL)**，不是 NULL。
        //   带括号是因为 TEXT 列里真的会出现字符串 "NULL"（导入 CSV、日志表、外部系统写的）——
        //   灰斜体的 "NULL" 和一条内容为 NULL 的文本在 12px 网格里根本分不出来，
        //   而这两种情况要做的处理完全不同。空串同理显示成 ""，三种状态各有各的字形。
        if (value == null) {
            return new Rendered("(NULL)", "adb-grid__cell--null",
                    I18n.t("cell.nullHint"), false);
        }
        if (value instanceof String text && text.isEmpty()) {
            return new Rendered("\"\"", "adb-grid__cell--empty", I18n.t("cell.emptyStringHint"), false);
        }
        if (value instanceof byte[] bytes) {
            return new Rendered("BLOB · " + sizeLabel(bytes.length), "adb-grid__cell--binary",
                    I18n.t("cell.blob", bytes.length), false);
        }
        if (value instanceof Boolean bool) {
            return Rendered.bool(bool, bool ? "true" : "false");
        }
        if (definition.type() == ColumnType.BOOLEAN) {
            Boolean bool = booleanValue(value);
            if (bool != null) {
                return Rendered.bool(bool, String.valueOf(value));
            }
        }
        if (definition.isEnum()) {
            String label = enumLabel(definition, value);
            return new Rendered(label, "adb-grid__cell--enum",
                    label.equals(String.valueOf(value)) ? I18n.t("cell.enumValue", value)
                            : value + " → " + label, false);
        }
        if (definition.type() == ColumnType.NUMBER || value instanceof Number) {
            String text = numberText(value);
            String raw = String.valueOf(value);
            // 展示值被收敛过时（浮点噪声），tooltip 给出原值 ——
            // 显示可以更干净，但不能瞒着用户真实值是什么。
            return new Rendered(text, "adb-grid__cell--number",
                    text.equals(raw) ? text : I18n.t("cell.raw", raw), false);
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

    /** ⭐ 与表清单的 {@code TableMeta.sizeText} 共用 {@link ByteSizes} —— 不再各写一份。 */
    static String sizeLabel(long bytes) {
        return ByteSizes.format(bytes);
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
            return I18n.t("cell.empty");
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

    static String numberText(Object value) {
        // BigDecimal 保留声明的 scale —— DECIMAL(10,2) 的 100 就该显示 "100.00"，
        // 那是业务精度，不是噪声，stripTrailingZeros 会把它抹掉。
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            return doubleText(((Number) value).doubleValue());
        }
        return String.valueOf(value);
    }

    /**
     * IEEE754 浮点数的展示文本 —— **只影响显示，永远不改值**。
     *
     * <h2>为什么需要它</h2>
     * {@code REAL / DOUBLE / FLOAT} 列读出来是 {@code double}，直接
     * {@code String.valueOf(double)} 会把二进制无法精确表示的尾数整段暴露出来，
     * 金额列显示成 {@code 22.990000000000002}。用户第一反应不是"浮点精度"，
     * 而是"这工具把我的数据显示坏了"。
     *
     * <h2>关键事实（实测，别想当然）</h2>
     * {@code 22.990000000000002 == 22.99} 是 **false** —— 它们是两个不同的 double。
     * 所以这不是"最短往返表示"能解决的：{@code Double.toString} 已经是最短表示了，
     * 它如实输出 22.990000000000002。
     * 真正要做的，是把**超出业务精度的小数位**收敛掉。
     *
     * <h2>收敛规则</h2>
     * double 只有约 15–17 位有效十进制数字。业务数据（金额 / 评分 / 比率）的小数位
     * 从不超过 6 位；噪声恒定出现在第 15 位之后。取
     * {@value #MAX_FRACTION_DIGITS} 位作分界：
     * <ul>
     *   <li>小数位 ≤ 12 → **原样输出**，真实精度一个字都不动。</li>
     *   <li>小数位 > 12 → 四舍五入到 12 位并去掉尾随零。
     *       {@code 22.990000000000002 → 22.99}，{@code 0.1+0.2 → 0.3}。</li>
     * </ul>
     *
     * <h2>⚠ 边界</h2>
     * 极小值（如 {@code 1e-20}）收敛到 12 位会变成 {@code 0} —— 那是**丢数据**。
     * 所以收敛结果为 0 而原值非 0 时，退回原样输出。
     * {@code NaN / ±Infinity} 无十进制表示，直接原样。
     *
     * <p>本方法**只**服务于展示。编辑回填走 {@code String.valueOf}、
     * 导出走 {@code csvValue}，两者都不经过这里 —— 因此格式化字符串
     * **不可能**被写回数据库（有 {@code NumberFormattingTest} 守着）。
     */
    static String doubleText(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);          // NaN / ±Infinity
        }
        if (value == 0.0d) {
            return "0";                            // 顺带消掉 "-0.0"
        }
        // BigDecimal.valueOf(double) ≡ new BigDecimal(Double.toString(v))：
        // 拿到的是 double 的**精确**十进制展开，不再引入新的舍入误差。
        BigDecimal exact = BigDecimal.valueOf(value);
        if (exact.scale() <= MAX_FRACTION_DIGITS) {
            return exact.stripTrailingZeros().toPlainString();
        }
        BigDecimal rounded = exact.setScale(MAX_FRACTION_DIGITS, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        if (rounded.signum() == 0) {
            return exact.toPlainString();          // 极小值不能被收敛成 0
        }
        return rounded.toPlainString();
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
                return I18n.t("cell.jsonObject", node.size(), truncate(JSON.writeValueAsString(node)));
            }
            if (node.isArray()) {
                return I18n.t("cell.jsonArray", node.size(), truncate(JSON.writeValueAsString(node)));
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
