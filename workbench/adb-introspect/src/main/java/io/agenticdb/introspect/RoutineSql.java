package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 例程（函数 / 存储过程）的「可重放重建语句预览」——纯领域类，不碰连接、不碰 UI。
 *
 * <p><b>诚实边界</b>：只有确实支持 {@code CREATE OR REPLACE} 的方言才生成 SQL。
 * MySQL / MariaDB 的例程体要 {@code DELIMITER} 这类<b>客户端指令</b>，
 * JDBC 的 {@code Statement} 不认；SQLite 没有 SQL 层例程；SQL Server 用
 * {@code CREATE OR ALTER}；Oracle 的定义体来源与改写规则另有一套 ——
 * 本轮一律判为不支持，绝不拼一条可能写坏库的语句。
 *
 * <p>方言判据走持久化的 {@code id()} 判别式，而不是在领域层散落枚举分支
 * （A1 承诺：加一种库只改 Dialect 实现 + 登记表，别处不出现方言枚举比较）。
 *
 * <p><b>生成策略</b>：把库返回的<b>定义体原文</b>当作一条完整的 CREATE 块处理 ——
 * 保留函数体内部的全部分号（PL/pgSQL 必需），只规整块尾部的连续分号与空白；
 * 绝不按分号切分、绝不在块后追加第二条语句。若原文不是一条完整的
 * {@code CREATE ... FUNCTION/PROCEDURE} 定义（例如只有函数体、缺签名），
 * 一律返回空串 —— 不臆造参数与返回类型。
 */
public final class RoutineSql {

    /** 只有 PostgreSQL 一家本轮支持用单条 CREATE OR REPLACE 重建例程。 */
    private static final Set<String> OR_REPLACE_DIALECT_IDS = Set.of("postgresql");

    private static final String FUNCTION = "FUNCTION";
    private static final String PROCEDURE = "PROCEDURE";

    /** 一条 CREATE 语句的头部；第 1 组 = FUNCTION / PROCEDURE。 */
    private static final Pattern CREATE_HEAD = Pattern.compile(
            "^CREATE(?:\\s+OR\\s+REPLACE)?\\s+(FUNCTION|PROCEDURE)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 全文里出现的 CREATE 头（用来识别被塞进来的第二条语句）。 */
    private static final Pattern ANY_CREATE_HEAD = Pattern.compile(
            "CREATE(?:\\s+OR\\s+REPLACE)?\\s+(?:FUNCTION|PROCEDURE)\\b",
            Pattern.CASE_INSENSITIVE);

    private RoutineSql() {
    }

    /** 该方言是否真的支持用一条 {@code CREATE OR REPLACE} 重建例程。 */
    public static boolean supported(DialectKind kind) {
        return kind != null && OR_REPLACE_DIALECT_IDS.contains(kind.id());
    }

    /**
     * 生成重建语句预览；不支持 / 名字非法 / 定义体为空 / 定义体不是单条 CREATE 时返回空串。
     *
     * @param routineName    例程名（仅用于输入校验，不拼进 SQL —— SQL 全部来自库返回的原文）
     * @param function       true = 函数，false = 存储过程
     * @param definitionBody 库返回的定义体原文
     * @param kind           方言
     */
    public static String preview(String routineName, boolean function,
                                 String definitionBody, DialectKind kind) {
        if (!supported(kind) || !validName(routineName)
                || definitionBody == null || definitionBody.isBlank()) {
            return "";
        }
        String single = singleStatement(definitionBody);
        if (single.isEmpty()) {
            return "";
        }
        Matcher head = CREATE_HEAD.matcher(single);
        if (!head.find()) {
            return "";   // 不是完整的 CREATE 语句 —— 不臆造签名
        }
        if (countCreateHeads(single) > 1) {
            return "";   // 疑似被塞了第二条语句 —— 不产出可重放的第二条
        }
        String expected = function ? FUNCTION : PROCEDURE;
        if (!expected.equalsIgnoreCase(head.group(1))) {
            return "";   // 声明的类型与库返回的关键字不符 —— 不篡改原文
        }
        return "CREATE OR REPLACE " + expected + single.substring(head.end());
    }

    /** 去掉块尾部的连续分号与空白；函数体内部的分号原样保留，绝不按分号切分。 */
    private static String singleStatement(String body) {
        String trimmed = body.strip();
        int end = trimmed.length();
        while (end > 0) {
            char last = trimmed.charAt(end - 1);
            if (last == ';' || Character.isWhitespace(last)) {
                end--;
            } else {
                break;
            }
        }
        return trimmed.substring(0, end);
    }

    /** 名字不拼进 SQL，但含语句分隔符 / 引号 / 控制字符的输入一律拒绝。 */
    private static boolean validName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ';' || c == '\n' || c == '\r' || c == '\'' || c == '"') {
                return false;
            }
        }
        return true;
    }

    private static int countCreateHeads(String text) {
        Matcher matcher = ANY_CREATE_HEAD.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
