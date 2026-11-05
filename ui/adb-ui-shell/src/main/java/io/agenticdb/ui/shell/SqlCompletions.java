package io.agenticdb.ui.shell;

import io.agenticdb.sql.SqlLexer;
import io.agenticdb.sql.SqlToken;
import io.agenticdb.sql.SqlTokenType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ⭐ R57-a：补全的**纯逻辑**层 —— 不碰界面、不碰数据库。
 *
 * <p>输入是「文本 + 光标 + 模式来源」，输出是「替换区间 + 候选清单」。
 * 拆成两半的理由和 R53 的 {@code EditValues.emptiedCell} 一样：规则只有一份，
 * 界面（{@link QueryCompletions}）与单测吃的都是同一个函数，界面单方面改掉时测试跟着红。
 *
 * <h2>三种上下文</h2>
 * <ul>
 *   <li><b>限定名</b>（{@code users.i|} / {@code u.i|} / {@code shop.users.i|}）→ 只给那张表的列。
 *       限定符认不出（既不是已知表也不是别名）时**不猜** —— 给空清单，弹层收起，
 *       比端出一堆不相干的列诚实。</li>
 *   <li><b>表位</b>（FROM / JOIN / INTO / UPDATE / TABLE 之后）→ 只给表与视图。</li>
 *   <li><b>其它</b>（SELECT 列 / WHERE …）→ 当前语句引用到的表的列，再表、视图、例程、关键字。</li>
 * </ul>
 *
 * <p>字符串字面量、数字、注释、未闭合引号里面**不弹** —— 词法器把这些都标得出来。
 */
final class SqlCompletions {

    /** 弹层是浮在编辑器上的窄窗，候选必须有上限，否则一个千表库把首帧拖成滚动条迷宫。 */
    static final int MAX_CANDIDATES = 500;

    private static final Set<String> TABLE_KEYWORDS =
            Set.of("FROM", "JOIN", "INTO", "UPDATE", "TABLE");

    private SqlCompletions() {
    }

    enum Kind {
        TABLE,
        VIEW,
        ROUTINE,
        COLUMN,
        KEYWORD
    }

    /** 模式来源交出的一行：对象名 + 类别。 */
    record Entry(String name, Kind kind) {
    }

    /**
     * 一条候选。
     *
     * @param name       显示名（目录里的原样）
     * @param kind       类别（决定图形与分组顺序）
     * @param detail     右侧小字：列所属的表名；其余为空
     * @param insertText 真正插进编辑器的文本（该加引号的已经加好）
     */
    record Candidate(String name, Kind kind, String detail, String insertText) {
    }

    /**
     * 一次补全的结果。
     *
     * @param replaceStart  被替换区间的起点（当前半截词的开头；点号后没有词时 = 光标）
     * @param replaceEnd    区间终点（= 光标）
     * @param candidates    候选（已按前缀过滤、按类别分组、截到 {@link #MAX_CANDIDATES}）
     * @param modelReady    模式快照是否已经齐（对象清单 + 本条需要的列）；false = 还在后台读
     * @param missingTables 本条上下文需要、但列还没读回来的表（控制器拿它们去补读）
     */
    record Proposal(int replaceStart, int replaceEnd, List<Candidate> candidates,
                    boolean modelReady, List<String> missingTables) {

        static Proposal none(int caret, boolean ready) {
            return new Proposal(caret, caret, List.of(), ready, List.of());
        }
    }

    /** 补全读模式数据的出口 —— 由 {@link QueryCompletions} 实现，单测用替身。 */
    interface Source {
        /** 当前库的对象清单（没读到就交空清单，并用 {@link #objectsReady()} 说明）。 */
        List<Entry> objects();

        /** 对象清单是否已经读回来（读到过且不过期）。false = 空清单是「还没读」不是「没有表」。 */
        boolean objectsReady();

        /** 某表的列（按界面上展开表节点的同一条懒加载通道；没读到交空）。 */
        List<String> columnsOf(String table);

        /** 该表的列是否已经读回来。 */
        boolean columnsLoaded(String table);

        /** SQL 关键字（来自词法器那份共享表，不再抄第二遍）。 */
        Set<String> keywords();

        /** 方言引号（只在对的标识符形状不安全时用）。 */
        String quote(String identifier);

        /**
         * 这个名字裸写（不加引号）进 SQL，引擎解析后还落回同一个对象吗。
         *
         * <p>⚠️ 默认 true 只在不折叠大小写的库成立：Oracle 把裸名折成大写、
         * PG 折成小写，目录里原样存的名字裸插出去就是「表或视图不存在」（D-68，
         * 规则在方言层 {@code Dialect#bareResolvesTo}，界面不写分支）。
         */
        default boolean bareResolvesTo(String name) {
            return true;
        }
    }

    /**
     * 对 {@code text} 的 {@code caret} 处求补全。
     *
     * <p>只看光标**之前**的内容 —— 光标后面的半截词不参与上下文判断，
     * 替换区间也只盖到光标为止（标准编辑器的「打字打一半按接受」行为）。
     */
    static Proposal suggest(String text, int caret, Source source) {
        int length = text == null ? 0 : text.length();
        int pos = Math.max(0, Math.min(caret, length));
        List<SqlToken> tokens = SqlLexer.tokenize(text == null ? "" : text.substring(0, pos));

        // 语句窗口：只回看到最近一个分号 —— 别名与引用表不能从上一条语句串过来。
        int from = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (tokens.get(i).type() == SqlTokenType.SEMICOLON) {
                from = i + 1;
                break;
            }
        }
        List<SqlToken> stmt = new ArrayList<>();
        for (int i = from; i < tokens.size(); i++) {
            SqlTokenType type = tokens.get(i).type();
            if (type != SqlTokenType.WHITESPACE && type != SqlTokenType.COMMENT) {
                stmt.add(tokens.get(i));
            }
        }

        String partial = "";
        int partialStart = pos;
        int prevSize = stmt.size();
        if (!tokens.isEmpty()) {
            // 前缀的最后一个原始记号总是落在光标上：它决定「此刻正打在什么里面」。
            SqlToken lastRaw = tokens.get(tokens.size() - 1);
            switch (lastRaw.type()) {
                case COMMENT, STRING, QUOTED_IDENTIFIER, NUMBER, PARAMETER -> {
                    // 注释 / 字面量 / 数字 / 参数 / 引号套里 —— 这时候弹候选只会碍事。
                    return Proposal.none(pos, source.objectsReady());
                }
                default -> {
                    // 空白结尾 = 半截词为空（FROM 与 "FROM " 是两回事）；
                    // 只有 IDENTIFIER 落在光标上才算「正在打的词」。
                }
            }
            if (!stmt.isEmpty()) {
                SqlToken last = stmt.get(stmt.size() - 1);
                if (last.end() == pos
                        && (last.type() == SqlTokenType.IDENTIFIER
                        || last.type() == SqlTokenType.KEYWORD)) {
                    partial = last.text();
                    partialStart = last.start();
                    prevSize = stmt.size() - 1;
                }
            }
        }
        List<SqlToken> prev = stmt.subList(0, prevSize);

        // ── 限定名上下文：prev 以点号结尾 ──
        if (!prev.isEmpty() && isDot(prev.get(prev.size() - 1))) {
            List<String> chain = readQualifierChain(prev);
            if (chain.isEmpty()) {
                return Proposal.none(pos, source.objectsReady());
            }
            AliasMap aliases = collectAliases(stmt.subList(0, prevSize));
            String table = resolveQualifier(chain, aliases, source);
            if (table == null) {
                // 认不出这个限定符：不猜、不端全部 —— 诚实优先。
                return new Proposal(partialStart, pos, List.of(),
                        source.objectsReady(), List.of());
            }
            boolean loaded = source.columnsLoaded(table);
            List<Candidate> columns = filterColumns(source.columnsOf(table), table, partial, source);
            return new Proposal(partialStart, pos, columns, source.objectsReady() && loaded,
                    loaded ? List.of() : List.of(table));
        }

        // ── 表位 / 通用上下文 ──
        AliasMap aliases = collectAliases(prev);
        boolean tableContext = !prev.isEmpty() && isTableKeyword(prev.get(prev.size() - 1));
        List<Entry> objects = source.objects();
        List<String> missing = new ArrayList<>();
        List<Candidate> out = new ArrayList<>();

        if (tableContext) {
            addMatching(out, objects, Kind.TABLE, partial, source);
            addMatching(out, objects, Kind.VIEW, partial, source);
        } else {
            for (String name : aliases.referenced()) {
                if (!knownObject(name, objects)) {
                    continue;
                }
                if (!source.columnsLoaded(name)) {
                    missing.add(name);
                    continue;
                }
                filterColumnsInto(out, source.columnsOf(name), name, partial, source);
            }
            addMatching(out, objects, Kind.TABLE, partial, source);
            addMatching(out, objects, Kind.VIEW, partial, source);
            addMatching(out, objects, Kind.ROUTINE, partial, source);
            addKeywords(out, source.keywords(), partial);
        }
        boolean ready = source.objectsReady() && missing.isEmpty();
        return new Proposal(partialStart, pos, List.copyOf(out), ready, List.copyOf(missing));
    }

    // ─────────────────────────── 上下文工具 ───────────────────────────

    private static boolean isDot(SqlToken token) {
        return token.type() == SqlTokenType.PUNCTUATION && token.text().equals(".");
    }

    private static boolean isNameToken(SqlToken token) {
        return token.type() == SqlTokenType.IDENTIFIER
                || token.type() == SqlTokenType.QUOTED_IDENTIFIER
                || token.type() == SqlTokenType.KEYWORD;
    }

    private static boolean isTableKeyword(SqlToken token) {
        return token.type() == SqlTokenType.KEYWORD
                && TABLE_KEYWORDS.contains(token.text().toUpperCase(Locale.ROOT));
    }

    /** 从「… a . b . |」里倒着收出 [a, b]；起点撞上不认识的记号就停，收不出就交空。 */
    private static List<String> readQualifierChain(List<SqlToken> prev) {
        List<String> segments = new ArrayList<>();
        int idx = prev.size() - 2;
        while (idx >= 0) {
            SqlToken token = prev.get(idx);
            if (!isNameToken(token)) {
                break;
            }
            segments.add(0, stripQuotes(token.text()));
            idx -= 1;
            if (idx >= 0 && isDot(prev.get(idx))) {
                idx -= 1;
            } else {
                break;
            }
        }
        return segments;
    }

    /**
     * 限定符 → 表名：先看整串尾段是不是已知对象，再看别名，两段式（{@code schema.table}）
     * 也认尾段。都对不上交 null（调用方决定「不猜」）。
     */
    private static String resolveQualifier(List<String> chain, AliasMap aliases, Source source) {
        String last = chain.get(chain.size() - 1);
        List<Entry> objects = source.objects();
        for (Entry entry : objects) {
            if (entry.kind() != Kind.KEYWORD && entry.name().equalsIgnoreCase(last)) {
                return entry.name();
            }
        }
        String viaAlias = aliases.tableFor(last);
        if (viaAlias != null) {
            return viaAlias;
        }
        return null;
    }

    private static boolean knownObject(String name, List<Entry> objects) {
        for (Entry entry : objects) {
            if (entry.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 别名表：`FROM 表 [AS] 别名` 的两种写法都收，外加「这条语句引用了哪些表」。 */
    private static AliasMap collectAliases(List<SqlToken> tokens) {
        AliasMap map = new AliasMap();
        for (int i = 0; i < tokens.size(); i++) {
            if (!isTableKeyword(tokens.get(i))) {
                continue;
            }
            String name = readNameChain(tokens, i + 1);
            if (name == null) {
                continue;
            }
            map.reference(name);
            int after = skipNameChain(tokens, i + 1);
            if (after < tokens.size() && tokens.get(after).type() == SqlTokenType.KEYWORD
                    && tokens.get(after).text().equalsIgnoreCase("AS")
                    && after + 1 < tokens.size()
                    && tokens.get(after + 1).type() == SqlTokenType.IDENTIFIER) {
                map.alias(tokens.get(after + 1).text(), name);
            } else if (after < tokens.size()
                    && tokens.get(after).type() == SqlTokenType.IDENTIFIER) {
                map.alias(tokens.get(after).text(), name);
            }
            i = after;
        }
        return map;
    }

    /** 从 from 起读 `a` 或 `a.b`（或 `a.b.c`，取尾段）；读不出交 null。 */
    private static String readNameChain(List<SqlToken> tokens, int from) {
        if (from >= tokens.size() || !isNameToken(tokens.get(from))) {
            return null;
        }
        String name = stripQuotes(tokens.get(from).text());
        int idx = from;
        while (idx + 2 < tokens.size() && isDot(tokens.get(idx + 1))
                && isNameToken(tokens.get(idx + 2))) {
            idx += 2;
            name = stripQuotes(tokens.get(idx).text());
        }
        return name;
    }

    private static int skipNameChain(List<SqlToken> tokens, int from) {
        int idx = from;
        while (idx + 2 < tokens.size() && isDot(tokens.get(idx + 1))
                && isNameToken(tokens.get(idx + 2))) {
            idx += 2;
        }
        return idx + 1;
    }

    private static final class AliasMap {
        private final java.util.Map<String, String> byAlias =
                new java.util.HashMap<>();
        private final List<String> referenced = new ArrayList<>();

        void reference(String table) {
            for (String existing : referenced) {
                if (existing.equalsIgnoreCase(table)) {
                    return;
                }
            }
            referenced.add(table);
        }

        void alias(String alias, String table) {
            byAlias.putIfAbsent(alias.toLowerCase(Locale.ROOT), table);
            for (String existing : referenced) {
                if (existing.equalsIgnoreCase(table)) {
                    return;
                }
            }
            referenced.add(table);
        }

        String tableFor(String name) {
            return byAlias.get(name.toLowerCase(Locale.ROOT));
        }

        List<String> referenced() {
            return referenced;
        }
    }

    // ─────────────────────────── 候选装配 ───────────────────────────

    private static List<Candidate> filterColumns(List<String> columns, String table,
                                                 String partial, Source source) {
        List<Candidate> out = new ArrayList<>();
        filterColumnsInto(out, columns, table, partial, source);
        return List.copyOf(out);
    }

    private static void filterColumnsInto(List<Candidate> out, List<String> columns, String table,
                                          String partial, Source source) {
        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            if (out.size() >= MAX_CANDIDATES) {
                return;
            }
            if (!matches(column, partial) || !seen.add(column.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new Candidate(column, Kind.COLUMN, table, insertText(column, source)));
        }
    }

    private static void addMatching(List<Candidate> out, List<Entry> objects, Kind kind,
                                    String partial, Source source) {
        for (Entry entry : objects) {
            if (out.size() >= MAX_CANDIDATES) {
                return;
            }
            if (entry.kind() != kind || !matches(entry.name(), partial)) {
                continue;
            }
            out.add(new Candidate(entry.name(), kind, null, insertText(entry.name(), source)));
        }
    }

    private static void addKeywords(List<Candidate> out, Set<String> keywords, String partial) {
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(keywords));
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        for (String keyword : sorted) {
            if (out.size() >= MAX_CANDIDATES) {
                return;
            }
            if (!matches(keyword, partial)) {
                continue;
            }
            out.add(new Candidate(keyword.toUpperCase(Locale.ROOT), Kind.KEYWORD, null,
                    keyword.toUpperCase(Locale.ROOT)));
        }
    }

    /** 前缀匹配、不区分大小写 —— 与「打字即筛」的弹层行为同一口径。 */
    private static boolean matches(String name, String partial) {
        return partial.isEmpty() || name.regionMatches(true, 0, partial, 0, partial.length());
    }

    /**
     * 形状不安全（{@code my table}）**或裸写会被引擎折叠出别的对象**（Oracle 的 {@code users}）
     * 时加引号 —— 其余保持裸写。
     *
     * <p>⭐ D-68：旧口径只判形状。Oracle 目录里原样存的小写名（设计器加引号建的，见 D-22）
     * 形状纯 ASCII、被裸插进编辑器，一执行折成 USERS 就是 ORA-00942 ——
     * 报错发生在编辑器之外，用户看到的是「库自己坏了」。
     */
    private static String insertText(String name, Source source) {
        return isPlainIdentifier(name) && source.bareResolvesTo(name)
                ? name : source.quote(name);
    }

    private static boolean isPlainIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') || first == '_')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String stripQuotes(String raw) {
        if (raw.length() < 2) {
            return raw;
        }
        char first = raw.charAt(0);
        char last = raw.charAt(raw.length() - 1);
        if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
            return raw.substring(1, raw.length() - 1);
        }
        if (first == '[' && last == ']') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}
