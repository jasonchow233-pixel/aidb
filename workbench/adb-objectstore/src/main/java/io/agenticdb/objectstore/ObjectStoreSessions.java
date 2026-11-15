package io.agenticdb.objectstore;

import io.agenticdb.asset.ObjectStoreProvider;

import java.util.Map;

/**
 * 对象存储会话工厂 —— 按连接档案里的 provider 分派到四家 SDK 适配器。
 *
 * <p>与 {@code RedisSessions.connect} 同口径：静态工厂、口令由调用方从
 * {@code CredentialStore} 取出后传入、非敏感参数从 {@code options()} 读，
 * 返回接口类型而不是具体实现类。
 *
 * <p>⚠️ 这里<b>不</b>硬依赖 {@code adb-session} 的 {@code ConnectionProfile}：
 * 只用 {@code Map<String,String> options} 就够，避免后端模块为了读几个字符串
 * 背上一整条会话依赖链。调用方传 {@code profile.options()} 即可。
 */
public final class ObjectStoreSessions {

    private ObjectStoreSessions() {
    }

    /**
     * 连接真实云账号。凭据串由 {@code CredentialStore} 保存（见
     * {@link ObjectStoreCredentials#encode}），options 里放 provider/endpoint/region/bucket。
     */
    public static ObjectStoreSession connect(Map<String, String> options, String storedCredentials) {
        ObjectStoreCredentials c = ObjectStoreCredentials.of(() -> options, storedCredentials);
        if (!c.complete()) {
            throw new IllegalArgumentException("object store credentials are incomplete");
        }
        return switch (c.provider()) {
            case S3 -> new S3ObjectStoreSession(c);
            case OSS -> new OssObjectStoreSession(c);
            case QINIU -> new QiniuObjectStoreSession(c);
            case COS -> new CosObjectStoreSession(c);
        };
    }

    /** 离线 / 演示 / 行为门禁用的内存后端（不连云端，数据只在进程内）。 */
    public static ObjectStoreSession inMemory(ObjectStoreProvider provider) {
        ObjectStoreCredentials c = new ObjectStoreCredentials(
                provider, "", "", "demo-bucket", "", "", "", true);
        return new InMemoryObjectStoreSession(c);
    }

    /** 用给定 options 建内存后端（向导「试用」与 demo 连接用）。 */
    public static ObjectStoreSession inMemory(Map<String, String> options, String storedCredentials) {
        ObjectStoreCredentials c = ObjectStoreCredentials.of(() -> options, storedCredentials);
        return new InMemoryObjectStoreSession(c);
    }

    /**
     * 连接测试：成功返回 {@code null}，失败返回错误文本（同向导 Tester 口径）。
     *
     * <p>⭐ 两歩而不是一步：{@code listBuckets()} 对七牛 Kodo 是<b>本地诚实回显</b>
     * （能力声明 {@code supportsListBuckets=false}，只报配置里的那一个桶，不发请求），
     * 于是坏 AK/SK 也能「测试连接通过」。追加一次<b>不存在前缀</b>的真实列举当握手：
     * 四家都会签一次名并打一次网络，前缀命中 0 条所以不会拉全桶、不改任何数据，
     * 但凭据/端点/桶名里任何一处不对都会在这里暴露。
     */
    public static String test(Map<String, String> options, String storedCredentials) {
        try (ObjectStoreSession session = connect(options, storedCredentials)) {
            session.listBuckets();
            String bucket = options.getOrDefault(ObjectStoreCredentials.OPT_BUCKET, "");
            session.listObjects(bucket, "__aidb_connection_probe__/", null);
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return msg;
        }
    }
}
