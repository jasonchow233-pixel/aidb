package io.agenticdb.ui.shell;

import io.agenticdb.introspect.DependencyLayout;
import io.agenticdb.introspect.SchemaDependencyGraph;
import io.agenticdb.ui.I18n;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 只读依赖图页面（JavaFX）。吃一个已经建好的 {@link SchemaDependencyGraph} + 聚焦节点，
 * 渲染有向图：节点 = 表 / 视图（被引用但未入清单者灰显），箭头 = 「视图依赖该对象」的方向。
 *
 * <p>分层坐标由 {@link DependencyLayout} 算出（纯数学、可无头单测），这里只负责画。
 * 支持拖拽平移与滚轮缩放；聚焦节点的<b>上游依赖 + 下游被依赖</b>高亮，其余淡出。
 *
 * <p>⭐ 只读：不提供任何编辑入口——与「明确不做 Model 全量编辑器」一致。
 */
public final class DependencyGraphPage extends VBox {

    private static final double NODE_W = 184.0;
    private static final double NODE_H = 48.0;

    private final SchemaDependencyGraph graph;
    private final String focus;
    private final Group canvas = new Group();
    private final Scale zoom = new Scale(1.0, 1.0);
    private final Translate pan = new Translate(0.0, 0.0);
    private final Map<String, Rectangle> nodeBoxes = new HashMap<>();

    public DependencyGraphPage(SchemaDependencyGraph graph, String focus) {
        this.graph = graph;
        // 裸名焦点（右键菜单传 o.name()）→ 节点实际显示名（可能是 schema.name），否则高亮对不上
        this.focus = graph.resolveNodeName(focus);

        String title = focus == null || focus.isBlank()
                ? I18n.t("page.depgraph.title")
                : I18n.t("page.depgraph.title.focus", focus);
        Label header = new Label(title + "  ·  " + I18n.t("depgraph.hint"));
        header.setStyle("-fx-padding:6 10; -fx-font-size:12px;");
        header.getStyleClass().add("adb-graph-header");

        canvas.getTransforms().addAll(zoom, pan);
        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.getStyleClass().add("adb-graph-scroll");

        getChildren().addAll(header, scroll);
        setSpacing(0.0);
        render();
        wirePanZoom(scroll);
    }

    private void render() {
        Map<String, DependencyLayout.Point> pos = DependencyLayout.layout(graph, focus);
        Set<String> neighborhood = focusNeighborhood();

        // 先把边画在底层
        for (SchemaDependencyGraph.Edge edge : graph.edges()) {
            DependencyLayout.Point from = pos.get(edge.from().name());
            DependencyLayout.Point to = pos.get(edge.to().name());
            if (from == null || to == null) {
                continue;
            }
            boolean lit = neighborhood.isEmpty()
                    || (neighborhood.contains(edge.from().name().toLowerCase(Locale.ROOT))
                    && neighborhood.contains(edge.to().name().toLowerCase(Locale.ROOT)));
            drawEdge(from, to, lit);
        }
        // 再画节点
        for (Map.Entry<String, DependencyLayout.Point> entry : pos.entrySet()) {
            SchemaDependencyGraph.Node node = findNode(entry.getKey());
            if (node == null) {
                continue;
            }
            boolean lit = neighborhood.isEmpty()
                    || neighborhood.contains(node.name().toLowerCase(Locale.ROOT));
            drawNode(node, entry.getValue(), lit);
        }
    }

    private Set<String> focusNeighborhood() {
        Set<String> nb = new HashSet<>();
        if (focus == null || focus.isBlank()) {
            return nb; // 整库：全部点亮
        }
        nb.add(focus.toLowerCase(Locale.ROOT));
        graph.transitiveDependenciesOf(focus).forEach(n -> nb.add(n.name().toLowerCase(Locale.ROOT)));
        graph.transitiveDependentsOf(focus).forEach(n -> nb.add(n.name().toLowerCase(Locale.ROOT)));
        return nb;
    }

    private SchemaDependencyGraph.Node findNode(String name) {
        for (SchemaDependencyGraph.Node n : graph.nodes()) {
            if (n.name().equals(name)) {
                return n;
            }
        }
        return null;
    }

    private void drawNode(SchemaDependencyGraph.Node node, DependencyLayout.Point p, boolean lit) {
        double x = p.x() - NODE_W / 2.0;
        double y = p.y() - NODE_H / 2.0;

        Rectangle box = new Rectangle(NODE_W, NODE_H);
        String fill = switch (node.kind()) {
            case TABLE -> "#2f6fb0";
            case VIEW -> "#2e8b57";
            case UNKNOWN -> "#7f7f7f";
        };
        box.setFill(Color.web(fill));
        box.setArcWidth(8.0);
        box.setArcHeight(8.0);
        // R16：定义体缺失的视图画琥珀色虚线框，和高亮黄实线区分 —— 让「少边」在图上可见
        if (node.unresolved()) {
            box.setStroke(Color.web("#e08a1e"));
            box.setStrokeWidth(lit ? 2.5 : 1.5);
            box.getStrokeDashArray().addAll(6.0, 4.0);
        } else {
            box.setStroke(lit ? Color.web("#ffd479") : Color.web("#00000000"));
            box.setStrokeWidth(lit ? 2.5 : 0.0);
        }
        box.setLayoutX(x);
        box.setLayoutY(y);
        nodeBoxes.put(node.name(), box);

        Label name = new Label(node.name());
        name.setStyle("-fx-text-fill:white; -fx-font-weight:bold; -fx-font-size:13px;");
        name.setMaxWidth(NODE_W - 12);
        name.setLayoutX(x + 6);
        name.setLayoutY(y + 6);

        Label kind = new Label(I18n.t(node.unresolved() ? "depgraph.legend.unresolved" : kindKey(node.kind())));
        kind.setStyle("-fx-text-fill:" + (node.unresolved() ? "#ffd9a0" : "#e8e8e8")
                + "; -fx-font-size:10px;");
        kind.setLayoutX(x + 6);
        kind.setLayoutY(y + 26);

        if (!lit) {
            box.setOpacity(0.4);
            name.setOpacity(0.4);
            kind.setOpacity(0.4);
        }
        canvas.getChildren().addAll(box, name, kind);
    }

    private void drawEdge(DependencyLayout.Point from, DependencyLayout.Point to, boolean lit) {
        // 从依赖者（from）指向被依赖者（to）
        Point2D start = edgePoint(from, to);
        Point2D end = edgePoint(to, from);
        Line line = new Line(start.getX(), start.getY(), end.getX(), end.getY());
        line.setStroke(lit ? Color.web("#c9c9c9") : Color.web("#555555"));
        line.setStrokeWidth(lit ? 1.8 : 1.0);
        if (!lit) {
            line.setOpacity(0.35);
        }
        canvas.getChildren().add(line);

        // 箭头：指向 to
        double angle = Math.atan2(end.getY() - start.getY(), end.getX() - start.getX());
        double len = 10.0;
        Polygon arrow = new Polygon(
                end.getX(), end.getY(),
                end.getX() - len * Math.cos(angle - 0.4), end.getY() - len * Math.sin(angle - 0.4),
                end.getX() - len * Math.cos(angle + 0.4), end.getY() - len * Math.sin(angle + 0.4));
        arrow.setFill(line.getStroke());
        if (!lit) {
            arrow.setOpacity(0.35);
        }
        canvas.getChildren().add(arrow);
    }

    /** 两个节点中心点连线与边界的交点（近似，按矩形边界裁剪）。 */
    private Point2D edgePoint(DependencyLayout.Point box, DependencyLayout.Point other) {
        double cx = box.x();
        double cy = box.y();
        double dx = other.x() - cx;
        double dy = other.y() - cy;
        if (dx == 0 && dy == 0) {
            return new Point2D(cx, cy);
        }
        double scaleX = (NODE_W / 2.0) / Math.abs(dx);
        double scaleY = (NODE_H / 2.0) / Math.abs(dy);
        double t = Math.min(scaleX, scaleY);
        return new Point2D(cx + dx * t, cy + dy * t);
    }

    private static String kindKey(SchemaDependencyGraph.Kind kind) {
        return switch (kind) {
            case TABLE -> "depgraph.legend.table";
            case VIEW -> "depgraph.legend.view";
            case UNKNOWN -> "depgraph.legend.unknown";
        };
    }

    private void wirePanZoom(ScrollPane scroll) {
        // 平移：在画布上按下拖拽
        final double[] last = {0.0, 0.0};
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            last[0] = e.getSceneX();
            last[1] = e.getSceneY();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            pan.setX(pan.getX() + (e.getSceneX() - last[0]));
            pan.setY(pan.getY() + (e.getSceneY() - last[1]));
            last[0] = e.getSceneX();
            last[1] = e.getSceneY();
        });
        // 缩放：滚轮
        scroll.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown()) {
                return;
            }
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
            zoom.setX(Math.min(3.0, Math.max(0.3, zoom.getX() * factor)));
            zoom.setY(zoom.getX());
            e.consume();
        });
    }
}
