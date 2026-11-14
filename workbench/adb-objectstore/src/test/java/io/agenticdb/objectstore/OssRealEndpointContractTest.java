package io.agenticdb.objectstore;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import io.agenticdb.asset.ObjectStoreProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ⭐ 阿里云 OSS <b>真账号 / 真端点</b>只读契约。
 *
 * <p>补齐 R2 的诚实边界：{@link S3RealEndpointContractTest} 只覆盖 S3 兼容协议，
 * 四家原生 SDK 里 OSS 一支此前「只有编译期 SDK 类型 + 内存后端行为契约」。
 * 本类走产品同一条工厂 {@link ObjectStoreSessions#connect(Map, String)}，
 * 对运营方提供的真实 OSS 账号验证：SigV4(V1) 签名、ListObjects marker 翻页、
 * delimiter 折叠、metadata / contentType、presigned URL 真 HTTPS GET。
 *
 * <p><b>凭据纪律</b>：endpoint / bucket / AK / SK / urlPrefix 一律从环境变量读，
 * 类里没有任何凭据字面量，也不写任何文件。默认是 <b>opt-in</b> —— 没给环境变量
 * 整类跳过，CI 不会误连生产账号。
 *
 * <p><b>写纪律</b>：默认全部只读，不写不删生产桶。唯一的写契约
 * {@link #optInWriteRoundTripUnderADedicatedPrefix()} 额外要求
 * {@code ADB_TEST_OSS_WRITE=true}，且只在独立前缀下写自己创建的对象并在 finally 清理。
 *
 * <p><b>规模纪律</b>：真实图片桶可能有几十万对象，绝不做「列全桶」这种会拖垮
 * 生产账号的调用。样本用原生 SDK 只取一页，分页契约用受限前缀 + 页数上限探测；
 * 不适合的前缀用 {@code assumeTrue} 跳过并写清原因，不造假数据。
 */
@EnabledIf("io.agenticdb.objectstore.OssRealEndpointContractTest#ossConfigured")
class OssRealEndpointContractTest {

    private static final String ENV_ENDPOINT = "ADB_TEST_OSS_ENDPOINT";
    private static final String ENV_BUCKET = "ADB_TEST_OSS_BUCKET";
    private static final String ENV_AK = "ADB_TEST_OSS_AK";
    private static final String ENV_SK = "ADB_TEST_OSS_SK";
    private static final String ENV_URL_PREFIX = "ADB_TEST_OSS_URL_PREFIX";

    /** 分页探测最多翻几页；超了就跳过，不让契约变成全桶扫描。 */
    private static final int MAX_PROBE_PAGES = 8;

    private static String endpoint;
    private static String bucket;
    private static String urlPrefix;
    private static Map<String, String> options;
    private static String storedCredentials;

    /** 原生 SDK 探到的一页样本（只读）：最小对象、层级前缀、可跨页的前缀。 */
    private static String sampleKey;
    private static long sampleSize;
    private static String hierarchyPrefix;
    private static String pagingPrefix;
    private static int pagingExpected;

    static boolean ossConfigured() {
        return present(ENV_ENDPOINT) && present(ENV_BUCKET)
                && present(ENV_AK) && present(ENV_SK);
    }

    private static boolean present(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    @BeforeAll
    static void setUp() {
        endpoint = System.getenv(ENV_ENDPOINT);
        bucket = System.getenv(ENV_BUCKET);
        urlPrefix = System.getenv(ENV_URL_PREFIX);
        options = Map.of(
                ObjectStoreCredentials.OPT_PROVIDER, ObjectStoreProvider.OSS.id(),
                ObjectStoreCredentials.OPT_ENDPOINT, endpoint,
                ObjectStoreCredentials.OPT_BUCKET, bucket);
        // 与产品落盘格式一致：b64(ak).b64(sk).b64(token)。
        storedCredentials = ObjectStoreCredentials.encode(
                System.getenv(ENV_AK), System.getenv(ENV_SK), "");

        // ⚠️ OSS 接口不 extends AutoCloseable，只能显式 shutdown。
        OSS raw = rawClient();
        try {
            ListObjectsRequest req = new ListObjectsRequest(bucket);
            req.setMaxKeys(20);
            ObjectListing page = raw.listObjects(req);
            List<OSSObjectSummary> summaries = page.getObjectSummaries();
            assumeTrue(!summaries.isEmpty(), "配置的 bucket 里没有对象，无法验证列举/下载契约");

            OSSObjectSummary smallest = summaries.get(0);
            for (OSSObjectSummary summary : summaries) {
                if (summary.getSize() < smallest.getSize()) {
                    smallest = summary;
                }
                String key = summary.getKey();
                if (hierarchyPrefix == null && key.contains("/")) {
                    hierarchyPrefix = key.substring(0, key.indexOf('/') + 1);
                }
            }
            sampleKey = smallest.getKey();
            sampleSize = smallest.getSize();

            // 找一个「超过一页但不超过 MAX_PROBE_PAGES」的前缀，用来验产品翻页。
            // 样本页的 key 多为时间戳/长数字开头，候选不能只盯样本首字符。
            for (String candidate : prefixCandidates(summaries)) {
                int count = probeFlatCount(raw, candidate, MAX_PROBE_PAGES);
                if (count > 1000 && count <= MAX_PROBE_PAGES * 1000) {
                    pagingPrefix = candidate;
                    pagingExpected = count;
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("OSS 真端点探测失败: " + e.getMessage(), e);
        } finally {
            raw.shutdown();
        }
    }

    /**
     * 前缀候选：真实图片桶的 key 大多是时间戳 / 长数字（2025…、1746…），
     * 先试常见的数字前缀，再补样本页里出现过的首字符与首两位。
     */
    private static List<String> prefixCandidates(List<OSSObjectSummary> summaries) {
        var out = new java.util.LinkedHashSet<String>();
        out.addAll(List.of("17", "10", "20", "2025", "2026", "1", "2"));
        for (OSSObjectSummary summary : summaries) {
            String key = summary.getKey();
            if (key.length() >= 1) {
                out.add(key.substring(0, 1));
            }
            if (key.length() >= 2) {
                out.add(key.substring(0, 2));
            }
        }
        return new ArrayList<>(out);
    }

    /** 原生 SDK 侧数一遍某前缀下的对象数；超过页数上限返回 -1。 */
    private static int probeFlatCount(OSS raw, String prefix, int maxPages) {
        ListObjectsRequest req = new ListObjectsRequest(bucket);
        req.setPrefix(prefix);
        req.setMaxKeys(1000);
        int total = 0;
        for (int pageNo = 0; pageNo < maxPages; pageNo++) {
            ObjectListing page = raw.listObjects(req);
            total += page.getObjectSummaries().size();
            if (!page.isTruncated()) {
                return total;
            }
            String next = page.getNextMarker();
            if (next == null || next.isBlank()) {
                return -1;
            }
            req.setMarker(next);
        }
        return -1;
    }

    private static OSS rawClient() {
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setConnectionTimeout(10_000);
        config.setSocketTimeout(15_000);
        config.setMaxErrorRetry(1);
        return new OSSClientBuilder().build(endpoint, System.getenv(ENV_AK),
                System.getenv(ENV_SK), config);
    }

    /** 所有被测操作都从产品工厂进，不绕路 new OssObjectStoreSession。 */
    private static ObjectStoreSession session() {
        return ObjectStoreSessions.connect(options, storedCredentials);
    }

    @Test
    void factoryConnectAndConnectionTestReachTheRealAccount() {
        try (ObjectStoreSession s = session()) {
            assertThat(s.provider()).isEqualTo(ObjectStoreProvider.OSS);
            assertThat(s.capabilities().supportsMove()).isTrue();
            assertThat(s.listBuckets()).extracting(Bucket::name).contains(bucket);
        }
        assertThat(ObjectStoreSessions.test(options, storedCredentials))
                .as("产品连接测试必须对真实 OSS 账号成功").isNull();
    }

    @Test
    void anExactKeyPrefixListsOnlyThatRealObject() {
        try (ObjectStoreSession s = session()) {
            List<ObjectEntry> rows = s.listObjects(bucket, sampleKey, null);
            assertThat(rows).as("prefix=完整 key 时应只命中该对象").isNotEmpty();
            assertThat(rows).extracting(ObjectEntry::key).contains(sampleKey);
            assertThat(rows).allSatisfy(row ->
                    assertThat(row.key()).startsWith(sampleKey));
        }
    }

    @Test
    void delimiterListingHonorsTheRealKeySpace() {
        try (ObjectStoreSession s = session()) {
            // 无层级键的桶：传 "/" 也不能凭空造出文件夹行，全部应当落为对象行。
            // prefix 取完整 key，列举范围 = 1 条，绝不对生产图片桶做全量扫描。
            List<ObjectEntry> flat = s.listObjects(bucket, sampleKey, "/");
            assertThat(flat).isNotEmpty();
            assertThat(flat).allSatisfy(row ->
                    assertThat(row.key()).startsWith(sampleKey));
            if (hierarchyPrefix == null) {
                assertThat(flat.stream().noneMatch(ObjectEntry::folder))
                        .as("样本页里没有含 / 的 key，不该凭空造出文件夹行").isTrue();
            } else {
                List<ObjectEntry> nested = s.listObjects(bucket, hierarchyPrefix, "/");
                assertThat(nested).isNotEmpty();
            }
        }
    }

    @Test
    void metadataAndPresignedUrlWorkForARealObject() throws Exception {
        // 只读下载限制在 1 MiB 以内，别为了一条契约把大图反复拉下来。
        assumeTrue(sampleSize <= 1024 * 1024,
                "样本对象大于 1 MiB，跳过下载以避免对生产账号造成流量压力");
        try (ObjectStoreSession s = session()) {
            ObjectMeta meta = s.metadata(bucket, sampleKey);
            assertThat(meta.key()).isEqualTo(sampleKey);
            assertThat(meta.size()).isEqualTo(sampleSize);
            assertThat(meta.etag()).isNotBlank();
            assertThat(meta.contentType())
                    .as("OSS 的 Content-Type 必须独立返回，不能拿 storageClass 顶替")
                    .isNotBlank();
            assertThat(meta.contentType()).isNotEqualTo(meta.storageClass());

            String url = s.presignUrl(bucket, sampleKey, 120);
            assertThat(url.startsWith("https://")).as("预签名链接必须是 HTTPS").isTrue();
            assertThat(URI.create(url).getHost()).isEqualTo(URI.create(urlPrefixHost()).getHost());

            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET()
                            .timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().length).isEqualTo(sampleSize);
        }
    }

    /**
     * 运营方配置里的 {@code urlPrefix} 是桶的公网域名：必须是可达域名，
     * 且与 SDK 生成的预签名链接同 host（否则复制出去的链接会验签失败）。
     */
    @Test
    void urlPrefixIsAReachableBucketDomain() throws Exception {
        assumeTrue(present(ENV_URL_PREFIX), "未配置 urlPrefix，跳过域名校验");
        URI uri = URI.create(urlPrefixHost());
        assertThat(uri.getHost()).isNotBlank();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        // 私有桶匿名访问桶根是 403，公有读是 200/404；两者都说明域名活着，
        // 连接失败 / 5xx / DNS 失败才是问题。
        assertThat(response.statusCode()).isLessThan(500).isGreaterThan(0);
    }

    /**
     * 服务端翻页：OSS ListObjects V1 单页上限 1000，产品 {@code flatList} 必须
     * 靠 NextMarker 一直翻到完 —— 拿原生 SDK 数出来的条数逐条对账。
     */
    @Test
    void flatListCrossesTheThousandObjectPageBoundary() {
        assumeTrue(pagingPrefix != null,
                "桶里没有「超过一页但不超过探测上限」的前缀，不造假数据也不全桶扫描");
        try (ObjectStoreSession s = session()) {
            List<ObjectEntry> rows = s.listObjects(bucket, pagingPrefix, null);
            assertThat(rows).as("跨页前缀 %s 的对象数应与原生 SDK 探测一致", pagingPrefix)
                    .hasSize(pagingExpected);
            assertThat(rows).extracting(ObjectEntry::key)
                    .doesNotHaveDuplicates()
                    .allSatisfy(key -> assertThat(key).startsWith(pagingPrefix));
        }
    }

    @Test
    void badEndpointAndIncompleteCredentialsFailClearly() {
        Map<String, String> bad = new HashMap<>(options);
        bad.put(ObjectStoreCredentials.OPT_ENDPOINT, "http://127.0.0.1:1");
        assertThat(ObjectStoreSessions.test(bad, storedCredentials)).isNotBlank();

        assertThatThrownBy(() -> ObjectStoreSessions.connect(options, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    /**
     * 可选写契约：默认关闭，必须显式 {@code ADB_TEST_OSS_WRITE=true}。
     * 只在独立前缀下写自己创建的对象，并在 finally 清理，绝不碰生产数据。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ADB_TEST_OSS_WRITE", matches = "true")
    void optInWriteRoundTripUnderADedicatedPrefix() {
        String prefix = "__aidb_oss_contract__/" + System.currentTimeMillis() + "/";
        byte[] data = "aidb oss real endpoint contract".getBytes(StandardCharsets.UTF_8);
        try (ObjectStoreSession s = session()) {
            try {
                ObjectMeta written = s.putObject(bucket, prefix + "a.txt", data, "text/plain");
                assertThat(written.size()).isEqualTo(data.length);
                assertThat(s.getObject(bucket, prefix + "a.txt")).isEqualTo(data);
                assertThat(s.metadata(bucket, prefix + "a.txt").contentType())
                        .startsWith("text/plain");

                s.moveObject(bucket, prefix + "a.txt", prefix + "b.txt");
                assertThat(s.getObject(bucket, prefix + "b.txt")).isEqualTo(data);
                assertThat(s.metadata(bucket, prefix + "b.txt").contentType())
                        .as("move 后 Content-Type 不许退化")
                        .startsWith("text/plain");
                assertThat(s.deletePrefix(bucket, prefix)).isEqualTo(1);
                assertThat(s.listObjects(bucket, prefix, null)).isEmpty();
            } finally {
                try {
                    s.deletePrefix(bucket, prefix);
                } catch (Exception ignored) {
                    // 清理失败留给人工核查，但不能掩盖主断言的失败。
                }
            }
        }
    }

    private static String urlPrefixHost() {
        return urlPrefix == null || urlPrefix.isBlank()
                ? "https://" + bucket + "." + URI.create(endpoint).getHost()
                : urlPrefix;
    }
}
