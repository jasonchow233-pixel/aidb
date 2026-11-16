package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从视图定义体里抽出它<b>引用</b>的表 / 视图名（前向依赖边：视图 → 它读的对象）。
 *
 * <p>这是「视图依赖图」的模型基座，纯函数、不碰库、不碰 UI，所以能在无头环境单测。
 * 反向边（谁依赖这张表 / 视图）由 {@link SchemaDependencyGraph} 从所有视图的前向边推导，
 * 不再单独查库——一套前向解析覆盖双向。
 *
 * <p>解析口径（v1，务实而非完备）：
 * <ul>
 *   <li>先剥掉 {@code CREATE [OR REPLACE] VIEW <名> AS} 前缀，给裸 {@code SELECT} 也能吃；</li>
 *   <li>剥行注释 {@code --}、块注释 {@code /* *\/}、单引号字符串（含 {@code ''} 转义）—— 这些里面
 *       出现 {@code FROM}/{@code JOIN} 不能算引用；</li>
 *   <li>识别 {@code WITH cte AS (...), cte2 AS (...)} 的 CTE 名，CTE 引用不算外部对象；</li>
 *   <li>扫所有 {@code FROM}/{@code JOIN} 后跟的对象路径（schema 限定 / 反引号 / 双引号 / 方括号），
 *       取最后一段去引号作为对象名；逗号连接 {@code FROM a, b} 逐段收，遇到
 *       {@code ON/WHERE/AS/别名} 等边界停；子查询里的 {@code FROM} 自然被同一套扫描覆盖。</li>
 * </ul>
 *
 * <p>已知限制（v1 不解决，写在这里免得被当成 bug）：
 * <ul>
 *   <li>跨 schema 引用若按 {@code schema.table} 写、而对象清单按裸名列，连不通——
 *       这里只取裸名，同 schema 内才连得上；</li>
 *   <li>动态 SQL / 同义名 / 视图里 {@code INSERT INTO ... SELECT} 的目标表会被当成依赖
 *       （视图正文理论上只能是查询，但防御性保留）；</li>
 *   <li>不是完整 SQL 语法树，极怪的方言写法可能漏——以「漏比错连更安全」为取舍。</li>
 * </ul>
 */
public final class ViewBodyDependencies {

    /**
     * 一处对象引用：{@code name} 是最后一段裸名，{@code qualifier} 是它前面的限定前缀
     * （如 {@code dbo} / {@code catalog.dbo}，已去引号，点分隔；无前缀时为 {@code null}）。
     *
     * <p>为什么留着 qualifier：库里同名对象分属不同 schema（{@code sales.orders} 与
     * {@code hr.orders}）时，只取裸名会把两条边并成一个节点（R16）。建图时限定名精确匹配优先，
     * 匹配不到再退回裸名，退回仍歧义的按 UNKNOWN 处理，不许臆造合并。
     */
    public record ObjectRef(String qualifier, String name) {
        public ObjectRef {
            qualifier = qualifier == null || qualifier.isEmpty() ? null : qualifier;
        }

        public boolean qualified() {
            return qualifier != null;
        }

        /** {@code schema.table} 形态的规范名（无前缀时就是裸名）。 */
        public String canonical() {
            return qualified() ? qualifier + "." + name : name;
        }
    }

    private ViewBodyDependencies() {
    }

    /** 边界关键字：遇到这些，from-item 列表就收尾（它们是别名 / 子句的开头，不是另一个对象）。 */
    private static final Set<String> BOUNDARY = Set.of(
            "ON", "USING", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "UNION", "EXCEPT", "INTERSECT", "SET", "VALUES", "WINDOW", "INTO", "AS",
            "BY", "DISTINCT", "ALL", "FROM", "JOIN", "SELECT", "INSERT", "UPDATE",
            "DELETE", "WITH");

    private static final Pattern CREATE_VIEW =
            Pattern.compile("(?i)CREATE\\s+(OR\\s+REPLACE\\s+)?VIEW\\s+");
    private static final Pattern AS_BOUND = Pattern.compile("(?i)\\bAS\\b");

    /**
     * 抽出一个视图正文（或整条 {@code CREATE VIEW}）里被引用的表 / 视图名。
     *
     * @return 去重后的裸对象名集合（保留原大小写；图里按大小写不敏感匹配）
     */
    public static Set<String> extract(String viewBody) {
        Set<String> names = new HashSet<>();
        for (ObjectRef ref : extractQualified(viewBody)) {
            names.add(ref.name());
        }
        return names;
    }

    /**
     * 与 {@link #extract(String)} 同一套解析，但保留每处引用的限定前缀（R16）。
     *
     * <p>返回顺序为首次出现顺序、按 {@code qualifier + name} 去重；裸名相同的两处引用若
     * 前缀不同（{@code sales.orders} / {@code hr.orders}）会各自保留，由建图阶段决定是否合并。
     *
     * @return 去重的引用列表（保留大小写）
     */
    public static List<ObjectRef> extractQualified(String viewBody) {
        String body = stripCreatePrefix(viewBody == null ? "" : viewBody);
        body = stripComments(body);
        body = stripSingleQuoted(body);
        Set<String> ctes = collectCtes(body);
        Set<String> seen = new LinkedHashSet<>();
        List<ObjectRef> refs = new ArrayList<>();
        scanFromJoin(body, ctes, (bare, qualifier) -> {
            String key = (qualifier == null ? "" : qualifier.toLowerCase(Locale.ROOT) + ".") +
                    bare.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                refs.add(new ObjectRef(qualifier, bare));
            }
        });
        return refs;
    }

    /** 剥 {@code CREATE [OR REPLACE] VIEW <名> AS} 前缀；不是 CREATE VIEW 则原样返回。 */
    static String stripCreatePrefix(String source) {
        Matcher create = CREATE_VIEW.matcher(source);
        if (!create.find()) {
            return source;
        }
        int from = create.end();
        Matcher as = AS_BOUND.matcher(source);
        if (as.find(from) && as.start() >= from) {
            return source.substring(as.end());
        }
        return source;
    }

    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '-' && i + 1 < n && source.charAt(i + 1) == '-') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n
                        && source.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i < n) {
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static String stripSingleQuoted(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (source.charAt(i) == '\'') {
                        if (i + 1 < n && source.charAt(i + 1) == '\'') {
                            i += 2; // 转义引号，不是串尾
                            continue;
                        }
                        i++; // 串尾
                        break;
                    }
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 收集 {@code WITH} 子句里的 CTE 名（大小写不敏感），供排除使用。 */
    static Set<String> collectCtes(String body) {
        Set<String> ctes = new HashSet<>();
        Matcher with = Pattern.compile("(?i)\\bWITH\\b").matcher(body);
        if (!with.find()) {
            return ctes;
        }
        int i = with.end();
        int n = body.length();
        // WITH 之后可能跟 RECURSIVE
        i = skipSpace(body, i);
        if (body.startsWith("RECURSIVE", i) || body.startsWith("recursive", i)) {
            i = skipSpace(body, i + 9);
        }
        int depth = 0;
        while (i < n) {
            i = skipSpace(body, i);
            String id = readPathLastSegment(body, i);
            if (id == null) {
                // 不是标识符（可能是 SELECT 主句开始 / 括号）—— 深度归零处见到 SELECT 就停
                if (depth == 0 && atWord(body, i, "SELECT", "INSERT", "UPDATE", "DELETE")) {
                    break;
                }
                // 跳过这一字符继续
                i++;
                continue;
            }
            int afterId = afterPath(body, i);
            i = skipSpace(body, afterId);
            if (atWord(body, i, "AS")) {
                // 这是一个 CTE 名；跳过它的定义体（AS 后的括号组）
                ctes.add(id.toLowerCase(Locale.ROOT));
                i = skipCteBody(body, i + 2);
            }
            // 否则只是主句里的标识符，继续（遇到 SELECT 会在下一轮停）
        }
        return ctes;
    }

    /** 跳过 CTE 的 {@code (...)} 定义体，返回右括号之后的位置。 */
    private static int skipCteBody(String body, int i) {
        i = skipSpace(body, i);
        if (i >= body.length() || body.charAt(i) != '(') {
            return i;
        }
        int depth = 0;
        int n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return i;
    }

    /** from-item 引用接收器：裸名 + 限定前缀（无前缀为 null）。 */
    @FunctionalInterface
    private interface RefSink {
        void accept(String bare, String qualifier);
    }

    /** 扫所有 {@code FROM}/{@code JOIN} 后跟的 from-item，提取对象名（去 CTE）。 */
    private static void scanFromJoin(String body, Set<String> ctes, RefSink sink) {
        Matcher m = Pattern.compile("(?i)\\b(FROM|JOIN)\\b").matcher(body);
        while (m.find()) {
            int i = m.end();
            // 一个 from-item 列表：一个或多个对象路径，逗号分隔，遇边界停
            while (i < body.length()) {
                i = skipSpace(body, i);
                List<String> segments = readPathSegments(body, i);
                if (segments.isEmpty()) {
                    break; // 括号子查询 / 意外字符：本 from-item 收尾，子查询内部会被另行扫到
                }
                String name = segments.get(segments.size() - 1);
                int after = afterPath(body, i);
                if (!ctes.contains(name.toLowerCase(Locale.ROOT))) {
                    String qualifier = segments.size() > 1
                            ? String.join(".", segments.subList(0, segments.size() - 1))
                            : null;
                    sink.accept(name, qualifier);
                }
                i = skipSpace(body, after);
                // 吃掉可能的别名（FROM a b / FROM a AS b），再判逗号或边界
                i = skipOptionalAlias(body, i);
                i = skipSpace(body, i);
                if (i < body.length() && body.charAt(i) == ',') {
                    i++;
                    continue;
                }
                break;
            }
        }
    }

    /** 吃一个别名标识符（非边界关键字），返回其后的位置。 */
    private static int skipOptionalAlias(String body, int i) {
        i = skipSpace(body, i);
        if (i >= body.length()) {
            return i;
        }
        if (body.charAt(i) == '"' || body.charAt(i) == '`' || body.charAt(i) == '[') {
            return i; // 引号开头不会是裸别名
        }
        Matcher id = BARE_ID.matcher(body.substring(i));
        if (id.lookingAt()) {
            String word = id.group().toUpperCase(Locale.ROOT);
            if (BOUNDARY.contains(word)) {
                return i; // 是子句关键字，不是别名
            }
            return i + id.group().length();
        }
        return i;
    }

    private static final Pattern BARE_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    /** 读一个对象路径（schema.表 / 带引号标识符 / [s].[t] 等形式），返回最后一段去引号；不是路径返回 null。 */
    static String readPathLastSegment(String body, int start) {
        List<String> segments = readPathSegments(body, start);
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    /**
     * 读一个对象路径的<b>所有</b>段（每段去引号、保原大小写）。
     * 空列表表示起点不是标识符 / 路径。供限定名解析与 CTE 收集共用一套分段逻辑。
     */
    static List<String> readPathSegments(String body, int start) {
        List<String> segments = new ArrayList<>();
        int i = start;
        int n = body.length();
        while (i < n) {
            String seg = readSegment(body, i);
            if (seg == null) {
                break;
            }
            segments.add(seg);
            i = afterSegment(body, i);
            i = skipSpace(body, i);
            if (i < n && body.charAt(i) == '.') {
                i++;
                i = skipSpace(body, i);
                continue;
            }
            break;
        }
        return segments;
    }

    /** 返回对象路径之后的位置（最后一个段之后）。 */
    static int afterPath(String body, int start) {
        int i = start;
        int n = body.length();
        while (i < n) {
            String seg = readSegment(body, i);
            if (seg == null) {
                return i;
            }
            i = afterSegment(body, i);
            i = skipSpace(body, i);
            if (i < n && body.charAt(i) == '.') {
                i++;
                i = skipSpace(body, i);
                continue;
            }
            return i;
        }
        return i;
    }

    /** 读一个标识符段：裸标识符或引号标识符（"x" / `x` / [x]），返回去引号后的内容；否则 null。 */
    private static String readSegment(String body, int i) {
        if (i >= body.length()) {
            return null;
        }
        char c = body.charAt(i);
        if (c == '"') {
            return readQuoted(body, i, '"');
        }
        if (c == '`') {
            return readQuoted(body, i, '`');
        }
        if (c == '[') {
            int end = body.indexOf(']', i + 1);
            if (end < 0) {
                return null;
            }
            return body.substring(i + 1, end);
        }
        Matcher id = BARE_ID.matcher(body.substring(i));
        if (id.lookingAt()) {
            return id.group();
        }
        return null;
    }

    private static String readQuoted(String body, int i, char q) {
        int n = body.length();
        int j = i + 1;
        StringBuilder sb = new StringBuilder();
        while (j < n) {
            char c = body.charAt(j);
            if (c == q) {
                if (j + 1 < n && body.charAt(j + 1) == q) {
                    sb.append(q); // 转义引号
                    j += 2;
                    continue;
                }
                return sb.toString();
            }
            sb.append(c);
            j++;
        }
        return null; // 没闭合，视为无效
    }

    /** 引号标识符之后的位置。 */
    private static int afterSegment(String body, int i) {
        char c = body.charAt(i);
        if (c == '"' || c == '`') {
            int n = body.length();
            int j = i + 1;
            while (j < n) {
                if (body.charAt(j) == c) {
                    if (j + 1 < n && body.charAt(j + 1) == c) {
                        j += 2;
                        continue;
                    }
                    return j + 1;
                }
                j++;
            }
            return j;
        }
        if (c == '[') {
            int end = body.indexOf(']', i + 1);
            return end < 0 ? body.length() : end + 1;
        }
        Matcher id = BARE_ID.matcher(body.substring(i));
        return id.lookingAt() ? i + id.group().length() : i;
    }

    private static int skipSpace(String body, int i) {
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 在位置 i 起是否是一个整词（边界匹配）关键字之一。 */
    private static boolean atWord(String body, int i, String... words) {
        if (i >= body.length()) {
            return false;
        }
        for (String w : words) {
            if (body.regionMatches(true, i, w, 0, w.length())) {
                int end = i + w.length();
                if (end >= body.length() || !isWordChar(body.charAt(end))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
