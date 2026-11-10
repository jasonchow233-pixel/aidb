package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link SchemaDependencyGraph} 算成<b>分层坐标</b>（纯数学，不碰 JavaFX / 不碰库），
 * 所以能在无头环境单测。渲染层只消费这里的坐标。
 *
 * <p>分层口径：边是「视图 → 它读的对象」。依赖别人的多、被别人依赖的少——
 * 把<b>什么都不依赖</b>的节点（通常是表）放第 0 层，越往右越「靠下游的视图」。
 * 某层 = 1 + max(它依赖对象的层)；遇环（视图互相依赖）就地截断到 0，不卡死。
 */
public final class DependencyLayout {

    private DependencyLayout() {
    }

    /** 一个坐标点（图坐标，渲染层再缩放 / 平移）。 */
    public record Point(double x, double y) {
    }

    /** 横向层间距、纵向节点间距（图坐标单位，渲染时再乘缩放）。 */
    public static final double LAYER_GAP = 260.0;
    public static final double ROW_GAP = 90.0;

    /**
     * @param graph 依赖图
     * @param focus 聚焦节点名（仅用于把聚焦层放中间，不影响整体分层逻辑）
     * @return 节点名 → 坐标
     */
    public static Map<String, Point> layout(SchemaDependencyGraph graph, String focus) {
        Map<String, Integer> layerOf = new HashMap<>();
        for (SchemaDependencyGraph.Node node : graph.nodes()) {
            computeLayer(node.name(), graph, layerOf, new java.util.HashSet<>());
        }
        // 按层分组
        Map<Integer, List<SchemaDependencyGraph.Node>> byLayer = new LinkedHashMap<>();
        for (SchemaDependencyGraph.Node node : graph.nodes()) {
            int l = layerOf.getOrDefault(node.name(), 0);
            byLayer.computeIfAbsent(l, k -> new ArrayList<>()).add(node);
        }
        // 聚焦层尽量居中：算出每层的纵向半高，整体偏移
        int maxLayer = byLayer.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int focusLayer = focus == null ? maxLayer / 2 : layerOf.getOrDefault(focus, maxLayer / 2);

        Map<String, Point> positions = new HashMap<>();
        for (Map.Entry<Integer, List<SchemaDependencyGraph.Node>> entry : byLayer.entrySet()) {
            int layer = entry.getKey();
            List<SchemaDependencyGraph.Node> row = entry.getValue();
            double x = layer * LAYER_GAP;
            double totalHeight = (row.size() - 1) * ROW_GAP;
            // 以聚焦层为 0 偏移，向上/下铺开
            for (int i = 0; i < row.size(); i++) {
                double y = (i * ROW_GAP) - totalHeight / 2 + (focusLayer - layer) * ROW_GAP;
                positions.put(row.get(i).name(), new Point(x, y));
            }
        }
        return positions;
    }

    /** 仅算每个节点的层（带环检测）。 */
    private static int computeLayer(String name, SchemaDependencyGraph graph,
                                    Map<String, Integer> memo, Set<String> visiting) {
        Integer cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(name)) {
            return 0; // 环：截断
        }
        Set<SchemaDependencyGraph.Node> deps = graph.dependenciesOf(name);
        int layer;
        if (deps == null || deps.isEmpty()) {
            layer = 0;
        } else {
            int max = 0;
            for (SchemaDependencyGraph.Node dep : deps) {
                max = Math.max(max, computeLayer(dep.name(), graph, memo, visiting));
            }
            layer = max + 1;
        }
        visiting.remove(name);
        memo.put(name, layer);
        return layer;
    }
}
