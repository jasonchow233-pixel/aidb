package io.agenticdb.objectstore;

import io.agenticdb.asset.ObjectStoreProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 四家 provider 会话的公共骨架 —— 把「与 SDK 无关」的逻辑收在一处。
 *
 * <p>子类只需实现六个原语（{@link #flatList} / get / put / delete / metadata / presign）
 * 与 {@link #listBuckets()}；文件夹折叠、递归删前缀、按能力拒绝 move 这些
 * <b>语义级</b>行为在这里统一，避免四家各写一遍而行为漂移。
 *
 * <p>folder 折叠是对象存储的关键语义：四家都没有真目录，只有 key 里的 {@code /}。
 * 传 delimiter 时，把 {@code prefix} 之后第一段以 delimiter 结尾的键折成一个
 * {@link ObjectEntry#folder(String)} 行；不传 delimiter 就平铺全部对象（递归删用）。
 */
public abstract class AbstractObjectStoreSession implements ObjectStoreSession {

    private final ObjectStoreProvider provider;
    private final ObjectStoreCapabilities capabilities;
    protected final ObjectStoreCredentials credentials;

    protected AbstractObjectStoreSession(ObjectStoreCredentials credentials) {
        if (credentials == null || credentials.provider() == null) {
            throw new IllegalArgumentException("object store credentials/provider must not be null");
        }
        this.credentials = credentials;
        this.provider = credentials.provider();
        this.capabilities = ObjectStoreCapabilities.forProvider(provider);
    }

    @Override
    public final ObjectStoreProvider provider() {
        return provider;
    }

    @Override
    public final ObjectStoreCapabilities capabilities() {
        return capabilities;
    }

    /** 平铺列举（不做 delimiter 折叠），由具体 SDK 适配器实现。 */
    protected abstract List<ObjectMeta> flatList(String bucket, String prefix);

    @Override
    public List<ObjectEntry> listObjects(String bucket, String prefix, String delimiter) {
        String safePrefix = prefix == null ? "" : prefix;
        List<ObjectMeta> all = flatList(bucket, safePrefix);
        if (delimiter == null || delimiter.isBlank()) {
            List<ObjectEntry> rows = new ArrayList<>();
            for (ObjectMeta meta : all) {
                rows.add(ObjectEntry.object(meta));
            }
            return rows;
        }
        Set<String> folders = new LinkedHashSet<>();
        List<ObjectEntry> rows = new ArrayList<>();
        for (ObjectMeta meta : all) {
            String key = meta.key();
            String rest = key.startsWith(safePrefix) ? key.substring(safePrefix.length()) : key;
            int cut = rest.indexOf(delimiter);
            if (cut >= 0) {
                String folderPrefix = safePrefix + rest.substring(0, cut + delimiter.length());
                if (folders.add(folderPrefix)) {
                    rows.add(ObjectEntry.folder(folderPrefix));
                }
            } else {
                rows.add(ObjectEntry.object(meta));
            }
        }
        return rows;
    }

    @Override
    public int deletePrefix(String bucket, String prefix) {
        List<ObjectMeta> all = flatList(bucket, prefix == null ? "" : prefix);
        int removed = 0;
        for (ObjectMeta meta : all) {
            deleteObject(bucket, meta.key());
            removed++;
        }
        return removed;
    }

    @Override
    public void moveObject(String bucket, String from, String to) {
        if (!capabilities.supportsMove()) {
            throw new UnsupportedOperationException(
                    provider.displayName() + " has no server-side move; use copy + delete instead");
        }
        byte[] data = getObject(bucket, from);
        ObjectMeta old = metadata(bucket, from);
        putObject(bucket, to, data, old == null ? "" : old.storageClass());
        deleteObject(bucket, from);
    }

    @Override
    public void close() {
        // 默认无资源可释放；持 SDK 客户端的子类自行覆写。
    }
}
