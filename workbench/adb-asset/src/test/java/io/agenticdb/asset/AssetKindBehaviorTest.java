package io.agenticdb.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetKindBehaviorTest {

    @Test
    void sqlMembersDelegateToTheirDialect() {
        assertThat(AssetKind.MYSQL.isSql()).isTrue();
        assertThat(AssetKind.MYSQL.dialectKind()).isEqualTo(DialectKind.MYSQL);
        assertThat(AssetKind.MYSQL.defaultPort()).isEqualTo(DialectKind.MYSQL.dialect().defaultPort());
        assertThat(AssetKind.SQLITE.usesFilePath()).isTrue();
        assertThat(AssetKind.POSTGRESQL.supportsColumnComment()).isTrue();
    }

    @Test
    void nonSqlMembersHaveNoDialect() {
        assertThat(AssetKind.REDIS.isSql()).isFalse();
        assertThat(AssetKind.REDIS.isRedis()).isTrue();
        assertThat(AssetKind.OBJECT_STORE.isObjectStore()).isTrue();
        assertThatThrownBy(AssetKind.REDIS::dialectKind)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(AssetKind.OBJECT_STORE::dialect)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void defaultPortsForNonSql() {
        assertThat(AssetKind.REDIS.defaultPort()).isEqualTo(6379);
        // 对象存储是 endpoint 语义，给 HTTPS 默认占位 443。
        assertThat(AssetKind.OBJECT_STORE.defaultPort()).isEqualTo(443);
    }

    @Test
    void ofResolvesByIdOrEnumNameBackwardsCompatible() {
        // 新 id（小写）
        assertThat(AssetKind.of("mysql")).isEqualTo(AssetKind.MYSQL);
        assertThat(AssetKind.of("object_store")).isEqualTo(AssetKind.OBJECT_STORE);
        assertThat(AssetKind.of("redis")).isEqualTo(AssetKind.REDIS);
        // 旧档案的枚举名（大写）也必须认 —— 旧 connections.json 写的是 "MYSQL"
        assertThat(AssetKind.of("MYSQL")).isEqualTo(AssetKind.MYSQL);
        assertThat(AssetKind.of("PostgreSQL")).isEqualTo(AssetKind.POSTGRESQL);
        assertThat(AssetKind.of("ORACLE")).isEqualTo(AssetKind.ORACLE);
    }

    @Test
    void fromDialectKindMapsSqlMembers() {
        assertThat(AssetKind.from(DialectKind.MYSQL)).isEqualTo(AssetKind.MYSQL);
        assertThat(AssetKind.from(DialectKind.SQLITE)).isEqualTo(AssetKind.SQLITE);
    }

    @Test
    void jsonRoundTripsWithStableLowercaseIdAndParsesLegacyName() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // 序列化产出小写 id（稳定、可读）。
        assertThat(mapper.writeValueAsString(AssetKind.MYSQL)).isEqualTo("\"mysql\"");
        assertThat(mapper.writeValueAsString(AssetKind.OBJECT_STORE)).isEqualTo("\"object_store\"");

        // 新 id 与新档案兼容。
        assertThat(mapper.readValue("\"mysql\"", AssetKind.class)).isEqualTo(AssetKind.MYSQL);
        // 旧档案的枚举名也能解析（不要求用户迁移 connections.json）。
        assertThat(mapper.readValue("\"MYSQL\"", AssetKind.class)).isEqualTo(AssetKind.MYSQL);
        assertThat(mapper.readValue("\"REDIS\"", AssetKind.class)).isEqualTo(AssetKind.REDIS);
    }

    @Test
    void unknownKindRejected() {
        assertThatThrownBy(() -> AssetKind.of("cassandra"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
