package io.agenticdb.objectstore;

import io.agenticdb.asset.ObjectStoreProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 对象存储连接凭据 + 非敏感连接参数。
 *
 * <p>⚠️ 密钥<b>绝不进</b> {@code ConnectionProfile.options()}：那份 map 会明文落盘到
 * {@code connections.json}（{@code sanitizedOptions} 的黑名单也挡不住自定义键名）。
 * 凭据统一走 {@code CredentialStore} —— 它每个 profile 只存<b>一个字符串</b>，
 * 所以这里把三段密钥编成一条可往返的文本；非敏感项（endpoint / region / bucket /
 * provider / pathStyle）仍放 options，向导与树都要读。
 *
 * <p>编码格式：{@code b64(accessKey).b64(secretKey).b64(token)}，点号分隔，
 * 空段用空串占位。Base64 URL 变体避免 {@code +} / {@code /} 在落盘/日志里被改写。
 */
public record ObjectStoreCredentials(
        ObjectStoreProvider provider,
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        String sessionToken,
        boolean pathStyle) {

    /** 选项键名约定：向导写入、会话工厂读取，单一处登记。 */
    public static final String OPT_PROVIDER = "os.provider";
    public static final String OPT_ENDPOINT = "os.endpoint";
    public static final String OPT_REGION = "os.region";
    public static final String OPT_BUCKET = "os.bucket";
    public static final String OPT_PATH_STYLE = "os.pathStyle";

    public ObjectStoreCredentials {
        endpoint = endpoint == null ? "" : endpoint.trim();
        region = region == null ? "" : region.trim();
        bucket = bucket == null ? "" : bucket.trim();
        accessKey = accessKey == null ? "" : accessKey.trim();
        secretKey = secretKey == null ? "" : secretKey.trim();
        sessionToken = sessionToken == null ? "" : sessionToken.trim();
    }

    /** 从 profile 的 options 读非敏感参数，凭据从 CredentialStore 那条字符串解出。 */
    public static ObjectStoreCredentials of(ConnectionOptionsLike profile, String stored) {
        Map<String, String> options = profile.options();
        String providerId = options.getOrDefault(OPT_PROVIDER, "s3");
        ObjectStoreProvider provider;
        try {
            provider = ObjectStoreProvider.of(providerId);
        } catch (IllegalArgumentException e) {
            provider = ObjectStoreProvider.S3;
        }
        String[] parts = decode(stored);
        return new ObjectStoreCredentials(
                provider,
                options.getOrDefault(OPT_ENDPOINT, ""),
                options.getOrDefault(OPT_REGION, ""),
                options.getOrDefault(OPT_BUCKET, ""),
                parts[0], parts[1], parts[2],
                Boolean.parseBoolean(options.getOrDefault(OPT_PATH_STYLE, "true")));
    }

    /** 只需要 {@code options()} 的结构化接口，避免 adb-objectstore 硬依赖 adb-session。 */
    public interface ConnectionOptionsLike {
        Map<String, String> options();
    }

    /** 编码三段密钥为 CredentialStore 可存的单串。 */
    public static String encode(String accessKey, String secretKey, String token) {
        return enc(accessKey) + "." + enc(secretKey) + "." + enc(token);
    }

    /** 解码；任何异常都回落成三段空串，绝不把异常抛到 UI 线程。 */
    public static String[] decode(String stored) {
        if (stored == null || stored.isBlank()) {
            return new String[]{"", "", ""};
        }
        String[] parts = stored.split("\\.", -1);
        String[] out = new String[]{"", "", ""};
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = new String(Base64.getUrlDecoder().decode(parts[i]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                out[i] = "";
            }
        }
        return out;
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /** 凭据是否齐备（缺 accessKey / secretKey 时会话工厂会给明确错误，不静默连空账号）。 */
    public boolean complete() {
        return !accessKey.isBlank() && !secretKey.isBlank();
    }
}
