import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 tokens.json 生成 Tokens.java 与双主题 CSS。
 *
 * <p>单一真相：{@code ui/adb-ui-core/src/main/resources/tokens/tokens.json}
 * <p>生成物：
 * <ul>
 *   <li>{@code ui/adb-ui-core/src/main/java/io/agenticdb/ui/Tokens.java}</li>
 *   <li>{@code ui/adb-ui-core/src/main/resources/io/agenticdb/ui/theme-dark.css}</li>
 *   <li>{@code ui/adb-ui-core/src/main/resources/io/agenticdb/ui/theme-light.css}</li>
 * </ul>
 *
 * <p>用法（仓库根目录）：{@code java tools/GenTokens.java}
 * <p>门禁：{@code TokenParityTest} 校验 JSON 的键与 {@code Tokens.ALL_KEYS} 完全一致。
 */
public final class GenTokens {

    private static final String TOKENS_JSON =
            "ui/adb-ui-core/src/main/resources/tokens/tokens.json";
    private static final String JAVA_OUT =
            "ui/adb-ui-core/src/main/java/io/agenticdb/ui/Tokens.java";
    private static final String CSS_DIR =
            "ui/adb-ui-core/src/main/resources/io/agenticdb/ui";

    private GenTokens() {
    }

    public static void main(String[] args) throws IOException {
        Path root = findRoot();
        Path tokensJson = root.resolve(TOKENS_JSON);
        Map<String, Object> data = asObject(new Json(tokensJson).parse());
        Map<String, Object> meta = asObject(data.get("meta"));
        Map<String, Object> tokens = asObject(data.get("tokens"));

        long declared = ((Number) meta.get("count")).longValue();
        if (declared != tokens.size()) {
            throw new IllegalStateException(
                    "tokens.json 的 count=" + declared + "，实际条目=" + tokens.size());
        }

        emitJava(root.resolve(JAVA_OUT), tokens);
        emitCss(root.resolve(CSS_DIR).resolve("theme-dark.css"), tokens, "dark", "theme-dark");
        emitCss(root.resolve(CSS_DIR).resolve("theme-light.css"), tokens, "light", "theme-light");
        System.out.println("generated " + tokens.size()
                + " tokens -> Tokens.java + theme-dark.css + theme-light.css");
    }

    private static Path findRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isRegularFile(cursor.resolve(TOKENS_JSON))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录（需要 pom.xml 与 " + TOKENS_JSON + "）");
    }

    private static void emitJava(Path output, Map<String, Object> tokens) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("package io.agenticdb.ui;");
        lines.add("");
        lines.add("import java.util.List;");
        lines.add("");
        lines.add("/**");
        lines.add(" * 设计令牌 —— 由 {@code tools/GenTokens.java} 从 tokens.json 生成。");
        lines.add(" *");
        lines.add(" * <p>⛔ 不要手改这个文件。改 tokens.json 后重新生成。");
        lines.add(" * <p>颜色字面量只允许出现在这里和 theme-*.css。");
        lines.add(" */");
        lines.add("public final class Tokens {");
        lines.add("");
        lines.add("    public static final int COUNT = " + tokens.size() + ";");
        lines.add("");

        for (Map.Entry<String, Object> entry : tokens.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> token = asObject(entry.getValue());
            lines.add("    /** " + key + " (" + token.get("type") + ") */");
            for (String theme : List.of("dark", "light")) {
                String[] value = javaValue(token, theme);
                lines.add("    public static final " + value[0] + " "
                        + constName(key) + "_" + theme.toUpperCase(Locale.ROOT)
                        + " = " + value[1] + ";");
            }
            lines.add("");
        }

        List<String> keys = new ArrayList<>(tokens.keySet());
        lines.add("    public static final List<String> ALL_KEYS = List.of(");
        for (int i = 0; i < keys.size(); i += 6) {
            int end = Math.min(i + 6, keys.size());
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < end; j++) {
                if (j > i) {
                    chunk.append(", ");
                }
                chunk.append('"').append(keys.get(j)).append('"');
            }
            if (end < keys.size()) {
                chunk.append(',');
            }
            lines.add("            " + chunk);
        }
        lines.add("    );");
        lines.add("");
        lines.add("    private Tokens() { }");
        lines.add("}");
        lines.add("");
        Files.writeString(output, String.join("\n", lines), StandardCharsets.UTF_8);
    }

    /**
     * 生成一套主题的 CSS。
     *
     * <p>⭐ 选择器是<b>类</b>（{@code .theme-dark} / {@code .theme-light}）而不是
     * {@code .root}。原因是：主题切换时如果整份样式表被换掉，JavaFX 要对
     * 整棵场景图重做一遍 CSS 计算，紧随其后的 {@code scene.snapshot()} 会拿到
     * 「样式表已换、像素还没重画」的空白帧（4.5-23 真实窗口复现过）。
     * 改成类选择器后，切换主题只是改根节点的一个 style class，
     * 不动样式表列表，CSS 重算范围小得多，也不再出现空白帧。
     */
    private static void emitCss(Path output, Map<String, Object> tokens, String theme,
                                 String selector) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("/* 由 tools/GenTokens.java 生成，不要手改。 */");
        lines.add("/* theme: " + theme + " · selector: ." + selector + " */");
        lines.add("." + selector + " {");
        for (Map.Entry<String, Object> entry : tokens.entrySet()) {
            Map<String, Object> token = asObject(entry.getValue());
            if (!"color".equals(token.get("type"))) {
                continue;
            }
            lines.add("    -adb-" + entry.getKey() + ": " + token.get(theme) + ";");
        }
        lines.add("}");
        lines.add("");
        Files.writeString(output, String.join("\n", lines), StandardCharsets.UTF_8);
    }

    private static String constName(String key) {
        String name = key.replaceAll("[^a-zA-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
            name = "_" + name;
        }
        return name;
    }

    private static String[] javaValue(Map<String, Object> token, String theme) {
        String value = String.valueOf(token.get(theme));
        String kind = String.valueOf(token.get("type"));
        if ("size".equals(kind) || "duration".equals(kind)) {
            String number = value.substring(0, value.length() - 2);
            return new String[]{number.contains(".") ? "double" : "int", number};
        }
        if ("number".equals(kind)) {
            return new String[]{value.contains(".") ? "double" : "int", value};
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return new String[]{"String", "\"" + escaped + "\""};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("tokens.json 期望对象，实际是 " + value);
        }
        return (Map<String, Object>) map;
    }

    /** tokens.json 是受控的简单 JSON；保留键顺序，失败时给出位置。 */
    private static final class Json {
        private final String text;
        private int index;

        private Json(Path path) throws IOException {
            this.text = Files.readString(path, StandardCharsets.UTF_8);
        }

        private Object parse() {
            Object value = value();
            whitespace();
            if (index != text.length()) {
                throw error("JSON 结束后仍有内容");
            }
            return value;
        }

        private Object value() {
            whitespace();
            if (index >= text.length()) {
                throw error("意外的文件结尾");
            }
            char ch = text.charAt(index);
            return switch (ch) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            index++;
            whitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (true) {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
                if (peek('}')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            index++;
            whitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            while (true) {
                result.add(value());
                whitespace();
                if (peek(']')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private String string() {
            if (!peek('"')) {
                throw error("期望字符串");
            }
            index++;
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return result.toString();
                }
                if (ch != '\\') {
                    result.append(ch);
                    continue;
                }
                if (index >= text.length()) {
                    throw error("字符串转义不完整");
                }
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw error("Unicode 转义不完整");
                        }
                        result.append((char) Integer.parseInt(
                                text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw error("未知转义 \\" + escape);
                }
            }
            throw error("字符串未闭合");
        }

        private Object number() {
            int start = index;
            while (index < text.length()) {
                char ch = text.charAt(index);
                if (ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E'
                        || (ch >= '0' && ch <= '9')) {
                    index++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw error("期望值");
            }
            try {
                BigDecimal decimal = new BigDecimal(raw);
                return decimal.scale() <= 0 ? (Object) decimal.longValueExact() : decimal;
            } catch (ArithmeticException | NumberFormatException e) {
                throw error("非法数字 " + raw);
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, index)) {
                throw error("期望 " + word);
            }
            index += word.length();
            return value;
        }

        private void whitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw error("期望 '" + expected + "'");
            }
            index++;
        }

        private IllegalArgumentException error(String message) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < Math.min(index, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new IllegalArgumentException(message + "（第 " + line + " 行，第 "
                    + column + " 列）");
        }
    }
}
