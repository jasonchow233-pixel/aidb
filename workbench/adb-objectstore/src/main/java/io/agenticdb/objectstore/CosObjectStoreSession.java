package io.agenticdb.objectstore;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯云 COS 适配器（{@code cos_api}）。
 *
 * <p>region 形如 {@code ap-guangzhou}；凭据 SecretId / SecretKey。
 * 列举按 marker 翻页。SDK 与 OSS 形态接近但类型完全独立，故单独一个适配器。
 *
 * <p>⚠️ 四家原生 SDK 里<b>只剩 COS 一支无真端点证据</b>（R2 剩余阻塞项）：
 * 本类只有编译期 SDK 类型 + 内存后端行为契约，不宣称连过真实 COS。
 * 分页入口同样按 COS 的 marker / commonPrefixes 语义实现，但未在真端点上验过。
 */
public final class CosObjectStoreSession extends AbstractObjectStoreSession {

    private final COSClient client;

    public CosObjectStoreSession(ObjectStoreCredentials c) {
        super(c);
        if (!c.complete()) {
            throw new IllegalArgumentException("COS requires secretId and secretKey");
        }
        String region = c.region().isBlank() ? "ap-guangzhou" : c.region();
        ClientConfig config = new ClientConfig(new Region(region));
        this.client = new COSClient(
                new BasicCOSCredentials(c.accessKey(), c.secretKey()), config);
    }

    @Override
    public List<Bucket> listBuckets() {
        List<Bucket> out = new ArrayList<>();
        for (com.qcloud.cos.model.Bucket b : client.listBuckets()) {
            out.add(new Bucket(b.getName(), SdkSupport.instant(b.getCreationDate())));
        }
        return out;
    }

    @Override
    protected List<ObjectMeta> flatList(String bucket, String prefix) {
        List<ObjectMeta> out = new ArrayList<>();
        ListObjectsRequest req = new ListObjectsRequest();
        req.setBucketName(bucket);
        req.setPrefix(prefix);
        req.setMaxKeys(1000);
        while (true) {
            ObjectListing page = client.listObjects(req);
            for (COSObjectSummary s : page.getObjectSummaries()) {
                out.add(meta(s));
            }
            if (!page.isTruncated()) {
                break;
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
     * ⭐ 浏览页的真分页：COS 的 marker + maxKeys + 服务端 {@code commonPrefixes} 折叠。
     * ⚠️ 这一支仍只有编译期 SDK 类型与内存后端行为契约，没有真端点证据（R2 仅剩 COS）。
     */
    @Override
    public ObjectPage listObjectsPage(String bucket, String prefix, String delimiter,
                                      String cursor, int maxKeys) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeDelimiter = delimiter == null ? "" : delimiter;
        String marker = PageCursor.tokenFor(cursor, bucket, safePrefix, safeDelimiter);
        ListObjectsRequest req = new ListObjectsRequest();
        req.setBucketName(bucket);
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
        for (COSObjectSummary s : page.getObjectSummaries()) {
            rows.putIfAbsent(s.getKey(), ObjectEntry.object(meta(s)));
        }
        String token = page.getNextMarker();
        String next = page.isTruncated() && token != null && !token.isBlank()
                ? PageCursor.of(bucket, safePrefix, safeDelimiter, token) : null;
        return new ObjectPage(new ArrayList<>(rows.values()), next);
    }

    private static ObjectMeta meta(COSObjectSummary s) {
        return new ObjectMeta(s.getKey(), s.getSize(),
                SdkSupport.instant(s.getLastModified()),
                SdkSupport.safe(s.getETag()), "", "");
    }

    @Override
    public byte[] getObject(String bucket, String key) {
        COSObject object = client.getObject(bucket, key);
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
