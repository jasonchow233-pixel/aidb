package io.agenticdb.objectstore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>内存 / 离线对象存储后端</b> —— 完整实现 {@link ObjectStoreSession} 全部语义。
 *
 * <p>三个用途：
 * <ol>
 *   <li><b>行为门禁</b>：在无网络、无云账号的环境里验证列举 / 上传 / 下载 /
 *       递归删 / folder 折叠 / 能力置灰，与真 Redis 容器的 {@code RedisMutateTest} 同定位；</li>
 *   <li><b>演示连接</b>：AIDB 的 demo 连接可以挂一个内存 bucket，让新用户不配账号
 *       就能走通「树 → 浏览器 → 上传/下载/删除」全链路；</li>
 *   <li><b>协议参考实现</b>：四家 SDK 适配器以它为语义基准，folder 折叠等规则以基类为准。</li>
 * </ol>
 *
 * <p>诚实标注：这里的数据只在进程内存里，进程退出即丢失，<b>不是</b>云端适配器。
 * 真实云账号由 {@link ObjectStoreSessions#connect} 分派到四家 SDK 会话。
 */
public final class InMemoryObjectStoreSession extends AbstractObjectStoreSession {

    /** bucket → (key → 内容)。用 ConcurrentHashMap 让后台线程的读写不串数据。 */
    private final Map<String, Map<String, StoredObject>> buckets = new ConcurrentHashMap<>();
    private final Map<String, Instant> created = new ConcurrentHashMap<>();

    private record StoredObject(byte[] data, String contentType, Instant lastModified,
                                String etag, String storageClass) {
    }

    public InMemoryObjectStoreSession(ObjectStoreCredentials credentials) {
        super(credentials);
        // 配置里指定了 bucket 就先建出来；没指定时给一个演示桶，避免空树无处下手。
        String configured = credentials.bucket();
        createBucket(configured.isBlank() ? "demo-bucket" : configured);
    }

    /** 测试/演示用：直接建桶。 */
    public void createBucket(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("bucket name must not be blank");
        }
        buckets.computeIfAbsent(name, k -> new LinkedHashMap<>());
        created.putIfAbsent(name, Instant.now());
    }

    @Override
    public List<Bucket> listBuckets() {
        if (!capabilities().supportsListBuckets()) {
            // 七牛：只暴露配置里那一个桶，不谎称能列出全部。
            String configured = credentials.bucket();
            if (configured.isBlank()) {
                return List.of();
            }
            return List.of(new Bucket(configured, created.getOrDefault(configured, Instant.EPOCH)));
        }
        List<Bucket> out = new ArrayList<>();
        for (String name : new java.util.TreeSet<>(buckets.keySet())) {
            out.add(new Bucket(name, created.getOrDefault(name, Instant.EPOCH)));
        }
        return out;
    }

    @Override
    protected List<ObjectMeta> flatList(String bucket, String prefix) {
        Map<String, StoredObject> objects = buckets.get(bucket);
        if (objects == null) {
            return List.of();
        }
        String safePrefix = prefix == null ? "" : prefix;
        List<ObjectMeta> out = new ArrayList<>();
        synchronized (objects) {
            for (Map.Entry<String, StoredObject> e : objects.entrySet()) {
                if (!e.getKey().startsWith(safePrefix)) {
                    continue;
                }
                StoredObject o = e.getValue();
                out.add(new ObjectMeta(e.getKey(), o.data().length, o.lastModified(),
                        o.etag(), o.storageClass(), SdkSupport.safe(o.contentType())));
            }
        }
        out.sort(java.util.Comparator.comparing(ObjectMeta::key));
        return out;
    }

    @Override
    public byte[] getObject(String bucket, String key) {
        StoredObject o = require(bucket, key);
        return o.data().clone();
    }

    @Override
    public ObjectMeta putObject(String bucket, String key, byte[] data, String contentType) {
        Map<String, StoredObject> objects = requireBucket(bucket);
        byte[] copy = data == null ? new byte[0] : data.clone();
        Instant now = Instant.now();
        String etag = md5Hex(copy);
        String safeType = contentType == null ? "" : contentType;
        synchronized (objects) {
            // 内存后端没有存储类型概念：storageClass 留空，contentType 单独存，
            // 不再把 MIME 塞进 storageClass（真端点 move 契约钉住这一点）。
            objects.put(key, new StoredObject(copy, safeType, now, etag, ""));
        }
        return new ObjectMeta(key, copy.length, now, etag, "", safeType);
    }

    @Override
    public void deleteObject(String bucket, String key) {
        Map<String, StoredObject> objects = requireBucket(bucket);
        synchronized (objects) {
            objects.remove(key);
        }
    }

    @Override
    public ObjectMeta metadata(String bucket, String key) {
        StoredObject o = require(bucket, key);
        return new ObjectMeta(key, o.data().length, o.lastModified(), o.etag(),
                o.storageClass(), SdkSupport.safe(o.contentType()));
    }

    @Override
    public String presignUrl(String bucket, String key, long ttlSeconds) {
        require(bucket, key);
        // 内存后端不对外提供 HTTP 地址；给一条明确标注的本地伪 URL，不谎称是云签名链接。
        return "memory://" + bucket + "/" + key + "?expiresIn=" + ttlSeconds;
    }

    private Map<String, StoredObject> requireBucket(String bucket) {
        Map<String, StoredObject> objects = buckets.get(bucket);
        if (objects == null) {
            throw new IllegalArgumentException("no such bucket: " + bucket);
        }
        return objects;
    }

    private StoredObject require(String bucket, String key) {
        Map<String, StoredObject> objects = requireBucket(bucket);
        synchronized (objects) {
            StoredObject o = objects.get(key);
            if (o == null) {
                throw new IllegalArgumentException("no such object: " + bucket + "/" + key);
            }
            return o;
        }
    }

    private static String md5Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }
}
