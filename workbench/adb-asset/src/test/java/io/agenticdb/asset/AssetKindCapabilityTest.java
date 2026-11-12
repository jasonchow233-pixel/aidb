package io.agenticdb.asset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ⭐ EXT：非 SQL 族的「安全访问器」契约。
 *
 * <p>背景是一次**真实崩溃**：P1 落地 Redis、P2 落地对象存储之后，UI 里还有几处直接调
 * {@code profile.kind().dialect()} —— 非 SQL 成员没有方言，
 * {@link AssetKind#dialect()} 会抛 {@code IllegalStateException}。
 * 于是档案列表里只要有 Redis / 对象存储连接，打开连接管理或切换标签页就崩
 * （截图工作实测到：注册一条 Redis 档案后打开任意标签页，整个应用起不来）。
 *
 * <p>修法不是在那几处加 try/catch，而是把判据收到注册表层：
 * {@link AssetKind#hasHostPort()} 与 {@link AssetKind#switchesCatalogByReconnect()}
 * 对每一族都有答案。这条门禁钉住「访问器对所有 kind 都不抛」。
 */
class AssetKindCapabilityTest {

    @Test
    void everyKindAnswersTheCapabilityQuestionsWithoutThrowing() {
        for (AssetKind kind : AssetKind.values()) {
            assertThatCode(() -> kind.hasHostPort())
                    .as("%s 的 hasHostPort() 不得抛", kind.id())
                    .doesNotThrowAnyException();
            assertThatCode(() -> kind.switchesCatalogByReconnect())
                    .as("%s 的 switchesCatalogByReconnect() 不得抛", kind.id())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void sqlKindsKeepTheirDialectAnswer() {
        assertThat(AssetKind.SQLITE.hasHostPort())
                .as("SQLite 走文件路径，没有主机与端口")
                .isFalse();
        assertThat(AssetKind.POSTGRESQL.hasHostPort()).isTrue();
        assertThat(AssetKind.MYSQL.hasHostPort()).isTrue();
    }

    @Test
    void redisHasAPortButObjectStorageHasAnEndpointInstead() {
        assertThat(AssetKind.REDIS.hasHostPort())
                .as("Redis 有真端口（6379）")
                .isTrue();
        assertThat(AssetKind.OBJECT_STORE.hasHostPort())
                .as("对象存储走 endpoint（域名），那一列应显示「—」而不是 0")
                .isFalse();
    }

    @Test
    void nonSqlKindsNeverSwitchCatalogByReconnect() {
        assertThat(AssetKind.REDIS.switchesCatalogByReconnect())
                .as("Redis 的 db 下标是另一个键浏览器实例，不是「同一连接切库」")
                .isFalse();
        assertThat(AssetKind.OBJECT_STORE.switchesCatalogByReconnect()).isFalse();
        assertThat(AssetKind.POSTGRESQL.switchesCatalogByReconnect())
                .as("SQL 族仍然问方言")
                .isTrue();
    }
}
