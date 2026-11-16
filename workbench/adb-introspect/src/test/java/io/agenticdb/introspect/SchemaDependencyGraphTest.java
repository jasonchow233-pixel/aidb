package io.agenticdb.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDependencyGraphTest {

    private SchemaDependencyGraph build() {
        return SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("t1"),
                SchemaDependencyGraph.Input.table("t2"),
                SchemaDependencyGraph.Input.view("v1", "SELECT * FROM t1"),
                SchemaDependencyGraph.Input.view("v2", "SELECT * FROM v1 JOIN t2 ON v1.a = t2.a")));
    }

    private Set<String> names(Set<SchemaDependencyGraph.Node> nodes) {
        return nodes.stream().map(SchemaDependencyGraph.Node::name).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void forwardEdgesResolved() {
        SchemaDependencyGraph g = build();
        assertThat(names(g.dependenciesOf("v1"))).containsExactlyInAnyOrder("t1");
        assertThat(names(g.dependenciesOf("v2"))).containsExactlyInAnyOrder("v1", "t2");
        assertThat(names(g.dependenciesOf("t1"))).isEmpty();
    }

    @Test
    void directDependents() {
        SchemaDependencyGraph g = build();
        assertThat(names(g.dependentsOf("t1"))).containsExactlyInAnyOrder("v1");
        assertThat(names(g.dependentsOf("v1"))).containsExactlyInAnyOrder("v2");
    }

    @Test
    void transitiveDependentsThroughNestedViews() {
        SchemaDependencyGraph g = build();
        // t1 消失会连累 v1，进而连累 v2
        assertThat(names(g.transitiveDependentsOf("t1"))).containsExactlyInAnyOrder("v1", "v2");
        assertThat(names(g.transitiveDependentsOf("v1"))).containsExactlyInAnyOrder("v2");
        assertThat(names(g.transitiveDependentsOf("t2"))).containsExactlyInAnyOrder("v2");
    }

    @Test
    void transitiveDependenciesReachBaseTables() {
        SchemaDependencyGraph g = build();
        assertThat(names(g.transitiveDependenciesOf("v2"))).containsExactlyInAnyOrder("v1", "t1", "t2");
    }

    @Test
    void referencedButUnknownObjectBecomesUnknownNode() {
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.view("v", "SELECT * FROM t JOIN external_x ON t.a = external_x.a"),
                SchemaDependencyGraph.Input.table("t")));
        SchemaDependencyGraph.Node unknown = g.nodes().stream()
                .filter(n -> n.name().equals("external_x")).findFirst().orElseThrow();
        assertThat(unknown.kind()).isEqualTo(SchemaDependencyGraph.Kind.UNKNOWN);
        assertThat(names(g.dependentsOf("external_x"))).containsExactlyInAnyOrder("v");
    }

    @Test
    void caseInsensitiveMatching() {
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("Users"),
                SchemaDependencyGraph.Input.view("v", "SELECT * FROM users")));
        assertThat(names(g.dependenciesOf("v"))).containsExactlyInAnyOrder("Users");
        assertThat(names(g.dependentsOf("Users"))).containsExactlyInAnyOrder("v");
    }

    @Test
    void selfReferenceIsDropped() {
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.view("v", "SELECT * FROM v")));
        assertThat(g.edges()).isEmpty();
    }

    @Test
    void edgeDirectionIsDependerToDependency() {
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("t"),
                SchemaDependencyGraph.Input.view("v", "SELECT * FROM t")));
        assertThat(g.edges()).hasSize(1);
        SchemaDependencyGraph.Edge e = g.edges().get(0);
        assertThat(e.from().name()).isEqualTo("v");
        assertThat(e.to().name()).isEqualTo("t");
    }

    // ── R16：限定名 / 缺定义体 ────────────────────────────────────────

    @Test
    void qualifiedRefPicksExactSchemaNoMerge() {
        // sales.orders 与 hr.orders 同名不同 schema：限定引用只连到对的那一个
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("orders", "sales"),
                SchemaDependencyGraph.Input.table("orders", "hr"),
                SchemaDependencyGraph.Input.view("v", "sales", "SELECT * FROM sales.orders")));
        assertThat(names(g.dependenciesOf("v"))).containsExactly("sales.orders");
        assertThat(names(g.dependenciesOf("sales.orders"))).isEmpty();
    }

    @Test
    void sameSchemaBareRefResolves() {
        // 视图在 sales schema，裸名 orders 先按同 schema 命中
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("orders", "sales"),
                SchemaDependencyGraph.Input.table("orders", "hr"),
                SchemaDependencyGraph.Input.view("v", "sales", "SELECT * FROM orders")));
        assertThat(names(g.dependenciesOf("v"))).containsExactly("sales.orders");
    }

    @Test
    void ambiguousBareRefBecomesExternal() {
        // 两个带 schema 的同名表 + 裸引用且视图无 schema → 不臆造合并，按 UNKNOWN 外部
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("orders", "sales"),
                SchemaDependencyGraph.Input.table("orders", "hr"),
                SchemaDependencyGraph.Input.view("v", "SELECT * FROM orders")));
        SchemaDependencyGraph.Node dep = g.dependenciesOf("v").iterator().next();
        assertThat(dep.kind()).isEqualTo(SchemaDependencyGraph.Kind.UNKNOWN);
        assertThat(dep.name()).isEqualTo("orders");
    }

    @Test
    void qualifiedRefMissingFromCatalogBecomesExternal() {
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("orders", "sales"),
                SchemaDependencyGraph.Input.view("v", "SELECT * FROM ghost.orders")));
        SchemaDependencyGraph.Node dep = g.dependenciesOf("v").iterator().next();
        assertThat(dep.kind()).isEqualTo(SchemaDependencyGraph.Kind.UNKNOWN);
        assertThat(dep.name()).isEqualTo("ghost.orders");
    }

    @Test
    void unresolvedViewIsRetainedAndMarked() {
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("t"),
                SchemaDependencyGraph.Input.view("v_ok", "SELECT * FROM t"),
                SchemaDependencyGraph.Input.view("v_missing", null)));
        // 缺定义体的视图仍在图里，但标 unresolved，且没有出边（不再被静默跳过）
        assertThat(g.nodes()).extracting(SchemaDependencyGraph.Node::name).contains("v_missing");
        assertThat(g.unresolvedViews()).extracting(SchemaDependencyGraph.Node::name)
                .containsExactly("v_missing");
        assertThat(g.unresolvedViews().get(0).kind()).isEqualTo(SchemaDependencyGraph.Kind.VIEW);
        assertThat(g.dependenciesOf("v_missing")).isEmpty();
        assertThat(g.unresolvedViews()).extracting(SchemaDependencyGraph.Node::name)
                .doesNotContain("v_ok");
    }

    @Test
    void nodeCarriesSchema() {
        SchemaDependencyGraph g = SchemaDependencyGraph.of(List.of(
                SchemaDependencyGraph.Input.table("orders", "sales")));
        SchemaDependencyGraph.Node n = g.nodes().get(0);
        assertThat(n.name()).isEqualTo("sales.orders");
        assertThat(n.schema()).isEqualTo("sales");
    }
}
