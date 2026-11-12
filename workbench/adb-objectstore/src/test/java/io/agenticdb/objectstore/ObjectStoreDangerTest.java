package io.agenticdb.objectstore;

import io.agenticdb.model.Danger;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-10 门禁：Danger 分级、能力矩阵、凭据编码。
 *
 * <p>与 {@code RedisMutateTest} / {@code DangerClassifierTest} 同一口径：
 * 未知操作必须 fail-closed 到 ADMIN，绝不能悄悄当只读放过。
 */
class ObjectStoreDangerTest {

    @Test
    void readOperationsAreReadOnly() {
        for (String op : new String[]{"LIST_BUCKETS", "LIST_OBJECTS", "GET_OBJECT",
                "HEAD_OBJECT", "METADATA", "PRESIGN_URL", "STAT", "list_objects"}) {
            assertThat(ObjectStoreDanger.level(op)).as(op).isEqualTo(Danger.READ_ONLY);
        }
    }

    @Test
    void writeOperationsAreMutating() {
        for (String op : new String[]{"PUT_OBJECT", "UPLOAD", "CREATE_BUCKET",
                "COPY_OBJECT", "MOVE_OBJECT", "SET_ACL", "SET_METADATA"}) {
            assertThat(ObjectStoreDanger.level(op)).as(op).isEqualTo(Danger.MUTATING);
        }
    }

    @Test
    void deleteOperationsAreDestructive() {
        for (String op : new String[]{"DELETE_OBJECT", "DELETE_PREFIX",
                "DELETE_BUCKET", "DELETE_OBJECTS"}) {
            assertThat(ObjectStoreDanger.level(op)).as(op).isEqualTo(Danger.DESTRUCTIVE);
            assertThat(Danger.DESTRUCTIVE.requiresApproval()).isTrue();
        }
    }

    @Test
    void unknownAndBlankCommandsFailClosedToAdmin() {
        assertThat(ObjectStoreDanger.level("EXEC_ARBITRARY")).isEqualTo(Danger.ADMIN);
        assertThat(ObjectStoreDanger.level(" ")).isEqualTo(Danger.ADMIN);
        assertThat(ObjectStoreDanger.level("")).isEqualTo(Danger.ADMIN);
        assertThat(ObjectStoreDanger.level(null)).isEqualTo(Danger.ADMIN);
    }

    @Test
    void qiniuHonestlyDeclaresItsMissingCapabilities() {
        var qiniu = ObjectStoreCapabilities.forProvider(io.agenticdb.asset.ObjectStoreProvider.QINIU);
        assertThat(qiniu.supportsMove()).isFalse();
        assertThat(qiniu.supportsListBuckets()).isFalse();
        assertThat(qiniu.supportsCopy()).isTrue();
        assertThat(qiniu.moveNote()).isNotBlank();
    }

    @Test
    void otherProvidersDeclareFullCapabilities() {
        for (var provider : new io.agenticdb.asset.ObjectStoreProvider[]{
                io.agenticdb.asset.ObjectStoreProvider.S3,
                io.agenticdb.asset.ObjectStoreProvider.OSS,
                io.agenticdb.asset.ObjectStoreProvider.COS}) {
            var caps = ObjectStoreCapabilities.forProvider(provider);
            assertThat(caps.supportsMove()).as(provider.id()).isTrue();
            assertThat(caps.supportsListBuckets()).as(provider.id()).isTrue();
            assertThat(caps.supportsPresign()).as(provider.id()).isTrue();
            assertThat(caps.moveNote()).as(provider.id()).isEmpty();
        }
    }

    @Test
    void credentialsRoundTripWithoutLeakingSecretsIntoOptions() {
        String stored = ObjectStoreCredentials.encode("AK with +/=", "SK:secret", "token-1");
        String[] parts = ObjectStoreCredentials.decode(stored);
        assertThat(parts).containsExactly("AK with +/=", "SK:secret", "token-1");

        Map<String, String> options = Map.of(
                ObjectStoreCredentials.OPT_PROVIDER, "cos",
                ObjectStoreCredentials.OPT_REGION, "ap-guangzhou",
                ObjectStoreCredentials.OPT_BUCKET, "my-bucket");
        ObjectStoreCredentials c = ObjectStoreCredentials.of(() -> options, stored);
        assertThat(c.provider()).isEqualTo(io.agenticdb.asset.ObjectStoreProvider.COS);
        assertThat(c.region()).isEqualTo("ap-guangzhou");
        assertThat(c.bucket()).isEqualTo("my-bucket");
        assertThat(c.complete()).isTrue();
        // options 里只有非敏感项，密钥只存在于 CredentialStore 那条串里。
        assertThat(options).doesNotContainKeys("accessKey", "secretKey", "password");
    }

    @Test
    void malformedStoredCredentialsDegradeToEmptyRatherThanThrowing() {
        assertThat(ObjectStoreCredentials.decode(null)).containsExactly("", "", "");
        assertThat(ObjectStoreCredentials.decode("not-base64!!!")).hasSize(3);
        assertThat(ObjectStoreCredentials.of(() -> Map.of(), null).complete()).isFalse();
    }
}
