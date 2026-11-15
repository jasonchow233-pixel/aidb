package io.agenticdb.objectstore;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 OSS 适配器（{@code aliyun-sdk-oss}）。
 *
 * <p>endpoint 必填（形如 {@code https://oss-cn-hangzhou.aliyuncs.com}）；
 * 凭据为 AccessKeyId / AccessKeySecret。列举按 marker 翻页直到取完，
 * 不做「只显示第一页」的假完整。
 *
 * <p>⭐ 原生 SDK 已有真账号只读证据：{@code OssRealEndpointContractTest} 走产品同一条
 * 工厂连真实北京区域账号，验过列桶 / 精确前缀 / delimiter / ListObjects V1 跨千条页边界 /
 * metadata 的 contentType / 预签名 URL 真 HTTPS GET。凭据只从环境变量读，
 * 写契约 opt-in；这仍是<b>一家账号的只读证据</b>，不是「所有 OSS 端点都验过」。
 */
public final class OssObjectStoreSession extends AbstractObjectStoreSession {

    private final OSS client;

    public OssObjectStoreSession(ObjectStoreCredentials c) {
        super(c);
        if (!c.complete()) {
            throw new IllegalArgumentException("OSS requires accessKey and secretKey");
        }
        if (c.endpoint().isBlank()) {
            throw new IllegalArgumentException("OSS requires an endpoint");
        }
        this.client = new OSSClientBuilder().build(
                c.endpoint(), c.accessKey(), c.secretKey());
    }

    @Override
    public List<Bucket> listBuckets() {
        List<Bucket> out = new ArrayList<>();
        for (com.aliyun.oss.model.Bucket b : client.listBuckets()) {
            out.add(new Bucket(b.getName(), SdkSupport.instant(b.getCreationDate())));
        }
        return out;
    }

    @Override
    protected List<ObjectMeta> flatList(String bucket, String prefix) {
        List<ObjectMeta> out = new ArrayList<>();
        ListObjectsRequest req = new ListObjectsRequest(bucket);
        req.setPrefix(prefix);
        req.setMaxKeys(1000);
        while (true) {
            ObjectListing page = client.listObjects(req);
            for (OSSObjectSummary s : page.getObjectSummaries()) {
                out.add(meta(s));
            }
            String next = page.getNextMarker();
            if (next == null || next.isBlank()) {
                break;
            }
            req.setMarker(next);
        }
        return out;
    }

    /**
     * ⭐ 浏览页的真分页：ListObjects V1 的 marker + maxKeys，文件夹由服务端
     * {@code commonPrefixes} 折叠。真账号那个 &gt;12000 对象的桶就是这条入口要解决的形状。
     */
    @Override
    public ObjectPage listObjectsPage(String bucket, String prefix, String delimiter,
                                      String cursor, int maxKeys) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeDelimiter = delimiter == null ? "" : delimiter;
        String marker = PageCursor.tokenFor(cursor, bucket, safePrefix, safeDelimiter);
        ListObjectsRequest req = new ListObjectsRequest(bucket);
        req.setPrefix(safePrefix);
        req.setMaxKeys(PageCursor.pageSize(maxKeys));
        if (!safeDelimiter.isBlank()) {
            req.setDelimiter(safeDelimiter);
        }
        if (marker != null) {
            req.setMarker(marker);
        }
        ObjectListing page = client.listObjects(req);
        Map<String, ObjectEntry> rows = new LinkedHashMap<>();
        if (page.getCommonPrefixes() != null) {
            for (String cp : page.getCommonPrefixes()) {
                rows.putIfAbsent(cp, ObjectEntry.folder(cp));
            }
        }
        for (OSSObjectSummary s : page.getObjectSummaries()) {
            rows.putIfAbsent(s.getKey(), ObjectEntry.object(meta(s)));
        }
        String next = null;
        if (page.isTruncated()) {
            String token = page.getNextMarker();
            if (token == null || token.isBlank()) {
                // V1 在平铺模式下不保证回 nextMarker：约定用本页最后一条 key 当下一页起点。
                for (String key : rows.keySet()) {
                    token = key;
                }
            }
            if (token != null && !token.isBlank()) {
                next = PageCursor.of(bucket, safePrefix, safeDelimiter, token);
            }
        }
        return new ObjectPage(new ArrayList<>(rows.values()), next);
    }

    private static ObjectMeta meta(OSSObjectSummary s) {
        return new ObjectMeta(s.getKey(), s.getSize(),
                SdkSupport.instant(s.getLastModified()),
                SdkSupport.safe(s.getETag()), "", "");
    }

    @Override
    public byte[] getObject(String bucket, String key) {
        OSSObject object = client.getObject(bucket, key);
        return SdkSupport.readAll(object.getObjectContent());
    }

    @Override
    public ObjectMeta putObject(String bucket, String key, byte[] data, String contentType) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }
        client.putObject(bucket, key, new ByteArrayInputStream(data), meta);
        return metadata(bucket, key);
    }

    @Override
    public void deleteObject(String bucket, String key) {
        client.deleteObject(bucket, key);
    }

    @Override
    public ObjectMeta metadata(String bucket, String key) {
        ObjectMetadata m = client.getObjectMetadata(bucket, key);
        return new ObjectMeta(key, m.getContentLength(),
                SdkSupport.instant(m.getLastModified()), SdkSupport.safe(m.getETag()), "",
                SdkSupport.safe(m.getContentType()));
    }

    @Override
    public String presignUrl(String bucket, String key, long ttlSeconds) {
        Date expiry = new Date(System.currentTimeMillis() + Math.max(1L, ttlSeconds) * 1000L);
        return client.generatePresignedUrl(bucket, key, expiry).toString();
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
