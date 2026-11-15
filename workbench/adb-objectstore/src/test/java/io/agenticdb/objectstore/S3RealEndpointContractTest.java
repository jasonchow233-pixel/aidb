package io.agenticdb.objectstore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ R2：S3 兼容端点的<b>真端点契约</b>（LocalStack 4.9.2 容器上的真 S3 协议服务）。
 *
 * <p>此前四家 SDK 适配器只有「编译通过 + {@link InMemoryObjectStoreSession} 上的行为契约」，
 * 连得上 / 列举 / 上传 / 下载 / 预签名是否真生效完全没验。这里让
 * {@link ObjectStoreSessions#connect(Map, String)} —— <b>产品同一条工厂路径</b> ——
 * 对着一个真 HTTPS S3 服务端跑一遍：SigV4 签名、SDK 序列化、ListObjectsV2 分页、
 * presigned URL 都是真实协议行为，不是桩。
 *
 * <p>⚠️ 诚实边界：这是 <b>S3 兼容协议</b>的真服务端，只覆盖
 * {@link S3ObjectStoreSession}（AWS S3 / MinIO / LocalStack 一支）。
 * 阿里云 OSS 与七牛 Kodo 已另有真账号只读契约（{@code OssRealEndpointContractTest} /
 * {@code QiniuRealEndpointContractTest}），四家里<b>仅腾讯云 COS 仍无真端点证据</b>；
 * 那两家走各自原生 SDK，<b>本类不宣称验过它们</b>。没有 Docker 时整类跳过（与
 * {@code PostgresIntrospectionTest} 同口径），有 Docker 就必须实跑。
 *
 * <p>⚠️ 契约与产品共用同一条路：种子数据（建桶 / 批量灌对象）用原生 SDK 直接写服务端，
 * 所有被测操作一律经 {@link #session()} 拿到的产品会话执行，契约里不内联任何产品兜底。
 */
@EnabledIf("io.agenticdb.objectstore.S3RealEndpointContractTest#dockerAvailable")
class S3RealEndpointContractTest {

    /** 4.9.2 在 4566 上同时提供 HTTP 与 HTTPS，且 TLS 证书由公开 CA 签发、
     *  SAN 含 {@code localhost.localstack.cloud} —— 测试 JVM 的默认信任库就能验过。 */
    private static final String IMAGE = "localstack/localstack:4.9.2";
    /**
     * 走 <b>HTTPS</b> 而不是容器的 HTTP 端口：AWS SDK 2.31 在明文 HTTP 上会给
     * PutObject 加 {@code Expect: 100-continue} + 流式 SigV4，LocalStack 的
     * Twisted 网关把 {@code 100 Continue} 和 {@code 200 OK} 一起回，SDK 的
     * Apache 客户端消化不了，每个 PUT 都会卡到 30s 读超时再重试（实测服务端每次
     * 都记 200）。真实 S3 / MinIO 生产端点也都是 HTTPS，所以用受信任的 TLS 端点
     * 跑契约，既贴近产品实际，也让 SDK 保持<b>默认</b>行为、不靠任何全局开关取巧。
     * 该明文 HTTP 边界另记在 STATUS「能力缺口」。
     */
    private static final String ENDPOINT_HOST = "localhost.localstack.cloud";
    private static final String ACCESS_KEY = "adb-contract-ak";
    private static final String SECRET_KEY = "adb-contract-sk-0123456789";
    private static final String BUCKET = "adb-contract";
    private static final String SECOND_BUCKET = "adb-contract-second";

    private static GenericContainer<?> localstack;
    private static String endpoint;
    private static Map<String, String> options;
    private static String storedCredentials;

    /** 没 Docker 的机器上跳过整类，不让构建挂掉（ad-hoc 判断，不引其它模块的 test-jar）。 */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @BeforeAll
    static void start() {
        localstack = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(4566)
                .withEnv("SERVICES", "s3")
                .withEnv("AWS_DEFAULT_REGION", "us-east-1")
                // 只等日志里那句 "Ready." 还不够，S3 插件可能仍在初始化；
                // 直接盯 health JSON 里 s3=available 才放行。
                .waitingFor(Wait.forHttp("/_localstack/health")
                        .forPort(4566)
                        .forResponsePredicate(body -> body.contains("\"s3\": \"available\""))
                        .withStartupTimeout(Duration.ofMinutes(4)));
        localstack.start();
        endpoint = "https://" + ENDPOINT_HOST + ":" + localstack.getMappedPort(4566);
        options = Map.of(
                ObjectStoreCredentials.OPT_PROVIDER, "s3",
                ObjectStoreCredentials.OPT_ENDPOINT, endpoint,
                ObjectStoreCredentials.OPT_REGION, "us-east-1",
                ObjectStoreCredentials.OPT_BUCKET, BUCKET,
                ObjectStoreCredentials.OPT_PATH_STYLE, "true");
        // 凭据串与产品落盘格式一致：b64(ak).b64(sk).b64(token)。
        storedCredentials = ObjectStoreCredentials.encode(ACCESS_KEY, SECRET_KEY, "");
        seed();
    }

    @AfterAll
    static void stop() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    /** 测试侧直接对服务端灌种子数据（等价于 RedisScanTest 里用 Lettuce 写库）。 */
    private static void seed() {
        try (S3Client raw = rawClient()) {
            raw.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            raw.createBucket(CreateBucketRequest.builder().bucket(SECOND_BUCKET).build());
        }
    }

    private static S3Client rawClient() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true).build())
                .build();
    }

    /** 所有被测操作都从产品的会话工厂进，不绕路直接构造 S3ObjectStoreSession。 */
    private static ObjectStoreSession session() {
        return ObjectStoreSessions.connect(options, storedCredentials);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void factoryConnectListsBucketsFromTheRealServer() {
        try (ObjectStoreSession s = session()) {
            assertThat(s.provider()).isEqualTo(io.agenticdb.asset.ObjectStoreProvider.S3);
            assertThat(s.capabilities().supportsListBuckets()).isTrue();
            assertThat(s.listBuckets()).extracting(Bucket::name)
                    .contains(BUCKET, SECOND_BUCKET);
        }
    }

    @Test
    void factoryTestSucceedsAgainstRealEndpointAndReportsBadEndpoint() {
        assertThat(ObjectStoreSessions.test(options, storedCredentials)).isNull();

        Map<String, String> bad = new HashMap<>(options);
        bad.put(ObjectStoreCredentials.OPT_ENDPOINT, "http://127.0.0.1:1");
        String error = ObjectStoreSessions.test(bad, storedCredentials);
        assertThat(error).isNotBlank();
    }

    @Test
    void incompleteCredentialsNeverSilentlyConnect() {
        assertThatThrownBy(() -> ObjectStoreSessions.connect(options, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void putThenGetAndMetadataRoundTripThroughRealS3() {
        byte[] data = bytes("hello real endpoint");
        try (ObjectStoreSession s = session()) {
            ObjectMeta written = s.putObject(BUCKET, "roundtrip/hello.txt", data, "text/plain");
            assertThat(written.key()).isEqualTo("roundtrip/hello.txt");
            assertThat(written.size()).isEqualTo(data.length);
            assertThat(written.etag()).isNotBlank();
            assertThat(written.lastModified()).isNotNull();

            assertThat(s.getObject(BUCKET, "roundtrip/hello.txt")).isEqualTo(data);
            ObjectMeta readBack = s.metadata(BUCKET, "roundtrip/hello.txt");
            assertThat(readBack.size()).isEqualTo(data.length);
            assertThat(readBack.etag()).isNotBlank();
        }
    }

    @Test
    void delimiterCollapsesFoldersOnTheRealServer() {
        try (ObjectStoreSession s = session()) {
            s.putObject(BUCKET, "folders/2026/a.txt", bytes("a"), "text/plain");
            s.putObject(BUCKET, "folders/2026/sub/b.txt", bytes("b"), "text/plain");
            s.putObject(BUCKET, "folders/root.txt", bytes("r"), "text/plain");

            List<ObjectEntry> root = s.listObjects(BUCKET, "folders/", "/");
            assertThat(root).extracting(ObjectEntry::key)
                    .containsExactlyInAnyOrder("folders/2026/", "folders/root.txt");
            assertThat(root.stream().filter(ObjectEntry::folder).findFirst().orElseThrow().size())
                    .isZero();

            List<ObjectEntry> nested = s.listObjects(BUCKET, "folders/2026/", "/");
            assertThat(nested).extracting(ObjectEntry::key)
                    .containsExactlyInAnyOrder("folders/2026/a.txt", "folders/2026/sub/");
        }
    }

    /**
     * 服务端翻页：S3 的 ListObjectsV2 默认单页 1000 条，这里灌 1001 个对象，
     * 逼 {@code flatList} 真的走一次 continuationToken 才能列全。
     */
    @Test
    void flatListCrossesTheThousandObjectPageBoundary() throws Exception {
        final int count = 1001;
        try (S3Client raw = rawClient(); ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> jobs = java.util.stream.IntStream.range(0, count)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        raw.putObject(PutObjectRequest.builder().bucket(BUCKET)
                                        .key("page/" + String.format("%04d", i) + ".bin").build(),
                                RequestBody.fromBytes(new byte[]{1}));
                        return null;
                    }).toList();
            for (Future<Void> f : pool.invokeAll(jobs)) {
                f.get();
            }
        }
        try (ObjectStoreSession s = session()) {
            assertThat(s.listObjects(BUCKET, "page/", null)).hasSize(count);
        }
    }

    /**
     * ⭐ 渐进分页在真 S3 服务端上验一次：一次只问一页、ListObjectsV2 的
     * continuationToken 真跨页、delimiter 折叠由 commonPrefixes 给，
     * 游标还必须绑住 (bucket, prefix, delimiter)，换层不许串数据。
     * 全量 {@code listObjects} 的千条跨页由上面那条负责，这里验的是<b>暴露给 UI 的那一页</b>。
     */
    @Test
    void pagedListingWalksRealServerTokensWithoutLosingRows() {
        try (ObjectStoreSession s = session()) {
            for (int i = 0; i < 12; i++) {
                s.putObject(BUCKET, "paged/" + String.format("%02d", i) + ".bin",
                        bytes("p"), "application/octet-stream");
            }
            s.putObject(BUCKET, "paged/dir-a/a.txt", bytes("a"), "text/plain");
            s.putObject(BUCKET, "paged/dir-b/b.txt", bytes("b"), "text/plain");

            List<String> paged = new ArrayList<>();
            String cursor = null;
            String firstToken = null;
            int pages = 0;
            while (true) {
                ObjectPage page = s.listObjectsPage(BUCKET, "paged/", "/", cursor, 5);
                pages++;
                if (firstToken == null) {
                    firstToken = page.nextToken();
                }
                assertThat(page.entries()).hasSizeLessThanOrEqualTo(5);
                for (ObjectEntry entry : page.entries()) {
                    paged.add(entry.key());
                }
                if (!page.hasMore()) {
                    break;
                }
                assertThat(page.nextToken()).isNotEqualTo(cursor).as("服务端游标必须前进");
                cursor = page.nextToken();
                assertThat(pages).isLessThan(20).as("分页没有收敛");
            }
            assertThat(pages).isGreaterThan(2);
            assertThat(firstToken).isNotBlank();
            assertThat(paged).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(
                            s.listObjects(BUCKET, "paged/", "/").stream()
                                    .map(ObjectEntry::key).toList());
            assertThat(paged).contains("paged/dir-a/", "paged/dir-b/");

            String token = firstToken;
            assertThatThrownBy(() -> s.listObjectsPage(BUCKET, "other/", "/", token, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }
    }

    @Test
    void deleteObjectRemovesExactlyOneKey() {
        try (ObjectStoreSession s = session()) {
            s.putObject(BUCKET, "del/a.txt", bytes("a"), "text/plain");
            s.putObject(BUCKET, "del/b.txt", bytes("b"), "text/plain");
            s.deleteObject(BUCKET, "del/a.txt");
            assertThat(s.listObjects(BUCKET, "del/", null))
                    .extracting(ObjectEntry::key).containsExactly("del/b.txt");
        }
    }

    @Test
    void deletePrefixRemovesEveryObjectBeneathItAndOnlyThose() {
        try (ObjectStoreSession s = session()) {
            s.putObject(BUCKET, "purge/a.txt", bytes("a"), "text/plain");
            s.putObject(BUCKET, "purge/sub/b.txt", bytes("b"), "text/plain");
            s.putObject(BUCKET, "purge-keep.txt", bytes("k"), "text/plain");

            assertThat(s.deletePrefix(BUCKET, "purge/")).isEqualTo(2);
            assertThat(s.listObjects(BUCKET, "purge/", null)).isEmpty();
            assertThat(s.getObject(BUCKET, "purge-keep.txt")).isEqualTo(bytes("k"));
        }
    }

    /** 移动必须保留对象的 contentType —— 拿 storageClass 当 MIME 类型发是错的。 */
    @Test
    void movePreservesContentTypeAndRemovesSource() {
        byte[] data = bytes("move me");
        try (ObjectStoreSession s = session(); S3Client raw = rawClient()) {
            s.putObject(BUCKET, "move/old.txt", data, "text/plain; charset=utf-8");
            s.moveObject(BUCKET, "move/old.txt", "move/new.txt");

            assertThat(s.getObject(BUCKET, "move/new.txt")).isEqualTo(data);
            assertThatThrownBy(() -> s.getObject(BUCKET, "move/old.txt"))
                    .isInstanceOf(Exception.class);
            String contentType = raw.headObject(HeadObjectRequest.builder()
                            .bucket(BUCKET).key("move/new.txt").build())
                    .contentType();
            assertThat(contentType).startsWith("text/plain");
        }
    }

    @Test
    void presignedUrlCanActuallyBeFetchedOverHttp() throws Exception {
        byte[] data = bytes("presigned payload");
        try (ObjectStoreSession s = session()) {
            s.putObject(BUCKET, "presign/a.txt", data, "text/plain");
            String url = s.presignUrl(BUCKET, "presign/a.txt", 120);
            assertThat(url).startsWith("http").contains("X-Amz-Signature");

            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET()
                            .timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(data);
        }
    }
}
