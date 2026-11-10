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
}
