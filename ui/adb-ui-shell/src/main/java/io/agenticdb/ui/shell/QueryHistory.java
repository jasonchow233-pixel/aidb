package io.agenticdb.ui.shell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 最近执行过的 SQL。首版落 java.util.prefs，P5 再迁到 config.db。
 *
 * <p>⭐ P2-3：条目不再只是 SQL 文本，还带**连接 / 命中的表 / 执行时间**。
 * 有了对象上下文，「这张表最近被怎么查过」才能回答 —— 数据页的「历史」
 * 和详情面板的「最近操作」都是按表筛这份记录。
 *
 * <p>表名从 SQL 里轻量识别（{@code FROM / JOIN / UPDATE / INTO} 后的标识符，
 * 去掉 schema 前缀），不是完整 SQL 解析器：识别不到时退回整句包含匹配，
 * 宁可少筛，也不编造「这表被查过」。
 *
 * <p>旧版本存的是纯字符串数组；{@link #parse} 继续认这种格式，
 * 老用户升级后历史不会突然清空。
 */
final class QueryHistory {

    private static final String KEY = "query.history";
    private static final int LIMIT = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Entry>> ENTRY_LIST = new TypeReference<>() { };
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    /** 一条历史记录。{@code at == 0} 表示旧格式迁移来的、时间未知。 */
    record Entry(String sql, String connection, List<String> tables, long at) {

        Entry {
            sql = sql == null ? "" : sql.strip();
            connection = connection == null ? "" : connection.strip();
            tables = tables == null ? List.of() : List.copyOf(tables);
            at = Math.max(at, 0);
        }

        Entry(String sql) {
            this(sql, "", List.of(), 0);
        }

        /** 这条记录是否与某张表有关。 */
        boolean touches(String table) {
            if (table == null || table.isBlank()) {
                return false;
            }
            String wanted = table.strip();
            for (String candidate : tables) {
                if (sameName(candidate, wanted)) {
                    return true;
                }
            }
            // 没识别出表时退回整句匹配（子查询 / 方言语法识别不到的情况）。
            return tables.isEmpty() && containsWord(sql, wanted);
        }

        /** 列表里的一行：时间 + 压平后的 SQL。 */
        String summary() {
            String text = oneLine(sql);
            if (text.length() > 120) {
                text = text.substring(0, 120) + "…";
            }
            return at <= 0 ? text : CLOCK.format(Instant.ofEpochMilli(at)) + " · " + text;
        }

        /** 悬停提示：完整 SQL + 上下文。 */
        String detail() {
            StringBuilder detail = new StringBuilder(sql);
            if (!connection.isBlank()) {
                detail.append("\n连接：").append(connection);
            }
            if (!tables.isEmpty()) {
                detail.append("\n表：").append(String.join(", ", tables));
            }
            return detail.toString();
        }

        private static boolean sameName(String left, String right) {
            String a = unquote(left);
            String b = unquote(right);
            int dot = a.lastIndexOf('.');
            if (dot >= 0) {
                a = a.substring(dot + 1);
            }
            dot = b.lastIndexOf('.');
            if (dot >= 0) {
                b = b.substring(dot + 1);
            }
            return a.equalsIgnoreCase(b);
        }

        private static String unquote(String value) {
            String text = value.strip();
            if (text.length() >= 2) {
                char first = text.charAt(0);
                char last = text.charAt(text.length() - 1);
                if ((first == '"' && last == '"') || (first == '`' && last == '`')
                        || (first == '[' && last == ']')) {
                    return text.substring(1, text.length() - 1);
                }
            }
            return text;
        }

        private static String oneLine(String sql) {
            return sql.replaceAll("\\s+", " ").strip();
        }

        private static boolean containsWord(String sql, String word) {
            return sql.toLowerCase(Locale.ROOT)
                    .matches(".*(?<![\\w$])" + java.util.regex.Pattern
                            .quote(word.toLowerCase(Locale.ROOT))
                            + "(?![\\w$]).*");
        }
    }

    private final SettingsStore settings;
    private final List<Entry> memory = new ArrayList<>();

    QueryHistory(SettingsStore settings) {
        this.settings = settings;
    }

    /** 只要 SQL 文本（菜单 / 兼容老调用）。 */
    List<String> recent() {
        return load().stream().map(Entry::sql).toList();
    }

    /** 全部条目，最新在前。 */
    List<Entry> entries() {
        return load();
    }

    /**
     * 某张表最近的执行记录（最新在前）。
     *
     * @param table 表名；匹配忽略大小写与 schema 前缀
     * @param limit 最多返回几条；{@code <= 0} 表示不限制
     */
    List<Entry> recentForObject(String table, int limit) {
        List<Entry> matched = new ArrayList<>();
        for (Entry entry : load()) {
            if (entry.touches(table)) {
                matched.add(entry);
                if (limit > 0 && matched.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(matched);
    }

    void add(String sql) {
        add(sql, "");
    }

    /** 记录一次执行。表上下文从 SQL 里识别。 */
    void add(String sql, String connection) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        Entry entry = new Entry(sql, connection, objectsIn(sql), System.currentTimeMillis());
        List<Entry> items = new ArrayList<>(load());
        items.removeIf(existing -> existing.sql().equals(entry.sql()));
        items.add(0, entry);
        if (items.size() > LIMIT) {
            items = new ArrayList<>(items.subList(0, LIMIT));
        }
        save(items);
    }

    void clear() {
        memory.clear();
        if (settings != null) {
            settings.set(KEY, "[]");
        }
    }

    /**
     * 解析落盘的 JSON：新版是对象数组，旧版是字符串数组。
     * 解析失败返回空 —— 历史坏了不能拖垮查询页。
     */
    static List<Entry> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Entry> items = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    String sql = item.asText();
                    if (!sql.isBlank()) {
                        items.add(new Entry(sql, "", objectsIn(sql), 0));
                    }
                    continue;
                }
                Entry entry = JSON.treeToValue(item, Entry.class);
                if (entry != null && !entry.sql().isBlank()) {
                    items.add(entry);
                }
            }
            return List.copyOf(items);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 从 SQL 里挑出表名（{@code FROM / JOIN / UPDATE / INTO} 后第一个标识符）。
     *
     * <p>字符串字面量与注释先跳过 —— {@code WHERE name = 'from users'} 不是表。
     * 识别不到的表由调用方退回整句匹配，而不是在这里猜。
     */
    static List<String> objectsIn(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        boolean expectTable = false;
        int index = 0;
        int length = sql.length();
        while (index < length) {
            char c = sql.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
                int end = sql.indexOf('\n', index);
                index = end < 0 ? length : end + 1;
                continue;
            }
            if (c == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                index = end < 0 ? length : end + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int end = index + 1;
                StringBuilder value = new StringBuilder();
                while (end < length) {
                    char current = sql.charAt(end);
                    if (c == '\'' && current == '\'' && end + 1 < length
                            && sql.charAt(end + 1) == '\'') {
                        value.append('\'');
                        end += 2;
                        continue;
                    }
                    if (current == close) {
                        end++;
                        break;
                    }
                    value.append(current);
                    end++;
                }
                if (expectTable && c != '\'') {
                    addTable(found, value.toString());
                    expectTable = false;
                }
                index = end;
                continue;
            }
            if (c == '(' || c == ')' || c == ',' || c == ';') {
                // 子查询 / 逗号列表：当前标记失效，内部 FROM 会被单独扫到。
                expectTable = false;
                index++;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                int end = index;
                while (end < length && (Character.isLetterOrDigit(sql.charAt(end))
                        || sql.charAt(end) == '_' || sql.charAt(end) == '$'
                        || sql.charAt(end) == '.')) {
                    end++;
                }
                String word = sql.substring(index, end);
                index = end;
                if (expectTable) {
                    addTable(found, word);
                    expectTable = false;
                } else if (isTableIntroducer(word)) {
                    expectTable = true;
                }
                continue;
            }
            // 运算符等：不影响「下一个标识符是表」的判定（等号后不会跟表名）。
            if (c != '=' && c != '>' && c != '<' && c != '!' && c != '|' && c != '+'
                    && c != '-' && c != '*' && c != '%') {
                expectTable = false;
            }
            index++;
        }
        return List.copyOf(found);
    }

    private static boolean isTableIntroducer(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "FROM", "JOIN", "UPDATE", "INTO" -> true;
            default -> false;
        };
    }

    private static void addTable(Set<String> found, String raw) {
        String name = raw.strip();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        if (!name.isBlank()) {
            found.add(name);
        }
    }

    private List<Entry> load() {
        if (settings == null) {
            return List.copyOf(memory);
        }
        String raw = settings.get(KEY, "");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return parse(raw);
    }

    private void save(List<Entry> items) {
        if (settings == null) {
            memory.clear();
            memory.addAll(items);
            return;
        }
        try {
            settings.set(KEY, JSON.writeValueAsString(items));
        } catch (Exception ignored) {
            // 历史记录失败不能影响查询本身。
        }
    }
}
