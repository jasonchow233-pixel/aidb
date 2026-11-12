package io.agenticdb.transfer;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式分隔文本编解码（RFC 4180）。
 *
 * <p><b>为什么要自己写</b>：JDK 没有 CSV 库，而 Commons CSV 之类的依赖
 * 要为「一个导出功能」引入一整个依赖 —— 不值。这里实现的是标准里最难的那部分：
 * <ul>
 *   <li>带引号的字段里可以有分隔符、换行、双引号（写成 {@code ""}）；</li>
 *   <li>流式读写 —— <b>不把整个文件读进内存</b>。</li>
 * </ul>
 *
 * <p>「不读进内存」对导入导出是硬要求：一张一千万行的表，
 * {@code Files.readAllLines} 会直接 OOM。
 */
public final class CsvCodec {

    private CsvCodec() {
    }

    /** 写一行。{@code null} 写成空字段。 */
    public static void writeRow(Writer out, List<String> values, char delimiter)
            throws IOException {
        writeRow(out, values, delimiter, false);
    }

    /**
     * 写一行。
     *
     * @param quoteAll {@code true} 时每个字段都加引号（导出向导的「文本限定符」选项）；
     *                 {@code false} 时只在必要时加 —— Navicat 的默认行为
     */
    public static void writeRow(Writer out, List<String> values, char delimiter, boolean quoteAll)
            throws IOException {
        // 默认限定符 '"' + 默认 CRLF 行尾 —— 兼容 D-86 之前所有调用点的逐字节输出。
        writeRow(out, values, delimiter, '"', quoteAll, "\r\n");
    }

    /**
     * D-86：写一行，支持自定义文本限定符与记录分隔符。
     *
     * @param qualifier       引用字符；字段内出现的该字符写成两个（转义规则随它变化）
     * @param quoteAll        是否给<b>所有</b>字段加引号（与 qualifier 是两件事）
     * @param recordDelimiter 本行的记录结尾
     */
    public static void writeRow(Writer out, List<String> values, char delimiter,
                                char qualifier, boolean quoteAll, String recordDelimiter)
            throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(delimiter);
            }
            out.write(escape(values.get(i), delimiter, qualifier, quoteAll));
        }
        out.write(recordDelimiter);
    }

    /** 转义单个字段。需要时加引号。 */
    public static String escape(String value, char delimiter) {
        return escape(value, delimiter, false);
    }

    /** 转义单个字段（默认限定符 '"'）。 */
    public static String escape(String value, char delimiter, boolean quoteAll) {
        return escape(value, delimiter, '"', quoteAll);
    }

    /** D-86：转义单个字段，引用字符随 qualifier 变化。 */
    public static String escape(String value, char delimiter, char qualifier, boolean quoteAll) {
        if (value == null) {
            return quoteAll ? new String(new char[]{qualifier, qualifier}) : "";
        }
        boolean quote = quoteAll || value.indexOf(delimiter) >= 0
                || value.indexOf(qualifier) >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        String q = String.valueOf(qualifier);
        String escaped = value.replace(q, q + q);
        return quote ? q + escaped + q : escaped;
    }

    /**
     * 读一行。
     *
     * @param in 读入源；必须是 {@link PushbackReader}（要回退一个字符判断 CRLF）
     * @return 行结束返回 {@code null}（EOF）
     */
    public static List<String> readRow(PushbackReader in, char delimiter) throws IOException {
        List<String> values = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean sawAnything = false;

        while (true) {
            int read = in.read();
            if (read < 0) {
                if (!sawAnything) {
                    return null;
                }
                values.add(field.toString());
                return values;
            }
            sawAnything = true;
            char c = (char) read;

            if (inQuotes) {
                if (c == '"') {
                    int next = in.read();
                    if (next == '"') {
                        field.append('"');
                        continue;
                    }
                    inQuotes = false;
                    if (next < 0) {
                        values.add(field.toString());
                        return values;
                    }
                    char after = (char) next;
                    if (after == delimiter) {
                        values.add(field.toString());
                        field.setLength(0);
                        continue;
                    }
                    if (after == '\n' || after == '\r') {
                        consumeLineFeed(in, after);
                        values.add(field.toString());
                        return values;
                    }
                    // 不合规的输入：闭引号后的字符当作字段内容继续
                    field.append(after);
                    continue;
                }
                field.append(c);
                continue;
            }

            if (c == '"' && field.isEmpty()) {
                inQuotes = true;
                continue;
            }
            if (c == delimiter) {
                values.add(field.toString());
                field.setLength(0);
                continue;
            }
            if (c == '\n') {
                values.add(field.toString());
                return values;
            }
            if (c == '\r') {
                consumeLineFeed(in, '\r');
                values.add(field.toString());
                return values;
            }
            field.append(c);
        }
    }

    /** 吃掉 CRLF 的第二个字符（单独一个 LF 时不动它）。 */
    private static void consumeLineFeed(PushbackReader in, char first) throws IOException {
        if (first != '\r') {
            return;
        }
        int next = in.read();
        if (next >= 0 && next != '\n') {
            in.unread(next);
        }
    }
}
