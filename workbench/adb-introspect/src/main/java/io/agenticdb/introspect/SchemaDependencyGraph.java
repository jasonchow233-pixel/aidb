package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一个 schema 内表 / 视图的<b>有向依赖图</b>（只读模型，不碰库、不碰 UI，可无头单测）。
 *
 * <p>边方向约定：{@code 视图 → 它读的对象}（视图依赖该对象）。
 * 于是「这个节点依赖谁」走<b>出边</b>，「谁依赖这个节点」（删表前警告要用的那一侧）走<b>入边</b>。
 * 反向边不单独查库——所有视图的前向边都解析出来后，入边自然推导得出，一套数据覆盖双向。
 *
 * <p>节点名按大小写不敏感归并（与 {@code existingTablesFor} / {@code dependentViews} 的口径一致）；
 * 被引用但不在入参清单里的对象按 {@link Kind#UNKNOWN} 计入（跨 schema / 系统表这类），
 * UI 可据此灰显——图本身不丢边。
 *
 * <p>⭐ R16（限定名与缺定义体）：
 * <ul>
 *   <li>建图节点 key = {@code schema.name}（schema 为空则是裸名）。视图正文里带限定前缀的引用
 *       （{@code sales.orders}）先按全名精确匹配；命中清单对象就连它，不再被压成裸名与
 *       {@code hr.orders} 错误合并；</li>
 *   <li>裸名引用只匹配无 schema 的清单对象；同 schema 优先、再按裸名索引，仍歧义（多个同名
 *       或零个）按 UNKNOWN「外部」灰显——宁可少连，不臆造合并；</li>
 *   <li>{@code body == null} 的视图<b>不再被静默跳过</b>：它照常入图，但标 {@link Node#unresolved()}
 *       （{@link #unresolvedViews()} 可取全部），UI 据此写明「定义体缺失，依赖可能不完整」。
 *       这样「缺定义少边」从看不见变成看得见。</li>
 * </ul>
 */
public final class SchemaDependencyGraph {

    /** 节点类型；{@code UNKNOWN} = 被引用但不在清单里的对象。 */
    public enum Kind {
        TABLE,
        VIEW,
        UNKNOWN
    }

    /** 图的一个节点。 */
    public static final class Node {
        private final String name;
        private final String schema;
        private final Kind kind;
        private final boolean unresolved;

        Node(String name, String schema, Kind kind, boolean unresolved) {
            this.name = name;
            this.schema = schema;
            this.kind = kind;
            this.unresolved = unresolved;
        }

        public String name() {
            return name;
        }

        public String schema() {
            return schema;
        }

        public Kind kind() {
            return kind;
        }

        /** 仅对视图有意义：定义体读不到，出边可能不完整。 */
        public boolean unresolved() {
            return unresolved;
        }

        @Override
        public String toString() {
            return name + ":" + kind + (unresolved ? ":unresolved" : "");
        }
    }

    /** 一条有向边：{@code from} 依赖 {@code to}。 */
    public static final class Edge {
        private final Node from;
        private final Node to;

        Edge(Node from, Node to) {
            this.from = from;
            this.to = to;
        }

        public Node from() {
            return from;
        }

        public Node to() {
            return to;
        }

        @Override
        public String toString() {
            return from.name() + " -> " + to.name();
        }
    }

    /** 建图输入：一个对象名 + 类型 + 可选 schema + （视图才有）定义体。 */
    public static final class Input {
        private final String name;
        private final String schema;
        private final Kind kind;
        private final String body;

        /** 兼容构造：无 schema（SQLite / 单 schema 场景）。 */
        public Input(String name, Kind kind, String body) {
            this(name, null, kind, body);
        }

        public Input(String name, String schema, Kind kind, String body) {
            this.name = name;
            this.schema = schema == null || schema.isBlank() ? null : schema;
            this.kind = kind;
            this.body = body;
        }

        public static Input table(String name) {
            return new Input(name, Kind.TABLE, null);
        }

        public static Input table(String name, String schema) {
            return new Input(name, schema, Kind.TABLE, null);
        }

        public static Input view(String name, String body) {
            return new Input(name, Kind.VIEW, body);
        }

        public static Input view(String name, String schema, String body) {
            return new Input(name, schema, Kind.VIEW, body);
        }
    }

    private final Map<String, Node> nodesByKey = new LinkedHashMap<>();
    private final Map<String, Set<String>> outgoing = new HashMap<>();
    private final Map<String, Set<String>> incoming = new HashMap<>();
    /** 无 schema 的清单对象，按裸名（小写）索引其 key；供裸名引用解析。 */
    private final Map<String, Set<String>> bareKeys = new HashMap<>();

    private SchemaDependencyGraph() {
    }

    /** 由一组对象（表 + 视图；视图带正文）建图。 */
    public static SchemaDependencyGraph of(Collection<Input> inputs) {
        SchemaDependencyGraph g = new SchemaDependencyGraph();
        // 第一遍：所有对象都入图（含 body==null 的视图，标 unresolved）。
        for (Input in : inputs) {
            g.putNode(in.name, in.schema, in.kind,
                    in.kind == Kind.VIEW && (in.body == null || in.body.isBlank()));
        }
        // 裸名索引只收无 schema 的清单对象；带 schema 的对象必须用限定名命中。
        for (Input in : inputs) {
            if (in.schema == null) {
                g.bareKeys.computeIfAbsent(keyOf(in.name), k -> new HashSet<>())
                        .add(keyOf(null, in.name));
            }
        }
        // 第二遍：解析每条有定义体的视图的前向边。
        for (Input in : inputs) {
            if (in.kind != Kind.VIEW || in.body == null || in.body.isBlank()) {
                continue;
            }
            String fromKey = keyOf(in.schema, in.name);
            for (ViewBodyDependencies.ObjectRef ref : ViewBodyDependencies.extractQualified(in.body)) {
                g.linkKeys(fromKey, g.resolveRef(in.schema, ref));
            }
        }
        return g;
    }

    /**
     * 把一个引用解析成目标节点 key：
     * <ol>
     *   <li>带限定前缀 → 按 {@code qualifier.name} 全名精确匹配；未命中按 UNKNOWN；</li>
     *   <li>裸名 → 同 schema 优先，再按裸名索引；唯一命中才连，否则按 UNKNOWN。</li>
     * </ol>
     */
    private String resolveRef(String viewSchema, ViewBodyDependencies.ObjectRef ref) {
        if (ref.qualified()) {
            String exact = keyOf(ref.qualifier(), ref.name());
            if (nodesByKey.containsKey(exact)) {
                return exact;
            }
            putNode(ref.canonical(), null, Kind.UNKNOWN, false);
            return keyOf(null, ref.canonical());
        }
        if (viewSchema != null) {
            String sameSchema = keyOf(viewSchema, ref.name());
            if (nodesByKey.containsKey(sameSchema)) {
                return sameSchema;
            }
        }
        Set<String> bare = bareKeys.get(keyOf(ref.name()));
        if (bare != null && bare.size() == 1) {
            return bare.iterator().next();
        }
        putNode(ref.name(), null, Kind.UNKNOWN, false);
        return keyOf(null, ref.name());
    }

    private void putNode(String name, String schema, Kind kind, boolean unresolved) {
        String key = keyOf(schema, name);
        String display = schema == null ? name : schema + "." + name;
        Node existing = nodesByKey.get(key);
        if (existing == null) {
            nodesByKey.put(key, new Node(display, schema, kind, unresolved));
        } else if (existing.kind() == Kind.UNKNOWN && kind != Kind.UNKNOWN) {
            // 提升：先当 UNKNOWN 入图，后来发现它其实是清单里的表/视图
            nodesByKey.put(key, new Node(display, schema, kind, unresolved));
        }
    }

    private void linkKeys(String fromKey, String toKey) {
        if (fromKey.equals(toKey)) {
            return; // 自环不要（视图引用自己名字的边界情况）
        }
        outgoing.computeIfAbsent(fromKey, k -> new HashSet<>()).add(toKey);
        incoming.computeIfAbsent(toKey, k -> new HashSet<>()).add(fromKey);
    }

    /** 全名 key：schema 非空则 {@code schema.name}，否则裸名；大小写不敏感。 */
    private static String keyOf(String schema, String name) {
        String bare = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return schema == null ? bare : schema.toLowerCase(Locale.ROOT) + "." + bare;
    }

    private static String keyOf(String name) {
        return keyOf(null, name);
    }

    /**
     * 公开查询入口的 key 解析：既接受全名（{@code sales.orders}），也接受裸名（{@code orders}）。
     * 先按精确 key 找；找不到再把入参当裸名，看是否有唯一节点以 {@code .裸名} 结尾。
     * 跨 schema 同名导致裸名歧义时不猜，退回原 key（于是查询返回空，不臆造连边）。
     */
    private String resolveKey(String name) {
        if (name == null) {
            return "";
        }
        String direct = keyOf(name);
        if (nodesByKey.containsKey(direct)) {
            return direct;
        }
        String bare = direct;
        List<String> matches = new ArrayList<>();
        for (String k : nodesByKey.keySet()) {
            if (k.endsWith("." + bare)) {
                matches.add(k);
            }
        }
        return matches.size() == 1 ? matches.get(0) : direct;
    }

    /** 全部节点（保首次出现顺序）。 */
    public List<Node> nodes() {
        return new ArrayList<>(nodesByKey.values());
    }

    /** 全部有向边。 */
    public List<Edge> edges() {
        List<Edge> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : outgoing.entrySet()) {
            Node from = nodesByKey.get(e.getKey());
            for (String toKey : e.getValue()) {
                result.add(new Edge(from, nodesByKey.get(toKey)));
            }
        }
        return result;
    }

    /** 这个节点<b>依赖</b>的对象（出边目标，直接）。 */
    public Set<Node> dependenciesOf(String name) {
        return resolve(outgoing.get(resolveKey(name)));
    }

    /** <b>直接</b>依赖这个节点的对象（入边来源）。 */
    public Set<Node> dependentsOf(String name) {
        return resolve(incoming.get(resolveKey(name)));
    }

    /** <b>传递</b>依赖这个节点的全部对象（谁会因它消失而坏，含多层嵌套视图）。 */
    public Set<Node> transitiveDependentsOf(String name) {
        return traverse(resolveKey(name), incoming);
    }

    /** <b>传递</b>被这个节点依赖的对象（顺藤摸到底）。 */
    public Set<Node> transitiveDependenciesOf(String name) {
        return traverse(resolveKey(name), outgoing);
    }

    /** 把裸名 / 全名焦点规范化成节点实际显示名；匹配不到则原样返回。 */
    public String resolveNodeName(String name) {
        Node node = nodesByKey.get(resolveKey(name));
        return node == null ? name : node.name();
    }

    /** 定义体读不到、出边可能不完整的视图节点（R16：让「少边」可见）。 */
    public List<Node> unresolvedViews() {
        List<Node> out = new ArrayList<>();
        for (Node n : nodesByKey.values()) {
            if (n.kind() == Kind.VIEW && n.unresolved()) {
                out.add(n);
            }
        }
        return out;
    }

    private Set<Node> resolve(Set<String> keys) {
        Set<Node> out = new HashSet<>();
        if (keys != null) {
            for (String k : keys) {
                out.add(nodesByKey.get(k));
            }
        }
        return out;
    }

    private Set<Node> traverse(String start, Map<String, Set<String>> adjacency) {
        Set<Node> seen = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        Set<String> visited = new HashSet<>();
        if (adjacency.get(start) != null) {
            frontier.addAll(adjacency.get(start));
        }
        while (!frontier.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String k : frontier) {
                if (!visited.add(k)) {
                    continue;
                }
                seen.add(nodesByKey.get(k));
                Set<String> succ = adjacency.get(k);
                if (succ != null) {
                    next.addAll(succ);
                }
            }
            frontier = next;
        }
        return seen;
    }
}
