package io.agenticdb.transfer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * ⭐ H4-a：NDJSON 读取器 —— 每行一个 JSON 对象，与 {@link TableExporter} 的
 * JSON 导出同形。
 *
 * <p>首个非空对象的键（按出现顺序）就是表头，<b>但首个对象本身同时是第一行
 * 数据</b> —— NDJSON 每行都是数据，导出侧不写「表头行」，把首对象当表头吃掉
 * 就丢了第一行。后续对象按键名字对齐（键按原文匹配，JSON 键区分大小写），
 * 缺键落 {@code null}，多余键忽略。
 * 值的口径：字符串按转义还原；数字 / {@code true} / {@code false} 取原文文本；
 * {@code null} → {@code null}（落库就是 NULL，文本列也是）；嵌套的
 * {@code {} / []} 不在往返保证内，按原文当文本。行内只许一个对象 ——
 * 解析失败（含行尾有多余内容）整行记坏行，不中断导入。
 *
 * <p>流式：逐行读、逐行喂，一千万行也不会把文件整个搬进内存。
 */
public final class JsonCodec {

    private JsonCodec() {
    }

    /** 逐行回调；返回 {@code false} 表示不再需要更多行。首行是表头（首个对象的键）。 */
    @FunctionalInterface
    public interface RowHandler {
        boolean row(List<String> cells) throws IOException;
    }

    /** 读整个文件；解析失败的行走 {@code badLine}，不中断。 */
    public static void read(Path file, Charset charset, RowHandler handler,
                            Consumer<String> badLine) throws IOException {
        try (BufferedReader reader = open(file, charset)) {
            stream(reader, handler, badLine);
        }
    }

    /** 读前若干行做预览（含表头行）；坏行在预览里直接略过。 */
    public static List<List<String>> preview(Path file, Charset charset, int maxRows)
            throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = open(file, charset)) {
            stream(reader, cells -> {
                rows.add(cells);
                return rows.size() < maxRows;
            }, message -> { });
        }
        return rows;
    }

    private static BufferedReader open(Path file, Charset charset) throws IOException {
        return new BufferedReader(TransferFiles.openReader(file, charset));
    }

    private static void stream(BufferedReader reader, RowHandler handler,
                               Consumer<String> badLine) throws IOException {
        List<String> header = null;
        int lineNo = 0;
        for (String line; (line = reader.readLine()) != null; ) {
            lineNo++;
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            LinkedHashMap<String, String> object = parseObject(text);
            if (object == null) {
                badLine.accept("JSON 解析失败：「" + excerpt(text) + "」（文件第 " + lineNo + " 行）");
                continue;
            }
            if (header == null) {
                header = new ArrayList<>(object.keySet());
                if (!handler.row(header)) {
                    return;
                }
                if (!handler.row(aligned(object, header))) {
                    return;
                }
                continue;
            }
            if (!handler.row(aligned(object, header))) {
                return;
            }
        }
    }

    private static List<String> aligned(LinkedHashMap<String, String> object,
                                        List<String> header) {
        List<String> row = new ArrayList<>(header.size());
        for (String key : header) {
            row.add(object.get(key));
        }
        return row;
    }

    private static String excerpt(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    /**
     * 解析单行对象。键序 = 表头序；LinkedHashMap 保序、允许 null 值（JSON null）。
     * 重复键后者胜。
     */
    private static LinkedHashMap<String, String> parseObject(String text) {
        Cursor cursor = new Cursor(text);
        cursor.ws();
        if (!cursor.take('{')) {
            return null;
        }
        LinkedHashMap<String, String> object = new LinkedHashMap<>();
        if (cursor.take('}')) {
            return cursor.atEnd() ? object : null;
        }
        while (true) {
            cursor.ws();
            String key = cursor.string();
            if (key == null || !cursor.take(':')) {
                return null;
            }
            String value = cursor.value();
            if (cursor.failed) {
                return null;
            }
            object.put(key, value);
            if (cursor.take(',')) {
                continue;
            }
            if (cursor.take('}')) {
                return cursor.atEnd() ? object : null;
            }
            return null;
        }
    }

    /**
     * 单行扫描器。失败置 {@link #failed} —— 调用方据此判坏行；
     * {@code null} 是合法值（JSON null），不能拿来当失败记号。
     */
    private static final class Cursor {
        private final String text;
        private int pos;
        private boolean failed;

        Cursor(String text) {
            this.text = text;
        }

        boolean atEnd() {
            ws();
            return pos >= text.length();
        }

        void ws() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean take(char expected) {
            ws();
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        /**
         * JSON 字符串（转义还原）。失败返回 {@code null} ——
         * 键和「失败」都不会混（键永远不是 null），可以这么用。
         */
        String string() {
            if (!take('"')) {
                return fail();
            }
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == '"') {
                    return out.toString();
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (pos >= text.length()) {
                    return fail();
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            return fail();
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException invalid) {
                            return fail();
                        }
                        pos += 4;
                    }
                    default -> {
                        return fail();
                    }
                }
            }
            return fail();
        }

        /**
         * 一个值：字符串还原、数字 / true / false 原文、null → null、
         * 嵌套结构取原文。失败用 {@code failed} 标记。
         */
        String value() {
            ws();
            if (pos >= text.length()) {
                return fail();
            }
            return switch (text.charAt(pos)) {
                case '"' -> string();
                case '{', '[' -> balanced();
                case 't' -> keyword("true", "true");
                case 'f' -> keyword("false", "false");
                case 'n' -> keyword("null", null);
                default -> number();
            };
        }

        private String keyword(String literal, String result) {
            if (text.startsWith(literal, pos)) {
                pos += literal.length();
                return result;
            }
            return fail();
        }

        private String number() {
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            boolean digit = false;
            while (pos < text.length() && "0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                digit = true;
                pos++;
            }
            return digit ? text.substring(start, pos) : fail();
        }

        /** 嵌套结构原文照抄（不在往返保证内，只求不炸）。 */
        private String balanced() {
            int start = pos;
            int depth = 0;
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == '"') {
                    pos--;
                    if (string() == null) {
                        return fail();
                    }
                    continue;
                }
                if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    if (--depth == 0) {
                        return text.substring(start, pos);
                    }
                }
            }
            return fail();
        }

        private String fail() {
            failed = true;
            return null;
        }
    }
}
