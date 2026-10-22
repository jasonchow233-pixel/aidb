package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 {@code CREATE TABLE} 原文里抽出 CHECK 约束。
 *
 * <h2>为什么需要它</h2>
 * SQLite **没有** 能读出 CHECK 约束的 PRAGMA，也没有 {@code information_schema}；
 * 约束只存在于 {@code sqlite_master.sql} 的原文里。所以：
 * <ul>
 *   <li>内省阶段：从 DDL 原文里解析出 CHECK（这样结构页能显示、改列时能搬运）；</li>
 *   <li>重建阶段：把解析出来的 CHECK 原样写回新的 {@code CREATE TABLE}。</li>
 * </ul>
 *
 * <h2>解析规则</h2>
 * <ol>
 *   <li>只认**表级**约束（{@code CHECK (...)}）与**列级**约束
 *       （{@code col INTEGER CHECK (col > 0)}）—— 两者都要，否则会漏；</li>
 *   <li>可选的 {@code CONSTRAINT <名字>} 会被记成约束名；</li>
 *   <li>括号配对要**跳过字符串字面量**（{@code 'a)b'} 里那个括号不是结束括号）；</li>
 *   <li>关键字匹配必须是**单词边界**（{@code CHECKED} 不是 {@code CHECK}）。</li>
 * </ol>
 */
public final class CheckParser {

    private CheckParser() {
    }

    /**
     * 从 CREATE TABLE 原文里抽出 CHECK 约束。
     *
     * @param ddl 建表语句原文；为空或不是建表语句时返回空列表
     */
    public static List<CheckDef> extract(String ddl) {
        List<CheckDef> checks = new ArrayList<>();
        if (ddl == null || ddl.isBlank()) {
            return checks;
        }
        int index = 0;
        int length = ddl.length();
        while (index < length) {
            char current = ddl.charAt(index);
            if (current == '\'') {
                index = skipLiteral(ddl, index, '\'');
                continue;
            }
            if (current == '"' || current == '`') {
                index = skipQuoted(ddl, index, current);
                continue;
            }
            if (isWordStart(current) && matchesKeyword(ddl, index, "CHECK")) {
                int open = ddl.indexOf('(', index);
                if (open < 0) {
                    break;
                }
                int close = matchingParen(ddl, open);
                if (close < 0) {
                    break;
                }
                String expression = ddl.substring(open + 1, close).trim();
                checks.add(new CheckDef(constraintNameBefore(ddl, index), expression));
                index = close + 1;
                continue;
            }
            index++;
        }
        return checks;
    }

    /** CHECK 之前最近的 {@code CONSTRAINT <名字>}（没有则空串）。 */
    private static String constraintNameBefore(String ddl, int checkIndex) {
        // 从 CHECK 往前找最近的一个 "CONSTRAINT" 关键字，它后面到 CHECK 之前是名字。
        int cursor = checkIndex - 1;
        while (cursor >= 0 && Character.isWhitespace(ddl.charAt(cursor))) {
            cursor--;
        }
        int nameEnd = cursor;
        while (cursor >= 0 && !Character.isWhitespace(ddl.charAt(cursor))
                && ddl.charAt(cursor) != '(' && ddl.charAt(cursor) != ',') {
            cursor--;
        }
        String candidate = unquote(ddl.substring(cursor + 1, nameEnd + 1).trim());
        if (candidate.isEmpty()) {
            return "";
        }
        // 往前看一个词：如果是 CONSTRAINT，候选就是约束名；否则它只是列名（列级约束）。
        int wordEnd = cursor;
        while (cursor >= 0 && Character.isWhitespace(ddl.charAt(cursor))) {
            cursor--;
        }
        int wordStart = cursor;
        while (cursor >= 0 && isWordPart(ddl.charAt(cursor))) {
            cursor--;
        }
        String word = ddl.substring(cursor + 1, wordStart + 1).toUpperCase(Locale.ROOT);
        return "CONSTRAINT".equals(word) ? candidate : "";
    }

    /**
     * 去掉标识符外面那层引号。
     *
     * <p>重建出来的 DDL 里约束名是带引号的（{@code CONSTRAINT "age_positive" CHECK …}），
     * SQLite 会**原样保存**这段文本 —— 不去掉引号，内省出来的名字就变成
     * {@code "age_positive"}（带引号），与重建前对不上。
     */
    private static String unquote(String identifier) {
        if (identifier.length() >= 2) {
            char first = identifier.charAt(0);
            char last = identifier.charAt(identifier.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                return identifier.substring(1, identifier.length() - 1);
            }
        }
        return identifier;
    }

    /** 找到与 open 位置的 {@code (} 配对的 {@code )}；找不到返回 -1。 */
    private static int matchingParen(String text, int open) {
        int depth = 0;
        int index = open;
        int length = text.length();
        while (index < length) {
            char current = text.charAt(index);
            if (current == '\'') {
                index = skipLiteral(text, index, '\'');
                continue;
            }
            if (current == '"' || current == '`') {
                index = skipQuoted(text, index, current);
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
            index++;
        }
        return -1;
    }

    /** 跳过单引号字符串（含 {@code ''} 转义）。 */
    private static int skipLiteral(String text, int start, char quote) {
        int index = start + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == quote) {
                if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return text.length();
    }

    /** 跳过双引号 / 反引号标识符。 */
    private static int skipQuoted(String text, int start, char quote) {
        int index = start + 1;
        while (index < text.length()) {
            if (text.charAt(index) == quote) {
                return index + 1;
            }
            index++;
        }
        return text.length();
    }

    /** 从 position 开始是否是关键字（单词边界内，大小写不敏感）。 */
    private static boolean matchesKeyword(String text, int position, String keyword) {
        if (position + keyword.length() > text.length()) {
            return false;
        }
        if (!text.regionMatches(true, position, keyword, 0, keyword.length())) {
            return false;
        }
        int after = position + keyword.length();
        if (after < text.length() && isWordPart(text.charAt(after))) {
            return false;
        }
        int before = position - 1;
        return before < 0 || !isWordPart(text.charAt(before));
    }

    private static boolean isWordStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isWordPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }
}
