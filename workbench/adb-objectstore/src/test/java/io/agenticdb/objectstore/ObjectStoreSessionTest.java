package io.agenticdb.objectstore;

import io.agenticdb.asset.ObjectStoreProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-10 行为门禁：在真实（内存）会话上验证列举 / 上传 / 下载 / 元数据 /
 * 递归删前缀 / folder 折叠 / 能力拒绝，而不是只测「方法被调用了」。
 *
 * <p>与 {@code RedisMutateTest} 的定位一致：写操作必须真的改状态，
 * 破坏性操作的分级由 {@code ObjectStoreDangerTest} 守，这里验证语义正确。
 * S3 兼容分支另有 {@link S3RealEndpointContractTest} 跑真 LocalStack 端点，
 * OSS / Kodo 另有真账号只读契约；四家里仅腾讯云 COS 仍只有编译期类型与
 * 内存后端契约（R2 剩余阻塞项）。本类不冒充云端连通性证据。
 * 分页语义另由 {@link ObjectStorePagingTest} 在无 Docker 环境下钉住。
 */
class ObjectStoreSessionTest {

    private ObjectStoreSession session;

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private InMemoryObjectStoreSession open(ObjectStoreProvider provider, String bucket) {
        ObjectStoreCredentials c = new ObjectStoreCredentials(
                provider, "", "", bucket, "ak", "sk", "", true);
        InMemoryObjectStoreSession s = new InMemoryObjectStoreSession(c);
        this.session = s;
        return s;
    }

    @Test
    void putThenGetAndMetadataRoundTrip() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "photos");
        s.putObject("photos", "2026/a.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain");

        assertThat(s.getObject("photos", "2026/a.txt"))
                .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        ObjectMeta meta = s.metadata("photos", "2026/a.txt");
        assertThat(meta.key()).isEqualTo("2026/a.txt");
        assertThat(meta.size()).isEqualTo(5);
        assertThat(meta.etag()).isNotBlank();
        assertThat(meta.lastModified()).isNotNull();
    }

    /**
     * ⭐ R2 真端点契约抓出的缺陷：早期把 contentType 塞进 storageClass，
     * moveObject 又把 storageClass 当 contentType 传，结果移动一次 MIME 就丢。
     * 这条不依赖 Docker，保证无容器环境也守得住字段分离。
     */
    @Test
    void contentTypeIsSeparateFromStorageClassAndSurvivesMove() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "photos");
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        ObjectMeta written = s.putObject("photos", "typed.txt", data, "text/plain");
        assertThat(written.contentType()).isEqualTo("text/plain");
        assertThat(written.storageClass()).isEmpty();
        assertThat(s.metadata("photos", "typed.txt").contentType()).isEqualTo("text/plain");
        assertThat(s.listObjects("photos", "", null).get(0).storageClass()).isEmpty();

        s.moveObject("photos", "typed.txt", "moved.txt");
        ObjectMeta moved = s.metadata("photos", "moved.txt");
        assertThat(moved.contentType()).isEqualTo("text/plain");
        assertThat(moved.storageClass()).isEmpty();
        assertThat(s.getObject("photos", "moved.txt")).isEqualTo(data);
    }

    @Test
    void listWithoutDelimiterFlattensEverything() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "photos");
        s.putObject("photos", "2026/a.txt", new byte[]{1}, "text/plain");
        s.putObject("photos", "2026/sub/b.txt", new byte[]{2}, "text/plain");
        s.putObject("photos", "root.txt", new byte[]{3}, "text/plain");

        assertThat(s.listObjects("photos", "", null))
                .extracting(ObjectEntry::key)
                .containsExactly("2026/a.txt", "2026/sub/b.txt", "root.txt");
        assertThat(s.listObjects("photos", "2026/", null))
                .extracting(ObjectEntry::key)
                .containsExactly("2026/a.txt", "2026/sub/b.txt");
    }

    @Test
    void delimiterCollapsesCommonPrefixesIntoFolders() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "photos");
        s.putObject("photos", "2026/a.txt", new byte[]{1}, "text/plain");
        s.putObject("photos", "2026/sub/b.txt", new byte[]{2}, "text/plain");
        s.putObject("photos", "2026/sub/deep/c.txt", new byte[]{3}, "text/plain");
        s.putObject("photos", "root.txt", new byte[]{4}, "text/plain");

        var rows = s.listObjects("photos", "", "/");
        // 同前缀只折一个文件夹行，不重复；文件夹行没有 size/etag。
        assertThat(rows).extracting(ObjectEntry::key)
                .containsExactly("2026/", "root.txt");
        assertThat(rows.get(0).folder()).isTrue();
        assertThat(rows.get(0).size()).isZero();
        assertThat(rows.get(1).folder()).isFalse();

        var nested = s.listObjects("photos", "2026/", "/");
        assertThat(nested).extracting(ObjectEntry::key)
                .containsExactly("2026/a.txt", "2026/sub/");
        assertThat(nested.get(1).folder()).isTrue();
    }

    @Test
    void deletePrefixRemovesEveryObjectBeneathItAndOnlyThose() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "photos");
        s.putObject("photos", "2026/a.txt", new byte[]{1}, "text/plain");
        s.putObject("photos", "2026/sub/b.txt", new byte[]{2}, "text/plain");
        s.putObject("photos", "keep.txt", new byte[]{3}, "text/plain");

        int removed = s.deletePrefix("photos", "2026/");
        assertThat(removed).isEqualTo(2);
        assertThat(s.listObjects("photos", "", null))
                .extracting(ObjectEntry::key).containsExactly("keep.txt");
        assertThatThrownBy(() -> s.getObject("photos", "2026/a.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteObjectRemovesExactlyOneKey() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "photos");
        s.putObject("photos", "a", new byte[]{1}, "");
        s.putObject("photos", "b", new byte[]{2}, "");
        s.deleteObject("photos", "a");
        assertThat(s.listObjects("photos", "", null))
                .extracting(ObjectEntry::key).containsExactly("b");
    }

    @Test
    void moveIsSupportedForS3AndHonestlyRejectedForQiniu() {
        InMemoryObjectStoreSession s3 = open(ObjectStoreProvider.S3, "photos");
        s3.putObject("photos", "old.txt", "x".getBytes(StandardCharsets.UTF_8), "text/plain");
        s3.moveObject("photos", "old.txt", "new.txt");
        assertThat(s3.listObjects("photos", "", null))
                .extracting(ObjectEntry::key).containsExactly("new.txt");

        session.close();
        InMemoryObjectStoreSession qiniu = open(ObjectStoreProvider.QINIU, "qbucket");
        assertThat(qiniu.capabilities().supportsMove()).isFalse();
        assertThatThrownBy(() -> qiniu.moveObject("qbucket", "a", "b"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("no server-side move");
    }

    @Test
    void qiniuListsOnlyTheConfiguredBucketInsteadOfPretending() {
        InMemoryObjectStoreSession qiniu = open(ObjectStoreProvider.QINIU, "qbucket");
        assertThat(qiniu.capabilities().supportsListBuckets()).isFalse();
        assertThat(qiniu.listBuckets()).extracting(Bucket::name).containsExactly("qbucket");
    }

    @Test
    void bucketsAreListedForProvidersThatSupportIt() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "alpha");
        s.createBucket("beta");
        assertThat(s.listBuckets()).extracting(Bucket::name)
                .containsExactly("alpha", "beta");
    }

    @Test
    void presignUrlIsExplicitlyLocalForTheInMemoryBackend() {
        InMemoryObjectStoreSession s = open(ObjectStoreProvider.S3, "photos");
        s.putObject("photos", "a.txt", new byte[]{1}, "text/plain");
        assertThat(s.presignUrl("photos", "a.txt", 60)).startsWith("memory://");
        assertThat(s.presignUrl("photos", "a.txt", 60)).contains("expiresIn=60");
    }

    @Test
    void factoryCreatesSessionsForAllFourProviders() {
        for (ObjectStoreProvider provider : ObjectStoreProvider.values()) {
            session = ObjectStoreSessions.inMemory(provider);
            assertThat(session.provider()).isEqualTo(provider);
            assertThat(session.capabilities().provider()).isEqualTo(provider);
            assertThat(session.listBuckets()).isNotNull();
            session.close();
            session = null;
        }
    }

    @Test
    void incompleteCredentialsAreRejectedWithAClearMessage() {
        assertThatThrownBy(() -> ObjectStoreSessions.connect(
                java.util.Map.of(ObjectStoreCredentials.OPT_PROVIDER, "s3"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }
}
