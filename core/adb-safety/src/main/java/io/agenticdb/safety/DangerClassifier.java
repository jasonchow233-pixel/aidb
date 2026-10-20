package io.agenticdb.safety;

import io.agenticdb.model.Danger;

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
 * <p>正确顺序：<b>先按 {@code ;} 切成多条，逐条分级，取最危险的那条。</b>
 *
 * <h2>未知语句的处理</h2>
 * 未知一律降级为 {@link Danger#READ_ONLY}（fail-open）。
 * 看起来危险，但实际有两道兜底：
 * <ol>
 *   <li>数据库自己会拒绝语法错误的语句</li>
 *   <li>驱动层会再判一次（第三层）</li>
 * </ol>
 * 反过来 fail-closed 也不行 —— 会让 {@code VALUES (1)}、{@code SHOW TABLES}
 * 这类合法只读语句全部弹 scary 的红色确认框，用户会养成闭眼点确认的习惯，
 * 那比弹窗本身更危险。
 */
public final class DangerClassifier {

    private DangerClassifier() {
    }

    private static final Set<String> READ_ONLY_HEADS = Set.of(
            "SELECT", "PRAGMA", "EXPLAIN", "WITH", "VALUES", "SHOW", "DESC", "DESCRIBE");

    private static final Set<String> MUTATING_HEADS = Set.of(
            "INSERT", "UPDATE", "REPLACE");

    private static final Set<String> ADMIN_HEADS = Set.of(
            "GRANT", "REVOKE", "ATTACH", "DETACH");

    /** 会改结构或删数据的语句。除非带 TEMP 关键字，否则一律 DESTRUCTIVE。 */
    private static final Set<String> STRUCTURAL_HEADS = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "VACUUM", "REINDEX");

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
        if (sql.indexOf(';') >= 0) {
            Danger worst = Danger.READ_ONLY;
            for (String stmt : sql.split(";")) {
                if (stmt.isBlank()) {
                    continue;
                }
                worst = worst.worse(classifySingle(stmt));
            }
            return worst;
        }
        return classifySingle(sql);
    }

    private static Danger classifySingle(String sql) {
        String head = headOf(sql);
        if (head.isEmpty()) {
            return Danger.READ_ONLY;
        }

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
            return sql.toUpperCase(Locale.ROOT).contains(" WHERE ") ? Danger.MUTATING : Danger.DESTRUCTIVE;
        }

        // 未知 → fail-open，靠数据库和驱动层兜底。见类注释
        return Danger.READ_ONLY;
    }

    /** 去掉前置注释与空白后取第一个词。 */
    private static String headOf(String sql) {
        String s = stripLeadingNoise(sql);
        int i = 0;
        while (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        return s.substring(0, i).toUpperCase(Locale.ROOT);
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