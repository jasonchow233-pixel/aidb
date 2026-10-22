package io.agenticdb.safety;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/**
 * ⭐ V-12：数据库错误的**归类**与可读说明。
 *
 * <h2>为什么要有</h2>
 * 驱动抛出来的错误原文对普通用户没有意义：
 * <pre>
 *   SQLITE_ERROR: no such table: users
 *   Access denied for user 'root'@'10.0.0.3'
 *   Lock wait timeout exceeded; try restarting transaction
 * </pre>
 * 用户真正想知道的是三件事：<b>发生了什么 / 是不是我的问题 / 下一步怎么办</b>。
 *
 * <p>这个类把驱动原文归成 8 类，每类给出<b>一句人话</b> + <b>一个建议动作</b>。
 * 界面拿到的不是 SQLException，而是「能照着做」的信息。
 */
public final class ErrorClassifier {

    /** 错误类别。 */
    public enum Kind {
        /** 连接断了（网络 / 服务重启）。 */
        CONNECTION_LOST,
        /** 权限不足。 */
        PERMISSION_DENIED,
        /** 对象不存在。 */
        NOT_FOUND,
        /** 超时（语句或锁等待）。 */
        TIMEOUT,
        /** 死锁 / 锁冲突。 */
        LOCK_CONFLICT,
        /** 约束冲突（唯一键 / 外键 / 非空 / CHECK）。 */
        CONSTRAINT_VIOLATION,
        /** 语法错误。 */
        SYNTAX,
        /** 其它。 */
        OTHER
    }

    /**
     * 归类结果。
     *
     * @param kind    类别
     * @param summary 一句话说明（给人看）
     * @param advice  建议动作（给人照着做）
     * @param detail  驱动原文（给排查用）
     */
    public record Diagnosis(Kind kind, String summary, String advice, String detail) {

        public Diagnosis {
            summary = summary == null ? "未知错误" : summary;
            advice = advice == null ? "" : advice;
            detail = detail == null ? "" : detail;
        }

        /**
         * 一行可读文本：**归类说明 + 驱动原文 + 建议动作**。
         *
         * <p>保留原文很重要 —— 归类给的是「方向」，原文给的是「细节」。
         */
        public String message() {
            StringBuilder text = new StringBuilder(summary);
            if (!detail.isEmpty() && !detail.equals(summary)) {
                text.append("：").append(detail);
            }
            if (!advice.isEmpty()) {
                text.append(" —— ").append(advice);
            }
            return text.toString();
        }
    }

    private ErrorClassifier() {
    }

    /**
     * 归类一个异常。
     *
     * @param error 异常（{@link SQLException} 或任何）
     */
    public static Diagnosis classify(Throwable error) {
        if (error == null) {
            return new Diagnosis(Kind.OTHER, "未知错误", "", "");
        }
        if (error instanceof SQLTimeoutException) {
            return new Diagnosis(Kind.TIMEOUT, "查询超时",
                    "缩小范围（加条件 / 分批）再试，或在连接属性里调大超时",
                    detail(error));
        }
        String text = messageOf(error);
        String lower = text.toLowerCase(Locale.ROOT);

        if (contains(lower, "lock wait timeout", "deadlock", "lock timeout")) {
            return new Diagnosis(Kind.LOCK_CONFLICT, "与其它事务有锁冲突",
                    "稍后重试；若是批量写入，考虑分批提交", detail(error));
        }
        if (contains(lower, "access denied", "permission denied", "not authorized",
                "insufficient privilege", "只读")) {
            return new Diagnosis(Kind.PERMISSION_DENIED, "权限不足",
                    "换一个有权限的账号，或让 DBA 授权", detail(error));
        }
        if (contains(lower, "no such table", "doesn't exist", "does not exist",
                "unknown table", "undefined table", "doesn't exist")) {
            return new Diagnosis(Kind.NOT_FOUND, "对象不存在",
                    "检查表名拼写与当前连接的库 / schema", detail(error));
        }
        if (contains(lower, "syntax error", "near \"", "sql syntax")) {
            return new Diagnosis(Kind.SYNTAX, "SQL 语法错误",
                    "在查询页用「格式化」看排版，或对照关键字拼写", detail(error));
        }
        if (contains(lower, "unique constraint", "duplicate entry", "unique failed",
                "primary key", "foreign key", "check constraint", "not null",
                "constraint failed")) {
            return new Diagnosis(Kind.CONSTRAINT_VIOLATION, "违反数据约束",
                    "改数据满足约束（唯一键 / 外键 / 非空 / CHECK）", detail(error));
        }
        if (contains(lower, "timed out", "timeout", "socket timeout")) {
            return new Diagnosis(Kind.TIMEOUT, "操作超时",
                    "缩小查询范围再试", detail(error));
        }
        if (contains(lower, "connection closed", "connection reset", "broken pipe",
                "communications link failure", "connection refused", "could not connect",
                "server has gone away", "connection is closed", "closed")) {
            return new Diagnosis(Kind.CONNECTION_LOST, "连接已断开",
                    "点「重新连接」；若是网络抖动，检查服务器与网络", detail(error));
        }
        return new Diagnosis(Kind.OTHER, "执行失败", "", detail(error));
    }

    private static boolean contains(String lower, String... needles) {
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String messageOf(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append(" | ");
            }
            current = current.getCause();
            depth++;
        }
        return text.toString();
    }

    private static String detail(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        return message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
