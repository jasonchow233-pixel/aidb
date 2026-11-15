package io.agenticdb.objectstore;

import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.Auth;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ⭐ 七牛云 Kodo <b>真账号 / 真端点</b>只读契约。
 *
 * <p>补齐 R2 的诚实边界：S3 兼容分支、阿里云 OSS 已有真端点证据后，
 * 四家原生 SDK 里 Kodo 一支仍是「只有编译期 SDK 类型 + 内存后端行为契约」。
 * 本类走产品同一条工厂 {@link ObjectStoreSessions#connect(Map, String)}，
 * 对运营方提供的真实 Kodo 账号验证：签名列举 / marker 翻页、delimiter 折叠、
 * stat 的 mimeType 与 putTime 换算、绑定域名下载与预签名 URL、能力诚实性
 * （无服务端 move、不谎称列全 bucket）、公开空间与私有空间两种读法。
 *
 * <p><b>凭据纪律</b>：endpoint / bucket / AK / SK / 私有空间参数一律从环境变量读，
 * 类里没有任何凭据字面量，也不写任何文件。默认 <b>opt-in</b> —— 没给环境变量整类跳过，
 * CI 不会误连生产账号。
 *
 * <p><b>写纪律</b>：默认全部只读，不写不删生产桶。唯一的写契约
 * {@link #optInWriteRoundTripUnderADedicatedPrefix()} 额外要求
 * {@code ADB_TEST_KODO_WRITE=true}，且只在独立前缀下写自己创建的对象并在 finally 清理。
 *
 * <p><b>规模纪律</b>：账号里可能有几十万对象，绝不做「列全桶」。样本只取原生 SDK 一页，
 * 列举契约用完整 key 或受限前缀；分页契约先探测前缀规模并设页数上限，
 * 不适合就 {@code assumeTrue} 跳过并写清原因，不造假数据。
 */
@EnabledIf("io.agenticdb.objectstore.QiniuRealEndpointContractTest#kodoConfigured")
class QiniuRealEndpointContractTest {

    private static final String ENV_ENDPOINT = "ADB_TEST_KODO_ENDPOINT";
    private static final String ENV_BUCKET = "ADB_TEST_KODO_BUCKET";
    private static final String ENV_AK = "ADB_TEST_KODO_AK";
    private static final String ENV_SK = "ADB_TEST_KODO_SK";
    private static final String ENV_PRIVATE_BUCKET = "ADB_TEST_KODO_PRIVATE_BUCKET";
    private static final String ENV_PRIVATE_DOMAIN = "ADB_TEST_KODO_PRIVATE_DOMAIN";

    /** 分页探测最多翻几页、最多试几个前缀；超了就跳过，不让契约变成全桶扫描。 */
    private static final int MAX_PROBE_PAGES = 8;
    private static final int MAX_PREFIX_CANDIDATES = 12;
    private static final String PROBE_PREFIX = "__aidb_kodo_contract_probe__/";

    private static String endpoint;
    private static String bucket;
    private static Map<String, String> options;
    private static String storedCredentials;
    private static BucketManager raw;

    /** 原生 SDK 探到的一页样本：最小对象、层级前缀、可跨页前缀。 */
    private static String sampleKey;
    private static long sampleSize;
    private static String sampleMime;
    private static String hierarchyPrefix;
    private static String pagingPrefix;
    private static int pagingExpected;

    /** 可选：私有空间的样本，用来验证匿名 401 / 签名 200 的差别。 */
    private static String privateBucket;
    private static String privateDomain;
    private static String privateSampleKey;
    private static long privateSampleSize;

    static boolean kodoConfigured() {
        return present(ENV_ENDPOINT) && present(ENV_BUCKET)
                && present(ENV_AK) && present(ENV_SK);
    }

    private static boolean present(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static boolean privateConfigured() {
        return present(ENV_PRIVATE_BUCKET) && present(ENV_PRIVATE_DOMAIN);
    }

    @BeforeAll
    static void setUp() {
        endpoint = System.getenv(ENV_ENDPOINT);
        bucket = System.getenv(ENV_BUCKET);
        options = Map.of(
                ObjectStoreCredentials.OPT_PROVIDER, ObjectStoreProvider.QINIU.id(),
                ObjectStoreCredentials.OPT_ENDPOINT, endpoint,
                ObjectStoreCredentials.OPT_BUCKET, bucket);
        storedCredentials = ObjectStoreCredentials.encode(
                System.getenv(ENV_AK), System.getenv(ENV_SK), "");
        raw = rawClient();

        // 只读一页：拿到样本对象、层级键（若有）、以及可跨页的前缀。
        FileListing page = listPage(raw, bucket, "", null, 20);
        FileInfo[] items = page.items == null ? new FileInfo[0] : page.items;
        assumeTrue(items.length > 0, "配置的 bucket 里没有对象，无法验证列举/下载契约");
        FileInfo smallest = items[0];
        // 真实桶里两类 key：随机 hash（F… 只有几十颗）与 STORE/<id>/<dir>/<file>
        // 层级键（数千颗）。分页素材必须取自后者，前者一页就完了。
        var candidatePrefixes = new LinkedHashSet<String>(List.of(
                "STORE/1963", "STORE/1960", "STORE/1", "STORE/1957", "F", "Fg"));
        for (FileInfo f : items) {
            if (f.fsize < smallest.fsize) {
                smallest = f;
            }
            if (f.key.length() >= 1) {
                candidatePrefixes.add(f.key.substring(0, 1));
            }
            if (f.key.length() >= 2) {
                candidatePrefixes.add(f.key.substring(0, 2));
            }
        }
        sampleKey = smallest.key;
        sampleSize = smallest.fsize;
        sampleMime = smallest.mimeType;

        // 层级前缀另取一页：STORE/<id>/ 下面真的有 default/ 与 undefined/ 两层，
        // delimiter 折叠契约要在真数据上验「造得出文件夹行」，不能只验平铺键。
        FileListing storePage = listPage(raw, bucket, "STORE/", null, 20);
        if (storePage.items != null && storePage.items.length > 0) {
            String nested = storePage.items[0].key;
            int firstSlash = nested.indexOf('/');
            int secondSlash = firstSlash < 0 ? -1 : nested.indexOf('/', firstSlash + 1);
            if (secondSlash > 0) {
                hierarchyPrefix = nested.substring(0, secondSlash + 1);
            }
        }

        int tried = 0;
        for (String candidate : candidatePrefixes) {
            if (tried++ >= MAX_PREFIX_CANDIDATES) {
                break;
            }
            int count = probeFlatCount(raw, bucket, candidate, MAX_PROBE_PAGES);
            if (count > 1000 && count <= MAX_PROBE_PAGES * 1000) {
                pagingPrefix = candidate;
                pagingExpected = count;
                break;
            }
        }

        if (privateConfigured()) {
            privateBucket = System.getenv(ENV_PRIVATE_BUCKET);
            privateDomain = System.getenv(ENV_PRIVATE_DOMAIN);
            FileListing privatePage = listPage(raw, privateBucket, "", null, 20);
            FileInfo[] privateItems = privatePage.items == null ? new FileInfo[0] : privatePage.items;
            if (privateItems.length > 0) {
                FileInfo privateSmallest = privateItems[0];
                for (FileInfo f : privateItems) {
                    if (f.fsize < privateSmallest.fsize) {
                        privateSmallest = f;
                    }
                }
                privateSampleKey = privateSmallest.key;
                privateSampleSize = privateSmallest.fsize;
            }
        }
    }

    private static BucketManager rawClient() {
        Auth auth = Auth.create(System.getenv(ENV_AK), System.getenv(ENV_SK));
        return new BucketManager(auth, new Configuration(Region.autoRegion()));
    }

    private static FileListing listPage(BucketManager bm, String bucketName,
                                        String prefix, String marker, int limit) {
        try {
            return bm.listFilesV2(bucketName, prefix, marker, limit, "");
        } catch (Exception e) {
            throw new IllegalStateException("Kodo 真端点探测失败: " + e.getMessage(), e);
        }
    }

    /** 原生 SDK 侧数一遍某前缀下的对象数；超过页数上限返回 -1。 */
    private static int probeFlatCount(BucketManager bm, String bucketName,
                                      String prefix, int maxPages) {
        int total = 0;
        String marker = null;
        for (int pageNo = 0; pageNo < maxPages; pageNo++) {
            FileListing page = listPage(bm, bucketName, prefix, marker, 1000);
            total += page.items == null ? 0 : page.items.length;
            if (page.isEOF() || page.marker == null || page.marker.isBlank()) {
                return total;
            }
            marker = page.marker;
        }
        return -1;
    }

    /** 所有被测操作都从产品工厂进，不绕路 new QiniuObjectStoreSession。 */
    private static ObjectStoreSession session() {
        return ObjectStoreSessions.connect(options, storedCredentials);
    }

    private static ObjectStoreSession privateSession() {
        Map<String, String> privateOptions = Map.of(
                ObjectStoreCredentials.OPT_PROVIDER, ObjectStoreProvider.QINIU.id(),
                ObjectStoreCredentials.OPT_ENDPOINT, privateDomain,
                ObjectStoreCredentials.OPT_BUCKET, privateBucket);
        return ObjectStoreSessions.connect(privateOptions, storedCredentials);
    }

    private static String normalizedDomain(String value) {
        String base = value.startsWith("http://") || value.startsWith("https://")
                ? value : "https://" + value;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static HttpResponse<byte[]> httpGet(String url) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET()
                        .timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void factoryConnectAndConnectionTestReachTheRealAccount() {
        try (ObjectStoreSession s = session()) {
            assertThat(s.provider()).isEqualTo(ObjectStoreProvider.QINIU);
            ObjectStoreCapabilities caps = s.capabilities();
            assertThat(caps.supportsMove()).as("Kodo 无服务端 move").isFalse();
            assertThat(caps.supportsListBuckets()).as("Kodo 不谎称能列全部 bucket").isFalse();
            assertThat(caps.supportsPresign()).isTrue();
            assertThat(s.listBuckets()).extracting(Bucket::name).containsExactly(bucket);
        }
        assertThat(ObjectStoreSessions.test(options, storedCredentials))
                .as("产品连接测试必须对真实 Kodo 账号成功，且做了一次真实签名握手").isNull();
    }

    /** 原生 SDK 能看到的空间不止一个，产品只回显配置桶 —— 两份事实必须对得上。 */
    @Test
    void configuredBucketExistsInTheRealAccount() {
        try {
            assertThat(Arrays.asList(raw.buckets())).contains(bucket);
        } catch (Exception e) {
            throw new IllegalStateException("Kodo buckets() 探测失败: " + e.getMessage(), e);
        }
        try {
            assertThat(Arrays.asList(raw.domainList(bucket)))
                    .as("配置的 endpoint 必须是该桶真实绑定的下载域名")
                    .contains(URI.create(normalizedDomain(endpoint)).getHost());
        } catch (Exception e) {
            throw new IllegalStateException("Kodo domainList() 探测失败: " + e.getMessage(), e);
        }
    }

    @Test
    void anExactKeyPrefixListsOnlyThatRealObject() {
        try (ObjectStoreSession s = session()) {
            List<ObjectEntry> rows = s.listObjects(bucket, sampleKey, null);
            assertThat(rows).as("prefix=完整 key 时应命中该对象").isNotEmpty();
            assertThat(rows).extracting(ObjectEntry::key).contains(sampleKey);
            assertThat(rows).allSatisfy(row -> assertThat(row.key()).startsWith(sampleKey));
        }
    }

    @Test
    void delimiterListingHonorsTheRealKeySpace() {
        try (ObjectStoreSession s = session()) {
            // prefix 取完整 key：列举范围 = 1 条，绝不对生产桶做全量扫描。
            List<ObjectEntry> flat = s.listObjects(bucket, sampleKey, "/");
            assertThat(flat).isNotEmpty();
            assertThat(flat).allSatisfy(row -> assertThat(row.key()).startsWith(sampleKey));
            assertThat(flat.stream().noneMatch(ObjectEntry::folder))
                    .as("prefix 是完整 key 且不含 / 时，不该凭空造出文件夹行").isTrue();
            assumeTrue(hierarchyPrefix != null, "桶里没有层级键，跳过文件夹折叠契约");
            List<ObjectEntry> nested = s.listObjects(bucket, hierarchyPrefix, "/");
            assertThat(nested).as("真层级前缀下必须折得出文件夹行").isNotEmpty();
            assertThat(nested).allSatisfy(row -> {
                assertThat(row.folder()).isTrue();
                assertThat(row.key()).startsWith(hierarchyPrefix).endsWith("/");
            });
        }
    }

    /**
     * stat 的 mimeType 是 Content-Type（不是 storageClass），putTime 单位是 100 纳秒
     * —— 除以 10000 得到毫秒，否则「最后修改」会显示成 1970 年。
     */
    @Test
    void metadataUsesQiniuMimeAndPutTimeScale() {
        try (ObjectStoreSession s = session()) {
            ObjectMeta meta = s.metadata(bucket, sampleKey);
            assertThat(meta.key()).isEqualTo(sampleKey);
            assertThat(meta.size()).isEqualTo(sampleSize);
            assertThat(meta.etag()).isNotBlank();
            assertThat(meta.contentType())
                    .as("Kodo 的 mimeType 必须落在独立 contentType 字段")
                    .isEqualTo(sampleMime == null ? "" : sampleMime);
            assertThat(meta.contentType()).isNotEqualTo(meta.storageClass());
            assertThat(meta.lastModified()).isNotNull();
            assertThat(meta.lastModified())
                    .as("putTime / 10000 才是毫秒；1970 年说明换算丢了四个零")
                    .isAfter(Instant.parse("2000-01-01T00:00:00Z"))
                    .isBefore(Instant.parse("2100-01-01T00:00:00Z"));
        }
    }

    @Test
    void getObjectAndPresignedUrlAreRealHttpReads() throws Exception {
        assumeTrue(sampleSize <= 1024 * 1024,
                "样本对象大于 1 MiB，跳过下载以避免对生产账号造成流量压力");
        try (ObjectStoreSession s = session()) {
            byte[] data = s.getObject(bucket, sampleKey);
            assertThat(data).hasSize((int) sampleSize);

            String url = s.presignUrl(bucket, sampleKey, 120);
            assertThat(url.startsWith("https://")).as("预签名链接必须是 HTTPS").isTrue();
            assertThat(URI.create(url).getHost())
                    .isEqualTo(URI.create(normalizedDomain(endpoint)).getHost());
            HttpResponse<byte[]> response = httpGet(url);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(data);
        }
    }

    /** 能力差异的诚实性：UI 置灰、后端抛 UOE，且不许偷偷产生写操作。 */
    @Test
    void serverSideMoveIsHonestlyRejected() {
        try (ObjectStoreSession s = session()) {
            assertThatThrownBy(() -> s.moveObject(bucket, sampleKey, sampleKey + ".moved"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("move");
        }
    }

    /** 私有空间：匿名读被拒，产品的签名 URL 与 getObject 必须能读回真实字节。 */
    @Test
    void privateBucketNeedsTheSignedUrl() throws Exception {
        assumeTrue(privateConfigured(), "未配置私有空间，跳过公有/私有读法对比");
        assumeTrue(privateSampleKey != null, "私有空间没有对象，无法验证签名读");
        assumeTrue(privateSampleSize <= 1024 * 1024, "私有样本大于 1 MiB，跳过下载");
        String anonymous = normalizedDomain(privateDomain) + "/" + privateSampleKey;
        assertThat(httpGet(anonymous).statusCode())
                .as("私有空间匿名读必须被拒绝")
                .isIn(401, 403);
        try (ObjectStoreSession s = privateSession()) {
            assertThat(s.getObject(privateBucket, privateSampleKey))
                    .hasSize((int) privateSampleSize);
            assertThat(httpGet(s.presignUrl(privateBucket, privateSampleKey, 120)).statusCode())
                    .isEqualTo(200);
        }
    }

    /** Kodo 的下载域名是必填项：不配就明确报错，不猜一个域名。 */
    @Test
    void missingDownloadDomainFailsClearly() {
        Map<String, String> noDomain = new HashMap<>(options);
        noDomain.put(ObjectStoreCredentials.OPT_ENDPOINT, "");
        try (ObjectStoreSession s = ObjectStoreSessions.connect(noDomain, storedCredentials)) {
            assertThatThrownBy(() -> s.presignUrl(bucket, sampleKey, 60))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("download domain");
        }
    }

    /**
     * 服务端翻页：Kodo listFilesV2 单页上限 1000，产品 {@code flatList} 必须靠 marker
     * 一直翻到完 —— 拿原生 SDK 数出来的条数逐条对账。
     */
    @Test
    void flatListCrossesTheMarkerPageBoundary() {
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

    /**
     * ⭐ 真端点抓出的缺陷回归：以前 test() 只调 Kodo 的本地 listBuckets() 回显，
     * 坏 AK/SK 也会「连接测试通过」。现在 test() 追加一次不存在前缀的真实签名列举。
     */
    @Test
    void badCredentialsAndIncompleteCredentialsFailClearly() {
        assertThat(ObjectStoreSessions.test(options, null))
                .as("缺凭据必须明确失败").isNotBlank();
        String badCredentials = ObjectStoreCredentials.encode(
                "bad-access-key", "bad-secret-key", "");
        assertThat(ObjectStoreSessions.test(options, badCredentials))
                .as("坏 AK/SK 必须被真实握手挡下，不能靠本地 listBuckets 回显假绿")
                .isNotBlank();

        assertThatThrownBy(() -> ObjectStoreSessions.connect(options, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    /**
     * 可选写契约：默认关闭，必须显式 {@code ADB_TEST_KODO_WRITE=true}。
     * 只在独立前缀下写自己创建的对象，并在 finally 清理，绝不碰生产数据。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ADB_TEST_KODO_WRITE", matches = "true")
    void optInWriteRoundTripUnderADedicatedPrefix() {
        String prefix = PROBE_PREFIX + System.currentTimeMillis() + "/";
        byte[] data = "aidb kodo real endpoint contract".getBytes(StandardCharsets.UTF_8);
        try (ObjectStoreSession s = session()) {
            try {
                ObjectMeta written = s.putObject(bucket, prefix + "a.txt", data, "text/plain");
                assertThat(written.size()).isEqualTo(data.length);
                assertThat(s.getObject(bucket, prefix + "a.txt")).isEqualTo(data);
                assertThat(s.metadata(bucket, prefix + "a.txt").contentType())
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
}
