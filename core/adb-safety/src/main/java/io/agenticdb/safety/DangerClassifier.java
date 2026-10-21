package io.agenticdb.safety;

import io.agenticdb.model.Danger;
import io.agenticdb.sql.SqlLexer;
import io.agenticdb.sql.SqlSplitter;
import io.agenticdb.sql.SqlSplitter.SqlStatement;
import io.agenticdb.sql.SqlToken;
import io.agenticdb.sql.SqlTokenType;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 风险分级器 —— 三层写门禁的<b>第一层</b>。
 *
 * <p>这是全系统安全边界最敏感的一段代码。移植自 Rust 侧
 * {@code adb-drivers/src/lib.rs} 的 {@code analyze()}，
 * 保留了该文件里那条标注为 L2 安全漏洞的修复。
 *
 * <h2>⛔ 多语句必须先于单语句判定</h2>
 * 如果先按单语句 head 匹配，{@code "SELECT 1; DROP TABLE t"} 会因为第一条是
 * SELECT 而被判成 {@link Danger#READ_ONLY}，于是<b>整条语句不弹确认框直接执行</b>。
 * 这是真实的越权路径，不是理论问题。
 *
 * <p>正确顺序：<b>先切成多条，逐条分级，取最危险的那条。</b>
 *
 * <h2>切分必须用词法内核，不能用 {@code String.split(";")}</h2>
 * 裸切分会把字符串字面量里的分号也当成语句边界：
 * {@code "INSERT INTO t VALUES ('a;b')"} 会被切成两截，后半截 {@code b')} 变成
 * 「未知语句」而被误判。这里统一复用 {@link SqlSplitter} —— 它跳过字符串、
 * 注释、引用标识符与 {@code BEGIN…END} 块里的分号。
 *
 * <h2>⛔ 未知语句头必须 fail-closed</h2>
 * <b>早期版本对未知语句头一律返回 {@link Danger#READ_ONLY}（fail-open）。</b>
 * 那意味着 {@code MERGE} / {@code UPSERT} / {@code CALL} / {@code COPY} / {@code SET}
 * / {@code DO} / {@code LOCK} 这些能改数据或能执行任意代码的语句
 * <b>一个确认框都不弹，直接执行</b>。
 *
 * <p>本版改为：<b>未知语句头一律按 {@link Danger#ADMIN}（管理操作）处理</b>。
 * 「多弹一次确认」与「静默执行破坏性语句」之间，宁可选前者。
 *
 * <h2>⛔ {@code WITH} 后面跟着的可能是写语句</h2>
 * {@code WITH cte AS (SELECT 1) DELETE FROM t} 的语句头是 {@code WITH}，
 * 但它删数据。本版对 {@code WITH} 语句体内的写语句关键字做扫描，
 * 找到就按那条语句分级。
 *
 * <h2>⛔ {@code EXPLAIN ANALYZE} 会真的执行语句</h2>
 * PostgreSQL 的 {@code EXPLAIN ANALYZE} <b>会执行</b>被解释的语句，
 * 所以 {@code EXPLAIN ANALYZE DELETE FROM t} 是破坏性的。
 * 而 {@code EXPLAIN} / {@code EXPLAIN QUERY PLAN} 不执行，是安全的。
 *
 * <p>其余细节见 {@code docs/ARCHITECTURE.md} §7.0。
 */
public final class DangerClassifier {

    private DangerClassifier() {
    }

    /** 明确只读、不改任何数据也不改结构。 */
    private static final Set<String> READ_ONLY_HEADS = Set.of(
            "SELECT", "VALUES", "SHOW", "DESCRIBE", "DESC");

    /** 改数据或改会话状态，但可回滚。 */
    private static final Set<String> MUTATING_HEADS = Set.of(
            "INSERT", "UPDATE", "REPLACE", "MERGE", "UPSERT",
            "SET", "RESET", "USE", "START", "BEGIN", "COMMIT", "ROLLBACK",
            "SAVEPOINT", "RELEASE", "LOCK", "ANALYZE", "COPY");

    /** 改权限 / 挂载库 / 执行任意代码。 */
    private static final Set<String> ADMIN_HEADS = Set.of(
            "GRANT", "REVOKE", "ATTACH", "DETACH", "CALL", "DO");

    /** 会改结构或删数据的语句。除非带 TEMP 关键字，否则一律 DESTRUCTIVE。 */
    private static final Set<String> STRUCTURAL_HEADS = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "VACUUM", "REINDEX");

    /**
     * {@code WITH} 语句体里出现这些关键字就说明里面藏着写语句。
     * 只扫 {@link SqlTokenType#KEYWORD} token —— 表名 / 列名是 IDENTIFIER，
     * 字符串字面量是 STRING，都不会命中。
     */
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
            "GRANT", "REVOKE", "CALL", "DO", "COPY", "SET", "LOCK");

    /**
     * 分级入口。
     *
     * @param sql 可能为空、可能带注释、可能有多条语句
     * @return 最危险的那条语句的等级；空输入返回 {@link Danger#READ_ONLY}
     */
    public static Danger classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return Danger.READ_ONLY;
        }

        // ⭐ 顺序不可调换。多语句优先，否则 "SELECT 1; DROP TABLE t" 越权。
        // ⭐ 用 SqlSplitter 而不是 split(";")：字符串里的分号不能切碎语句。
        List<SqlStatement> statements = SqlSplitter.split(sql);
        if (statements.isEmpty()) {
            return Danger.READ_ONLY;
        }
        Danger worst = Danger.READ_ONLY;
        for (SqlStatement statement : statements) {
            worst = worst.worse(classifySingle(statement.text()));
        }
        return worst;
    }

    private static Danger classifySingle(String sql) {
        String head = headOf(sql);
        if (head.isEmpty()) {
            return Danger.READ_ONLY;
        }

        // ── 需要特殊处理的语句头 ─────────────────────────────────────
        if ("EXPLAIN".equals(head)) {
            return explainDanger(sql);
        }
        if ("WITH".equals(head)) {
            return withDanger(sql);
        }
        if ("PRAGMA".equals(head)) {
            return pragmaDanger(sql);
        }

        // ── 明确知道的头 ──────────────────────────────────────────────
        if (READ_ONLY_HEADS.contains(head)) {
            return Danger.READ_ONLY;
        }
        if (MUTATING_HEADS.contains(head)) {
            return Danger.MUTATING;
        }
        if (ADMIN_HEADS.contains(head)) {
            return Danger.ADMIN;
        }
        if (STRUCTURAL_HEADS.contains(head)) {
            // CREATE TEMP TABLE 是可回滚的，不该弹「改结构」的红色确认
            if (isTemporary(sql)) {
                return Danger.MUTATING;
            }
            return Danger.DESTRUCTIVE;
        }

        // ⭐ DELETE 无 WHERE 是全表删除 —— 必须单独判，不能和 UPDATE 混为一谈
        if ("DELETE".equals(head)) {
            return hasWhere(sql) ? Danger.MUTATING : Danger.DESTRUCTIVE;
        }

        // ⭐⭐ fail-closed：未知语句头按「管理操作」处理。
        //    宁可多弹一次确认，也不允许静默执行一个我们没认识的语句。
        return Danger.ADMIN;
    }

    /**
     * {@code EXPLAIN} 系列。
     *
     * <p>{@code EXPLAIN ANALYZE} <b>会执行</b>被解释的语句（PostgreSQL），
     * 所以按「去掉 EXPLAIN ANALYZE 之后的剩余部分」重新分级。
     * 其余 {@code EXPLAIN} / {@code EXPLAIN QUERY PLAN} 只解释不执行，是只读的。
     */
    private static Danger explainDanger(String sql) {
        String rest = stripLeadingNoise(sql);
        rest = stripWord(rest, "EXPLAIN");
        String word = firstWord(rest);
        if ("ANALYZE".equals(word)) {
            String tail = stripWord(stripLeadingNoise(rest), "ANALYZE");
            if (tail.isBlank()) {
                return Danger.MUTATING;
            }
            return classifySingle(tail);
        }
        return Danger.READ_ONLY;
    }

    /**
     * {@code WITH} 语句。
     *
     * <p>语句头是 {@code WITH}，但体内可能是 {@code DELETE} / {@code UPDATE}。
     * 扫一遍关键字，命中写语句就按那条语句分级。
     */
    private static Danger withDanger(String sql) {
        Danger worst = Danger.READ_ONLY;
        for (SqlToken token : SqlLexer.tokenize(sql)) {
            if (token.type() != SqlTokenType.KEYWORD) {
                continue;
            }
            String word = token.text().toUpperCase(Locale.ROOT);
            if (!WRITE_KEYWORDS.contains(word)) {
                continue;
            }
            worst = worst.worse(keywordDanger(word, sql));
        }
        return worst;
    }

    /**
     * {@code PRAGMA} 语句。
     *
     * <p>{@code PRAGMA table_info(t)} 是读；{@code PRAGMA journal_mode = WAL}
     * 会改库的持久化设置，不能当只读放过。
     */
    private static Danger pragmaDanger(String sql) {
        List<SqlToken> tokens = SqlLexer.tokenize(sql);
        boolean seenName = false;
        for (SqlToken token : tokens) {
            if (token.type() == SqlTokenType.WHITESPACE || token.type() == SqlTokenType.COMMENT) {
                continue;
            }
            if (token.type() == SqlTokenType.KEYWORD && "PRAGMA".equalsIgnoreCase(token.text())) {
                continue;
            }
            if (!seenName) {
                seenName = true;
                continue;
            }
            // 名字后面紧跟 "=" 就是赋值型 PRAGMA。
            if ("=".equals(token.text())) {
                return Danger.MUTATING;
            }
            break;
        }
        return Danger.READ_ONLY;
    }

    /** 已知写关键字对应的等级。 */
    private static Danger keywordDanger(String keyword, String sql) {
        return switch (keyword) {
            case "DELETE" -> hasWhere(sql) ? Danger.MUTATING : Danger.DESTRUCTIVE;
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME" ->
                    isTemporary(sql) ? Danger.MUTATING : Danger.DESTRUCTIVE;
            case "GRANT", "REVOKE", "CALL", "DO" -> Danger.ADMIN;
            default -> Danger.MUTATING;
        };
    }

    /** 去掉前置注释与空白后取第一个词。 */
    private static String headOf(String sql) {
        return firstWord(stripLeadingNoise(sql));
    }

    /** 取第一个「字母或下划线」开头的词；没有则返回空串。 */
    private static String firstWord(String sql) {
        String s = stripLeadingNoise(sql);
        int i = 0;
        while (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        return s.substring(0, i).toUpperCase(Locale.ROOT);
    }

    /** 去掉开头的一个词（大小写不敏感）与它后面的空白。 */
    private static String stripWord(String sql, String word) {
        String s = stripLeadingNoise(sql);
        if (!s.toUpperCase(Locale.ROOT).startsWith(word)) {
            return s;
        }
        return s.substring(word.length()).stripLeading();
    }

    /**
     * 语句里有没有 {@code WHERE} 子句。
     *
     * <p>用词法内核判定而不是 {@code contains(" WHERE ")}：后者会被注释和字符串骗到。
     */
    private static boolean hasWhere(String sql) {
        for (SqlToken token : SqlLexer.tokenize(sql)) {
            if (token.type() == SqlTokenType.KEYWORD && "WHERE".equalsIgnoreCase(token.text())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥掉开头的 {@code --} 行注释与 {@code /* *}{@code /} 块注释。
     * 不处理字符串字面量内部的 {@code --}，那属于过度设计：
     * 分级器只是给出提示，最终权威是数据库。
     */
    private static String stripLeadingNoise(String sql) {
        String s = sql.stripLeading();
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                s = nl < 0 ? "" : s.substring(nl + 1).stripLeading();
                progressed = true;
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end < 0 ? "" : s.substring(end + 2).stripLeading();
                progressed = true;
            }
        }
        return s;
    }

    private static boolean isTemporary(String sql) {
        String up = sql.toUpperCase(Locale.ROOT);
        return up.contains(" TEMP ") || up.contains(" TEMPORARY ") || up.endsWith(" TEMP");
    }
}
