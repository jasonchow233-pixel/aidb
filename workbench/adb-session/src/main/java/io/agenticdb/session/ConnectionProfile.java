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
                password == null || password.isBlank() ? null : password, options);
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
