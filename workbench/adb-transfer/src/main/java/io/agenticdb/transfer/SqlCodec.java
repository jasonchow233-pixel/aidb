package io.agenticdb.transfer;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * ⭐ H4-a：INSERT 语句读取器 —— 与 {@link TableExporter} 的 SQL 导出同形。
 *
 * <p>首条 INSERT 的列清单就是表头；后续语句按各自的列清单对到表头
 * （SQL 标识符大小写不敏感），缺的落 {@code null}，多余的忽略。文件里
 * {@code INSERT INTO} 的表名不读 —— 目标表由导入方决定。字面量与导出侧的
 * {@code sqlLiteral} 互逆：{@code NULL} → null、{@code ''} 翻倍还原成单引号、
 * 数字与 true/false 原文、其余标识符原文（转类型时再判）。
 *
 * <p>流式：逐字符扫，注释（{@code --} 到行尾、{@code /* *}{@code /}）是空白；
 * 非 INSERT 语句跳到分号并记一条坏行，一千万行的 dump 也不会整个进内存。
 * 只认识「导出侧写得出来」的那一小块语法 —— 函数调用、十六进制 blob
 * 这类会整句记坏行，不猜。
 */
public final class SqlCodec {

    private SqlCodec() {
    }

    /** 逐行回调；返回 {@code false} 表示不再需要更多行。首行是表头（首条语句的列清单）。 */
    @FunctionalInterface
    public interface RowHandler {
        boolean row(List<String> cells) throws IOException;
    }

    /** 读整个文件；解析失败的语句走 {@code badLine}，不中断。 */
    public static void read(Path file, Charset charset, RowHandler handler,
                            Consumer<String> badLine) throws IOException {
        try (Reader reader = TransferFiles.openReader(file, charset)) {
            scan(new Scanner(reader), handler, badLine);
        }
    }

    /** 读前若干行做预览（含表头行）；坏语句在预览里直接略过。 */
    public static List<List<String>> preview(Path file, Charset charset, int maxRows)
            throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Reader reader = TransferFiles.openReader(file, charset)) {
            scan(new Scanner(reader), cells -> {
                rows.add(cells);
                return rows.size() < maxRows;
            }, message -> { });
        }
        return rows;
    }

    private static void scan(Scanner scanner, RowHandler handler, Consumer<String> badLine)
            throws IOException {
        List<String> header = null;
        while (true) {
            scanner.skipBlank();
            if (!scanner.hasMore()) {
                return;
            }
            if (!scanner.wordIs("insert")) {
                badLine.accept("不是 INSERT 语句（文件第 " + scanner.lineNo + " 行附近）");
                scanner.skipStatement();
                continue;
            }
            Scanner.InsertStatement statement = scanner.insertStatement();
            if (statement == null) {
                badLine.accept("INSERT 语句解析失败（文件第 " + scanner.lineNo + " 行附近）");
                scanner.skipStatement();
                continue;
            }
            if (header == null) {
                header = statement.columns();
                if (!handler.row(header)) {
                    return;
                }
            }
            for (List<String> tuple : statement.tuples()) {
                if (!handler.row(aligned(statement.columns(), tuple, header))) {
                    return;
                }
            }
        }
    }

    /** 元组按语句自己的列清单对到表头：缺的 null、多余的忽略。 */
    private static List<String> aligned(List<String> columns, List<String> tuple,
                                        List<String> header) {
        String[] row = new String[header.size()];
        for (int i = 0; i < columns.size() && i < tuple.size(); i++) {
            int target = indexOfIgnoreCase(header, columns.get(i));
            if (target >= 0) {
                row[target] = tuple.get(i);
            }
        }
        return Arrays.asList(row);
    }

    private static int indexOfIgnoreCase(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(needle)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 逐字符 SQL 扫描器。 {@code next} 是当前字符（已从流里读出），
     * 推回流只用于「-」/「/」的双字符试探。
     */
    private static final class Scanner {
        private final PushbackReader reader;
        private int next = -1;
        private int lineNo = 1;
        private boolean failed;

        Scanner(Reader reader) throws IOException {
            this.reader = new PushbackReader(reader, 2);
            this.next = this.reader.read();
        }

        boolean hasMore() {
            return next != -1;
        }

        void advance() throws IOException {
            if (next == '\n') {
                lineNo++;
            }
            next = reader.read();
        }

        /** 向后看第二个字符（不动 {@code next}）。 */
        private int peek2() throws IOException {
            int second = reader.read();
            if (second != -1) {
                reader.unread(second);
            }
            return second;
        }

        /** 空白与注释（-- 到行尾、块注释）都不算内容。 */
        void skipBlank() throws IOException {
            while (hasMore()) {
                if (Character.isWhitespace((char) next)) {
                    advance();
                } else if (next == '-' && peek2() == '-') {
                    while (hasMore() && next != '\n') {
                        advance();
                    }
                } else if (next == '/' && peek2() == '*') {
                    advance();
                    advance();
                    while (hasMore() && !(next == '*' && peek2() == '/')) {
                        advance();
                    }
                    if (hasMore()) {
                        advance();
                        advance();
                    }
                } else {
                    return;
                }
            }
        }

        boolean take(char expected) throws IOException {
            skipBlank();
            if (hasMore() && next == expected) {
                advance();
                return true;
            }
            return false;
        }

        boolean wordIs(String expected) throws IOException {
            skipBlank();
            String word = word();
            return word != null && word.equalsIgnoreCase(expected);
        }

        /** 裸词：字母开头，字母 / 数字 / 下划线 / 美元号。 */
        private String word() throws IOException {
            if (!hasMore() || !Character.isLetter((char) next)) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            while (hasMore()
                    && (Character.isLetterOrDigit((char) next) || next == '_' || next == '$')) {
                out.append((char) next);
                advance();
            }
            return out.toString();
        }

        /** 标识符：双引号 / 反引号（配对引号翻倍还原）、方括号，或裸词。 */
        private String identifier() throws IOException {
            skipBlank();
            if (!hasMore()) {
                return null;
            }
            char opening = (char) next;
            if (opening == '"' || opening == '`') {
                advance();
                StringBuilder out = new StringBuilder();
                while (hasMore()) {
                    char ch = (char) next;
                    advance();
                    if (ch != opening) {
                        out.append(ch);
                        continue;
                    }
                    if (hasMore() && next == opening) {
                        out.append(opening);
                        advance();
                        continue;
                    }
                    return out.toString();
                }
                return null;
            }
            if (opening == '[') {
                advance();
                StringBuilder out = new StringBuilder();
                while (hasMore() && next != ']') {
                    out.append((char) next);
                    advance();
                }
                if (!hasMore()) {
                    return null;
                }
                advance();
                return out.toString();
            }
            return word();
        }

        /**
         * 解析一条 INSERT。失败返回 {@code null}（调用方记坏行并跳到分号）。
         * 关键字大小写不敏感；表名允许 schema.table（反正不读）。
         */
        InsertStatement insertStatement() throws IOException {
            failed = false;
            if (!wordIs("into")) {
                return null;
            }
            if (identifier() == null) {
                return null;
            }
            skipBlank();
            if (hasMore() && next == '.') {
                advance();
                if (identifier() == null) {
                    return null;
                }
            }
            if (!take('(')) {
                return null;
            }
            List<String> columns = new ArrayList<>();
            while (true) {
                String column = identifier();
                if (column == null) {
                    return null;
                }
                columns.add(column);
                if (take(',')) {
                    continue;
                }
                if (take(')')) {
                    break;
                }
                return null;
            }
            if (!wordIs("values")) {
                return null;
            }
            List<List<String>> tuples = new ArrayList<>();
            while (true) {
                if (!take('(')) {
                    return null;
                }
                List<String> tuple = new ArrayList<>();
                while (true) {
                    String literal = literal();
                    if (failed) {
                        return null;
                    }
                    tuple.add(literal);
                    if (take(',')) {
                        continue;
                    }
                    if (take(')')) {
                        break;
                    }
                    return null;
                }
                tuples.add(tuple);
                if (take(',')) {
                    continue;
                }
                if (!hasMore()) {
                    break;
                }
                if (take(';')) {
                    break;
                }
                return null;
            }
            return new InsertStatement(columns, tuples);
        }

        /** 一个字面量：字符串、数字、NULL / true / false、其余标识符原文。 */
        private String literal() throws IOException {
            skipBlank();
            if (!hasMore()) {
                return fail();
            }
            char opening = (char) next;
            if (opening == '\'') {
                return sqlString();
            }
            if (opening == '-' || opening == '+' || opening == '.'
                    || Character.isDigit(opening)) {
                return number();
            }
            if (Character.isLetter(opening) || opening == '_') {
                String word = word();
                if (word == null) {
                    return fail();
                }
                return switch (word.toLowerCase(Locale.ROOT)) {
                    case "null" -> null;
                    case "true" -> "true";
                    case "false" -> "false";
                    default -> word;
                };
            }
            return fail();
        }

        /** SQL 字符串：'' 翻倍还原，不认反斜杠转义（导出侧也是这么写的）。 */
        private String sqlString() throws IOException {
            advance();
            StringBuilder out = new StringBuilder();
            while (hasMore()) {
                char ch = (char) next;
                advance();
                if (ch != '\'') {
                    out.append(ch);
                    continue;
                }
                if (hasMore() && next == '\'') {
                    out.append('\'');
                    advance();
                    continue;
                }
                return out.toString();
            }
            return fail();
        }

        private String number() throws IOException {
            StringBuilder out = new StringBuilder();
            int digits = 0;
            while (hasMore()) {
                char ch = (char) next;
                if ("+-0123456789.eE".indexOf(ch) < 0) {
                    break;
                }
                if (Character.isDigit(ch)) {
                    digits++;
                }
                out.append(ch);
                advance();
            }
            return digits > 0 ? out.toString() : fail();
        }

        /** 跳到分号（分号也吃掉）；字符串里的分号不算。 */
        void skipStatement() throws IOException {
            while (hasMore()) {
                char ch = (char) next;
                if (ch == '\'') {
                    sqlString();
                    continue;
                }
                advance();
                if (ch == ';') {
                    return;
                }
            }
        }

        private String fail() {
            failed = true;
            return null;
        }

        record InsertStatement(List<String> columns, List<List<String>> tuples) {
        }
    }
}
