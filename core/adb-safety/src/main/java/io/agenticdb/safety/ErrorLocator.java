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
     * 把驱动错误归成「该往哪改」的<b>建议键</b>。
     *
     * <p>⭐ 移植自 Rust 侧的 {@code translate_constraint()}。
     * 用户看到「FOREIGN KEY constraint failed」不知道该干什么，
     * 看到「该行的外键指向一个不存在的记录」才知道。
     *
     * <p>⭐ R45 G3-b：交回的是<b>资源键</b>（空串 = 认不出来），文案与语言由界面层定
     * （渲染口同样是 {@code ErrorText}）。左边那串是大写后的<b>驱动原文探针</b>，
     * 不是文案 —— 服务器用什么语言报错就跟界面语言无关。
     */
    public static String constraintAdviceKey(String message) {
        if (message == null) {
            return "";
        }
        String m = message.toUpperCase(Locale.ROOT);

        Map<String, String> rules = new java.util.LinkedHashMap<>();
        rules.put("FOREIGN KEY CONSTRAINT FAILED", "error.rule.foreignKey");
        rules.put("UNIQUE CONSTRAINT FAILED", "error.rule.unique");
        rules.put("NOT NULL CONSTRAINT FAILED", "error.rule.notNull");
        rules.put("CHECK CONSTRAINT FAILED", "error.rule.check");
        rules.put("DATATYPE MISMATCH", "error.rule.typeMismatch");
        rules.put("READONLY", "error.rule.readOnly");

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