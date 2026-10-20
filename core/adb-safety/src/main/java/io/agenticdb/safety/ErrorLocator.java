package io.agenticdb.safety;

import io.agenticdb.model.Danger;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * SQL 错误定位 —— 把数据库的报错变成「跳到第几行第几列」。
 *
 * <p>移植自 Rust 侧 {@code locate()}，覆盖三种厂商消息形态。
 */
public final class ErrorLocator {

    private ErrorLocator() {
    }

    /**
     * 定位结果。
     *
     * @param line   1 起的行号
     * @param column 1 起的列号
     */
    public record Position(int line, int column) {
    }

    /**
     * 从 SQLException 消息里解析出位置。
     *
     * @param message 驱动抛出的原始消息
     * @param sql     触发这条错误的 SQL，用于把 offset 换算成行列
     * @return 解析不出时返回 empty，<b>绝不返回 (0,0) 这类假位置</b>
     */
    public static Optional<Position> locate(String message, String sql) {
        if (message == null || message.isBlank() || sql == null) {
            return Optional.empty();
        }

        Optional<Position> pg = matchLineColumn(message, "line (\\d+), column (\\d+)");
        if (pg.isPresent()) {
            return pg;
        }
        Optional<Position> oracle = matchLineColumn(message, "line:(\\d+) column:(\\d+)");
        if (oracle.isPresent()) {
            return oracle;
        }
        // MySQL: "at line 12 near 'xxx'" —— 只有行号，列号算 1
        var mysql = java.util.regex.Pattern.compile("at line (\\d+)").matcher(message);
        if (mysql.find()) {
            return Optional.of(new Position(Integer.parseInt(mysql.group(1)), 1));
        }
        return Optional.empty();
    }

    private static Optional<Position> matchLineColumn(String message, String regex) {
        var m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new Position(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2))));
    }

    /**
     * 编辑器里的 offset → 行列。
     *
     * @param offset 0 起的字符偏移
     * @return 行列，均从 1 开始
     */
    public static Position positionOf(String sql, int offset) {
        int clamped = Math.max(0, Math.min(offset, sql.length()));
        int line = 1;
        int lastNl = -1;
        for (int i = 0; i < clamped; i++) {
            if (sql.charAt(i) == '\n') {
                line++;
                lastNl = i;
            }
        }
        return new Position(line, clamped - lastNl);
    }

    /**
     * 把驱动错误翻译成人话 + 建议动作。
     *
     * <p>⭐ 移植自 Rust 侧的 {@code translate_constraint()}。
     * 用户看到「FOREIGN KEY constraint failed」不知道该干什么，
     * 看到「该行的外键指向一个不存在的记录」才知道。
     */
    public static String explainConstraintViolation(String message) {
        if (message == null) {
            return "";
        }
        String m = message.toUpperCase(Locale.ROOT);

        Map<String, String> rules = new java.util.LinkedHashMap<>();
        rules.put("FOREIGN KEY CONSTRAINT FAILED", "外键指向的记录不存在。请先插入被引用的行，或检查外键值是否写错");
        rules.put("UNIQUE CONSTRAINT FAILED", "违反唯一约束。该值已存在，请换一个");
        rules.put("NOT NULL CONSTRAINT FAILED", "该字段不允许为空");
        rules.put("CHECK CONSTRAINT FAILED", "值不满足该列的 CHECK 约束");
        rules.put("DATATYPE MISMATCH", "类型不匹配，请检查赋值的类型");
        rules.put("READONLY", "该字段是只读的（自增或生成列），不能手工修改");

        for (Map.Entry<String, String> e : rules.entrySet()) {
            if (m.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return "";
    }

    /** 编辑器「应用建议改写」时用：把用户输入改成安全的 NULL。 */
    public static Danger dangerOfSuggestion(String sql) {
        return DangerClassifier.classify(sql);
    }
}