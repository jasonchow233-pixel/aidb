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
import java.util.List;

/**
 * 阿里云 OSS 适配器（{@code aliyun-sdk-oss}）。
 *
 * <p>endpoint 必填（形如 {@code https://oss-cn-hangzhou.aliyuncs.com}）；
 * 凭据为 AccessKeyId / AccessKeySecret。列举按 marker 翻页直到取完，
 * 不做「只显示第一页」的假完整。
 *
 * <p>⚠️ 真实云端连通性属 P4-3 的 R2 阻塞项；行为门禁在内存后端验证。
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
                out.add(new ObjectMeta(s.getKey(), s.getSize(),
                        SdkSupport.instant(s.getLastModified()),
                        SdkSupport.safe(s.getETag()), ""));
            }
            String next = page.getNextMarker();
            if (next == null || next.isBlank()) {
                break;
            }
            req.setMarker(next);
        }
        return out;
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
                SdkSupport.instant(m.getLastModified()), SdkSupport.safe(m.getETag()), "");
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
