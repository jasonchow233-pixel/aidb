package io.agenticdb.objectstore;

import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.Auth;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 七牛云 Kodo 适配器（{@code qiniu-java-sdk}）—— <b>能力差异最大</b>的一家。
 *
 * <p>诚实声明（{@link ObjectStoreCapabilities}）：
 * <ul>
 *   <li><b>不能列出全部 bucket</b>：只暴露连接配置里指定的那一个桶，不谎称列全；</li>
 *   <li><b>没有服务端 move</b>：{@code move=false}，UI 把「移动 / 重命名」置灰，
 *       基类 {@link AbstractObjectStoreSession#moveObject} 直接抛 UOE，
 *       不给「点了没反应」的假控件；</li>
 *   <li>下载需要<b>绑定域名</b>：{@code endpoint} 在这里是下载域名而非 API 端点，
 *       不配置就明确报错，不猜一个。</li>
 * </ul>
 *
 * <p>putTime 单位是 100 纳秒，除以 10000 才是毫秒 —— 这里统一换算，
 * 避免 UI 上的「最后修改」显示成公元 1970 年。
 *
 * <p>⭐ 原生 SDK 已有真账号只读证据：{@code QiniuRealEndpointContractTest} 对公开 /
 * 私有两空间验过签名列举 / {@code STORE/} 层级键 4 页 3427 条跨 marker 页边界 /
 * stat 的 mimeType 与 putTime / 公开与签名 URL 真 HTTPS GET / 坏凭据必须失败。
 * 凭据只从环境变量读，写契约 opt-in。
 */
public final class QiniuObjectStoreSession extends AbstractObjectStoreSession {

    private final Auth auth;
    private final BucketManager bucketManager;
    private final UploadManager uploadManager;

    public QiniuObjectStoreSession(ObjectStoreCredentials c) {
        super(c);
        if (!c.complete()) {
            throw new IllegalArgumentException("Qiniu requires accessKey and secretKey");
        }
        this.auth = Auth.create(c.accessKey(), c.secretKey());
        Configuration config = new Configuration(Region.autoRegion());
        this.bucketManager = new BucketManager(auth, config);
        this.uploadManager = new UploadManager(config);
    }

    @Override
    public List<Bucket> listBuckets() {
        // 七牛 SDK 的 buckets() 需要管理态权限且不在标准只读令牌里；
        // 诚实做法：只给配置里那一个桶，并在能力声明里标 supportsListBuckets=false。
        return credentials.bucket().isBlank()
                ? List.of()
                : List.of(new Bucket(credentials.bucket(), Instant.EPOCH));
    }

    @Override
    protected List<ObjectMeta> flatList(String bucket, String prefix) {
        List<ObjectMeta> out = new ArrayList<>();
        String marker = null;
        try {
            while (true) {
                FileListing page = bucketManager.listFilesV2(bucket, prefix, marker, 1000, "");
                if (page.items != null) {
                    for (FileInfo f : page.items) {
                        out.add(meta(f));
                    }
                }
                if (page.isEOF() || page.marker == null || page.marker.isBlank()) {
                    break;
                }
                marker = page.marker;
            }
        } catch (QiniuException e) {
            throw SdkSupport.wrap("listObjects", e);
        }
        return out;
    }

    /**
     * ⭐ 浏览页的真分页：Kodo 的 listFilesV2 传 delimiter 时由服务端给
     * {@code commonPrefixes}，续页凭证是 marker。
     *
     * <p>⚠️ {@code FileListing.isEOF()} 在 SDK 里就是「marker 为空」（已核字节码），
     * 因此有无下一页的唯一判据是 marker 非空；UI 侧按 key 去重，重复页不会插回两遍。
     */
    @Override
    public ObjectPage listObjectsPage(String bucket, String prefix, String delimiter,
                                      String cursor, int maxKeys) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeDelimiter = delimiter == null ? "" : delimiter;
        String marker = PageCursor.tokenFor(cursor, bucket, safePrefix, safeDelimiter);
        try {
            FileListing page = bucketManager.listFilesV2(
                    bucket, safePrefix, marker, PageCursor.pageSize(maxKeys), safeDelimiter);
            Map<String, ObjectEntry> rows = new LinkedHashMap<>();
            if (page.commonPrefixes != null) {
                for (String cp : page.commonPrefixes) {
                    rows.putIfAbsent(cp, ObjectEntry.folder(cp));
                }
            }
            if (page.items != null) {
                for (FileInfo f : page.items) {
                    rows.putIfAbsent(f.key, ObjectEntry.object(meta(f)));
                }
            }
            String next = page.marker != null && !page.marker.isBlank()
                    ? PageCursor.of(bucket, safePrefix, safeDelimiter, page.marker) : null;
            return new ObjectPage(new ArrayList<>(rows.values()), next);
        } catch (QiniuException e) {
            throw SdkSupport.wrap("listObjectsPage", e);
        }
    }

    private static ObjectMeta meta(FileInfo f) {
        // ⭐ mimeType 是 MIME，不是存储类型：早年塞进 storageClass 是语义错位，
        //    与 S3/OSS/COS 的 contentType 字段对齐（R2 真端点契约抓出）。
        return new ObjectMeta(f.key, f.fsize, qiniuTime(f.putTime),
                SdkSupport.safe(f.hash), "", SdkSupport.safe(f.mimeType));
    }

    @Override
    public byte[] getObject(String bucket, String key) {
        String url = signedUrl(key, 300L);
        try (InputStream in = URI.create(url).toURL().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw SdkSupport.wrap("getObject", e);
        }
    }

    @Override
    public ObjectMeta putObject(String bucket, String key, byte[] data, String contentType) {
        try {
            String token = auth.uploadToken(bucket, key);
            uploadManager.put(data, key, token);
        } catch (QiniuException e) {
            throw SdkSupport.wrap("putObject", e);
        }
        return metadata(bucket, key);
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            bucketManager.delete(bucket, key);
        } catch (QiniuException e) {
            throw SdkSupport.wrap("deleteObject", e);
        }
    }

    @Override
    public ObjectMeta metadata(String bucket, String key) {
        try {
            FileInfo info = bucketManager.stat(bucket, key);
            return new ObjectMeta(key, info.fsize, qiniuTime(info.putTime),
                    SdkSupport.safe(info.hash), "", SdkSupport.safe(info.mimeType));
        } catch (QiniuException e) {
            throw SdkSupport.wrap("metadata", e);
        }
    }

    @Override
    public String presignUrl(String bucket, String key, long ttlSeconds) {
        return signedUrl(key, ttlSeconds);
    }

    private String signedUrl(String key, long ttlSeconds) {
        String domain = credentials.endpoint();
        if (domain.isBlank()) {
            throw new UnsupportedOperationException(
                    "Qiniu requires a bound download domain (endpoint) to read objects");
        }
        String base = domain.startsWith("http://") || domain.startsWith("https://")
                ? domain : "https://" + domain;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        long deadline = System.currentTimeMillis() / 1000L + Math.max(1L, ttlSeconds);
        return auth.privateDownloadUrlWithDeadline(base + key, deadline);
    }

    private static Instant qiniuTime(long putTime) {
        return Instant.ofEpochMilli(putTime / 10000L);
    }
}
