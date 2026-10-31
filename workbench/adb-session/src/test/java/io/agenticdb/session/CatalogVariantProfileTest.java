package io.agenticdb.session;

import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U1：catalog 虚拟连接的 id 约定。
 *
 * <p>「切库 = 换连接」靠一条字符串约定成立：{@code 父id@库名}。
 * 这条约定散开就会出事（会话复用错、标签页撞车、口令找不到），
 * 所以把构造 / 解析 / 判定全部钉在 {@link ConnectionProfile} 上。
 */
class CatalogVariantProfileTest {

    private static ConnectionProfile parent() {
        return new ConnectionProfile("abc-123", "生产库", DialectKind.MYSQL,
                "10.0.0.8", 3306, "orders", "root", true, 42L, "生产",
                Map.of("useSSL", "true"));
    }

    @Test
    void variantKeepsEverythingButSwitchesTheDatabase() {
        ConnectionProfile variant = parent().catalogVariant("audit");

        assertThat(variant.database()).isEqualTo("audit");
        assertThat(variant.host()).isEqualTo("10.0.0.8");
        assertThat(variant.port()).isEqualTo(3306);
        assertThat(variant.username()).isEqualTo("root");
        assertThat(variant.options()).containsEntry("useSSL", "true");
        assertThat(variant.name())
                .as("名字要能看出是哪个库，否则状态栏分不清")
                .contains("audit");
    }

    @Test
    void variantIdIsDerivedFromTheParent() {
        ConnectionProfile variant = parent().catalogVariant("audit");

        assertThat(variant.id()).isEqualTo("abc-123@audit");
        assertThat(ConnectionProfile.catalogVariantId("abc-123", "audit"))
                .isEqualTo("abc-123@audit");
        assertThat(ConnectionProfile.isCatalogVariantId(variant.id())).isTrue();
        assertThat(ConnectionProfile.isCatalogVariantId("abc-123"))
                .as("档案 id（UUID）不是虚拟连接").isFalse();
        assertThat(ConnectionProfile.isCatalogVariantId(null)).isFalse();
    }

    @Test
    void variantIdRoundTripsThroughParentAndCatalog() {
        String variantId = ConnectionProfile.catalogVariantId("parent-1", "my.db");

        assertThat(ConnectionProfile.parentOfCatalogVariant(variantId))
                .isEqualTo("parent-1");
        assertThat(ConnectionProfile.catalogOfCatalogVariant(variantId))
                .isEqualTo("my.db");
        // 库名里带 @ 也不能把爹弄丢 —— 取最后一个分隔符。
        assertThat(ConnectionProfile.parentOfCatalogVariant("p@a@b")).isEqualTo("p@a");
        assertThat(ConnectionProfile.catalogOfCatalogVariant("p@a@b")).isEqualTo("b");
    }

    @Test
    void variantIsADifferentConnectionEvenForTheSameServer() {
        ConnectionProfile parent = parent();
        ConnectionProfile variant = parent.catalogVariant("audit");

        assertThat(variant)
                .as("equals 按 id —— 虚拟连接与父连接不是同一条连接")
                .isNotEqualTo(parent);
        assertThat(variant.spec("secret").database()).isEqualTo("audit");
        assertThat(variant.spec("secret").jdbcUrl())
                .as("同主机同端口，只是换了库名")
                .isEqualTo(parent.spec("secret").jdbcUrl().replace("/orders", "/audit"));
    }

    @Test
    void everyReconnectDialectSwitchesTheDatabaseInItsUrl() {
        for (DialectKind kind : java.util.List.of(
                DialectKind.MYSQL, DialectKind.POSTGRESQL, DialectKind.SQLSERVER)) {
            ConnectionProfile parent = new ConnectionProfile("id-" + kind.id(), "生产库", kind,
                    "db.internal", kind.dialect().defaultPort(), "main", "user",
                    false, 0, "", Map.of());
            ConnectionProfile variant = parent.catalogVariant("audit");

            assertThat(variant.database())
                    .as("%s 的库名要换掉", kind).isEqualTo("audit");
            assertThat(variant.spec(null).jdbcUrl())
                    .as("%s 的连接串要指向新库，而不是继续连旧库", kind)
                    .contains("audit").doesNotContain("main");
        }
    }

    @Test
    void sqliteProfilesNeverLookLikeVariants() {
        ConnectionProfile sqlite = ConnectionProfile.sqlite("demo", "演示库", "/tmp/x.db");

        assertThat(ConnectionProfile.isCatalogVariantId(sqlite.id()))
                .as("演示库的 id 就是「demo」，不带分隔符 —— 不会被误判成虚拟连接")
                .isFalse();
    }
}
