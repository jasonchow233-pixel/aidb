package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DialectKind;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 已保存连接的元数据。
 *
 * <p>⛔ 绝不包含口令。口令只存在系统钥匙串 / Secret Service；
 * 这个类可以被安全地序列化到 {@code ~/.agenticdb/connections.json}。
 */
public record ConnectionProfile(
        String id,
        String name,
        DialectKind kind,
        String host,
        int port,
        String database,
        String username,
        boolean favorite,
        long lastUsedAt,
        Map<String, String> options) {

    public ConnectionProfile {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        name = name == null || name.isBlank() ? kind.displayName() : name.trim();
        host = host == null ? "" : host;
        port = port <= 0 ? ConnectionProfile.defaultPort(kind) : port;
        database = database == null ? "" : database;
        username = username == null ? "" : username;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static ConnectionProfile newProfile(String name, DialectKind kind, String host,
                                               int port, String database, String username) {
        return new ConnectionProfile(UUID.randomUUID().toString(), name, kind, host, port,
                database, username, false, 0, Map.of());
    }

    public static ConnectionProfile sqlite(String id, String name, String path) {
        return new ConnectionProfile(id, name, DialectKind.SQLITE, "", 0, path, "", false, 0,
                Map.of());
    }

    public static int defaultPort(DialectKind kind) {
        return switch (kind) {
            case POSTGRESQL -> 5432;
            case MYSQL -> 3306;
            case SQLITE -> 0;
        };
    }

    public ConnectionProfile withFavorite(boolean value) {
        return new ConnectionProfile(id, name, kind, host, port, database, username, value,
                lastUsedAt, options);
    }

    public ConnectionProfile withLastUsed(long value) {
        return new ConnectionProfile(id, name, kind, host, port, database, username, favorite,
                value, options);
    }

    public ConnectionProfile withName(String value) {
        return new ConnectionProfile(id, value, kind, host, port, database, username, favorite,
                lastUsedAt, options);
    }

    public ConnectionSpec spec(String password) {
        return new ConnectionSpec(kind, host, port, database, username,
                password == null || password.isBlank() ? null : password,
                sanitizedOptions(options));
    }

    /**
     * 会话级敏感键：A14 / R4。
     *
     * <p>这些键<b>不允许</b>出现在 {@code options} 里：
     * <ul>
     *   <li>落盘时它们会明文进 {@code connections.json}；</li>
     *   <li>连接时它们会<b>覆盖</b>钥匙串里的口令（options 后于 password 应用）。</li>
     * </ul>
     * 敏感值只能走 {@link CredentialStore}。
     */
    private static final java.util.Set<String> SENSITIVE_OPTION_KEYS = java.util.Set.of(
            "password", "passwd", "pwd", "pass", "secret", "token", "apikey", "api_key",
            "access_token", "accesstoken", "private_key", "privatekey",
            "sslpassword", "sslkeypassword", "keystorepassword", "truststorepassword",
            "sslkey", "sslcertificate");

    /** 判断某个 options 键是不是敏感键（大小写不敏感）。 */
    public static boolean isSensitiveOption(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return SENSITIVE_OPTION_KEYS.stream()
                .map(value -> value.replace("-", "").replace("_", ""))
                .anyMatch(normalized::equals);
    }

    /**
     * 剔除敏感键后的 options（A14）。
     *
     * <p>落盘与建立连接都走这里 —— 敏感值一个都不许留下。
     */
    public static Map<String, String> sanitizedOptions(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!isSensitiveOption(key)) {
                safe.put(key, item);
            }
        });
        return Map.copyOf(safe);
    }

    public String displayTarget() {
        return switch (kind) {
            case SQLITE -> database;
            case POSTGRESQL, MYSQL -> host + ":" + port + "/" + database;
        };
    }

    public String kindLabel() {
        return kind.displayName().toUpperCase(Locale.ROOT);
    }

    public ConnectionProfile withPasswordlessOptions(Map<String, String> value) {
        return new ConnectionProfile(id, name, kind, host, port, database, username, favorite,
                lastUsedAt, value == null ? Map.of() : value);
    }

    /** 用于重复连接：新 id、新名称、不带使用记录。 */
    public ConnectionProfile duplicate() {
        return new ConnectionProfile(UUID.randomUUID().toString(), name + " 副本", kind, host,
                port, database, username, false, 0, options);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConnectionProfile other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + " (" + kindLabel() + ")";
    }
}
