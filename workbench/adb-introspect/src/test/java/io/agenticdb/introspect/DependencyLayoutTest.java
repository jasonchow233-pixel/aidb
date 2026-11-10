package io.agenticdb.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyLayoutTest {

    private SchemaDependencyGraph graph() {
        return SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("t1"),
                SchemaDependencyGraph.Input.table("t2"),
                SchemaDependencyGraph.Input.view("v1", "SELECT * FROM t1"),
                SchemaDependencyGraph.Input.view("v2", "SELECT * FROM v1 JOIN t2 ON v1.a = t2.a")));
    }

    @Test
    void layersByDependencyDepth() {
        Map<String, DependencyLayout.Point> pos = DependencyLayout.layout(graph(), null);
        assertThat(pos).hasSize(4);
        // 表在最左（第 0 层），下游视图向右逐层推进
        assertThat(pos.get("t1").x()).isEqualTo(0.0);
        assertThat(pos.get("t2").x()).isEqualTo(0.0);
        assertThat(pos.get("v1").x()).isEqualTo(DependencyLayout.LAYER_GAP);
        assertThat(pos.get("v2").x()).isEqualTo(2 * DependencyLayout.LAYER_GAP);
    }

    @Test
    void cycleDoesNotHang() {
        SchemaDependencyGraph cyclic = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.view("v1", "SELECT * FROM v2"),
                SchemaDependencyGraph.Input.view("v2", "SELECT * FROM v1")));
        Map<String, DependencyLayout.Point> pos = DependencyLayout.layout(cyclic, null);
        // 不卡死、两个节点都给出了坐标
        assertThat(pos).hasSize(2);
        assertThat(pos.get("v1")).isNotNull();
        assertThat(pos.get("v2")).isNotNull();
    }
}
