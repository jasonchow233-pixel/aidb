package io.agenticdb.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ViewBodyDependenciesTest {

    private Set<String> ex(String body) {
        return ViewBodyDependencies.extract(body);
    }

    @Test
    void plainFrom() {
        assertThat(ex("SELECT * FROM users")).containsExactlyInAnyOrder("users");
    }

    @Test
    void joinOn() {
        assertThat(ex("SELECT o.id, u.name FROM orders o JOIN users u ON o.uid = u.id"))
                .containsExactlyInAnyOrder("orders", "users");
    }

    @Test
    void leftInnerJoin() {
        assertThat(ex("SELECT * FROM t1 LEFT JOIN t2 ON t1.a=t2.a INNER JOIN t3 ON t2.b=t3.b"))
                .containsExactlyInAnyOrder("t1", "t2", "t3");
    }

    @Test
    void schemaQualifiedKeepsLastSegment() {
        assertThat(ex("SELECT * FROM dbo.users")).containsExactlyInAnyOrder("users");
        assertThat(ex("SELECT * FROM catalog.dbo.users")).containsExactlyInAnyOrder("users");
    }

    @Test
    void doubleQuotedIdentifier() {
        assertThat(ex("SELECT * FROM \"My Table\"")).containsExactlyInAnyOrder("My Table");
    }

    @Test
    void backtickIdentifier() {
        assertThat(ex("SELECT a FROM `orders`")).containsExactlyInAnyOrder("orders");
    }

    @Test
    void bracketIdentifier() {
        assertThat(ex("SELECT a FROM [dbo].[users]")).containsExactlyInAnyOrder("users");
    }

    @Test
    void commaJoin() {
        assertThat(ex("SELECT * FROM a, b, c")).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void commaJoinWithAliases() {
        assertThat(ex("SELECT * FROM a x, b y WHERE x.id = y.id"))
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void subqueryIsScanned() {
        assertThat(ex("SELECT * FROM (SELECT id FROM t1) x JOIN t2 ON x.id = t2.id"))
                .containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void cteNamesAreNotExternalObjects() {
        assertThat(ex("WITH v AS (SELECT * FROM t) SELECT * FROM v"))
                .containsExactlyInAnyOrder("t");
    }

    @Test
    void multipleCtesExcluded() {
        assertThat(ex("WITH a AS (SELECT * FROM t1), b AS (SELECT * FROM t2) "
                + "SELECT * FROM a JOIN b ON a.x = b.x"))
                .containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void nestedViewReferencesAreKept() {
        assertThat(ex("SELECT * FROM v1 JOIN v2 ON v1.id = v2.parent"))
                .containsExactlyInAnyOrder("v1", "v2");
    }

    @Test
    void aliasAfterFromNotCounted() {
        assertThat(ex("SELECT * FROM users u WHERE u.id = 1")).containsExactlyInAnyOrder("users");
    }

    @Test
    void commentWithFakeFromIgnored() {
        assertThat(ex("SELECT * FROM -- FROM fake\norders")).containsExactlyInAnyOrder("orders");
        assertThat(ex("SELECT * FROM /* FROM fake */ t")).containsExactlyInAnyOrder("t");
    }

    @Test
    void stringLiteralWithFromIgnored() {
        assertThat(ex("SELECT * FROM t WHERE name = 'FROM fake'"))
                .containsExactlyInAnyOrder("t");
    }

    @Test
    void createViewPrefixStripped() {
        assertThat(ex("CREATE VIEW v AS SELECT * FROM t")).containsExactlyInAnyOrder("t");
        assertThat(ex("CREATE OR REPLACE VIEW v AS SELECT a FROM b"))
                .containsExactlyInAnyOrder("b");
    }

    @Test
    void quotedNameWithDotInside() {
        assertThat(ex("SELECT * FROM \"schema.with.dot\".\"table\"")).containsExactlyInAnyOrder("table");
    }

    @Test
    void emptyAndNullAreSafe() {
        assertThat(ex("")).isEmpty();
        assertThat(ex(null)).isEmpty();
        assertThat(ex("SELECT 1")).isEmpty();
    }

    @Test
    void recursionDoesNotLoopForever() {
        assertThat(ex("WITH RECURSIVE cte AS (SELECT id FROM base UNION ALL "
                + "SELECT id FROM cte) SELECT * FROM cte"))
                .containsExactlyInAnyOrder("base");
    }

    // ── R16：限定名保留 ─────────────────────────────────────────────

    private List<String> qualified(String body) {
        return ViewBodyDependencies.extractQualified(body).stream()
                .map(ViewBodyDependencies.ObjectRef::canonical)
                .toList();
    }

    @Test
    void qualifiedKeepsSchemaPrefix() {
        assertThat(qualified("SELECT * FROM dbo.users"))
                .containsExactly("dbo.users");
        assertThat(qualified("SELECT * FROM catalog.dbo.users"))
                .containsExactly("catalog.dbo.users");
    }

    @Test
    void unqualifiedStaysBare() {
        assertThat(qualified("SELECT * FROM users")).containsExactly("users");
    }

    @Test
    void twoSchemasSameBareNameAreNotMerged() {
        // 这正是 R16 的根因：裸名集合会把 sales.orders / hr.orders 并成 "orders"
        assertThat(ex("SELECT * FROM sales.orders JOIN hr.orders ON sales.orders.id = hr.orders.id"))
                .containsExactly("orders"); // extract 仍保持旧行为（向后兼容）
        assertThat(qualified("SELECT * FROM sales.orders JOIN hr.orders ON 1=1"))
                .containsExactlyInAnyOrder("sales.orders", "hr.orders");
    }

    @Test
    void quotedQualifierIsPreserved() {
        assertThat(qualified("SELECT * FROM [dbo].[users]")).containsExactly("dbo.users");
        assertThat(qualified("SELECT * FROM \"sales\".\"orders\""))
                .containsExactly("sales.orders");
        // 引号内含点：限定前缀整体保留
        assertThat(qualified("SELECT * FROM \"schema.with.dot\".\"table\""))
                .containsExactly("schema.with.dot.table");
    }

    @Test
    void qualifiedDedupIsByCanonicalName() {
        assertThat(qualified("SELECT * FROM a.t JOIN a.t t2 ON 1=1"))
                .containsExactly("a.t");
    }

    @Test
    void objectRefExposesParts() {
        ViewBodyDependencies.ObjectRef qualified = new ViewBodyDependencies.ObjectRef("dbo", "users");
        assertThat(qualified.qualified()).isTrue();
        assertThat(qualified.name()).isEqualTo("users");
        assertThat(qualified.qualifier()).isEqualTo("dbo");
        assertThat(qualified.canonical()).isEqualTo("dbo.users");

        ViewBodyDependencies.ObjectRef bare = new ViewBodyDependencies.ObjectRef(null, "users");
        assertThat(bare.qualified()).isFalse();
        assertThat(bare.canonical()).isEqualTo("users");
    }
}
