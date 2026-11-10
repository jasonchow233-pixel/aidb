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
        private final Kind kind;

        Node(String name, Kind kind) {
            this.name = name;
            this.kind = kind;
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        @Override
        public String toString() {
            return name + ":" + kind;
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

    /** 建图输入：一个对象名 + 类型 + （视图才有）定义体。 */
    public static final class Input {
        private final String name;
        private final Kind kind;
        private final String body;

        public Input(String name, Kind kind, String body) {
            this.name = name;
            this.kind = kind;
            this.body = body;
        }

        public static Input table(String name) {
            return new Input(name, Kind.TABLE, null);
        }

        public static Input view(String name, String body) {
            return new Input(name, Kind.VIEW, body);
        }
    }

    private final Map<String, Node> nodesByKey = new LinkedHashMap<>();
    private final Map<String, Set<String>> outgoing = new HashMap<>();
    private final Map<String, Set<String>> incoming = new HashMap<>();

    private SchemaDependencyGraph() {
    }

    /** 由一组对象（表 + 视图；视图带正文）建图。 */
    public static SchemaDependencyGraph of(Collection<Input> inputs) {
        SchemaDependencyGraph g = new SchemaDependencyGraph();
        for (Input in : inputs) {
            g.putNode(in.name, in.kind);
        }
        for (Input in : inputs) {
            if (in.kind != Kind.VIEW || in.body == null) {
                continue;
            }
            for (String ref : ViewBodyDependencies.extract(in.body)) {
                g.putNode(ref, Kind.UNKNOWN); // 被引用但不在清单里 —— 仍入图，灰显交给 UI
                g.link(in.name, ref);
            }
        }
        return g;
    }

    private void putNode(String name, Kind kind) {
        String key = keyOf(name);
        Node existing = nodesByKey.get(key);
        if (existing == null) {
            nodesByKey.put(key, new Node(name, kind));
        } else if (existing.kind() == Kind.UNKNOWN && kind != Kind.UNKNOWN) {
            // 提升：先当 UNKNOWN 入图，后来发现它其实是清单里的表/视图
            nodesByKey.put(key, new Node(name, kind));
        }
    }

    private void link(String from, String to) {
        String fk = keyOf(from);
        String tk = keyOf(to);
        if (fk.equals(tk)) {
            return; // 自环不要（视图引用自己名字的边界情况）
        }
        outgoing.computeIfAbsent(fk, k -> new HashSet<>()).add(tk);
        incoming.computeIfAbsent(tk, k -> new HashSet<>()).add(fk);
    }

    private static String keyOf(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
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
        return resolve(outgoing.get(keyOf(name)));
    }

    /** <b>直接</b>依赖这个节点的对象（入边来源）。 */
    public Set<Node> dependentsOf(String name) {
        return resolve(incoming.get(keyOf(name)));
    }

    /** <b>传递</b>依赖这个节点的全部对象（谁会因它消失而坏，含多层嵌套视图）。 */
    public Set<Node> transitiveDependentsOf(String name) {
        return traverse(keyOf(name), incoming);
    }

    /** <b>传递</b>被这个节点依赖的对象（顺藤摸到底）。 */
    public Set<Node> transitiveDependenciesOf(String name) {
        return traverse(keyOf(name), outgoing);
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
