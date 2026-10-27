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
 * <p>⛔ 绝不包含口令。口令只存在系统凭据库（WinCred / Keychain / Secret Service）；
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
        String group,
        Map<String, String> options) {

    public ConnectionProfile {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        name = name == null || name.isBlank() ? kind.displayName() : name.trim();
        host = host == null ? "" : host;
        port = port <= 0 ? ConnectionProfile.defaultPort(kind) : port;
        database = database == null ? "" : database;
        username = username == null ? "" : username;
        // ⭐ P5-1：空串 = 未分组。分组是展示层元数据，不参与连接参数。
        group = group == null ? "" : group.trim();
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static ConnectionProfile newProfile(String name, DialectKind kind, String host,
                                               int port, String database, String username) {
        return new ConnectionProfile(UUID.randomUUID().toString(), name, kind, host, port,
                database, username, false, 0, "", Map.of());
    }

    public static ConnectionProfile sqlite(String id, String name, String path) {
        return new ConnectionProfile(id, name, DialectKind.SQLITE, "", 0, path, "", false, 0, "",
                Map.of());
    }

    // ─────────────────── catalog 虚拟连接（U1）───────────────────

    /**
     * catalog 虚拟连接的 id 分隔符：{@code 父id@库名}。
     *
     * <p>⭐ 为什么切库要造一个**新 id** 而不是原地改 database：
     * <ul>
     *   <li>标签页按 {@code connectionId} 缓存页面与会话 —— 同一个 id 下
     *       两个库的「users」会撞进同一个标签页；</li>
     *   <li>{@code SessionManager.open} 按 id 复用会话 —— 原地改库名后
     *       复用回来的还是旧库的会话。</li>
     * </ul>
     * 带后缀的 id 让「切库 = 换连接」自然成立：每个库一条会话、
     * 一套标签，互不串味。虚拟连接**只活在内存里**，绝不落盘。
     */
    public static final String CATALOG_VARIANT_SEPARATOR = "@";

    /** 切库用的虚拟连接：同主机同账号，只换数据库。 */
    public ConnectionProfile catalogVariant(String catalog) {
        return new ConnectionProfile(catalogVariantId(id, catalog),
                name + " · " + catalog, kind, host, port, catalog, username,
                favorite, lastUsedAt, group, options);
    }

    public static String catalogVariantId(String parentId, String catalog) {
        return parentId + CATALOG_VARIANT_SEPARATOR + catalog;
    }

    /** 这个 id 是不是 catalog 虚拟连接（注意：只能用来兜底推断，先用注册表查）。 */
    public static boolean isCatalogVariantId(String id) {
        return id != null && id.contains(CATALOG_VARIANT_SEPARATOR);
    }

    public static String parentOfCatalogVariant(String variantId) {
        int at = variantId == null ? -1 : variantId.lastIndexOf(CATALOG_VARIANT_SEPARATOR);
        return at < 0 ? variantId : variantId.substring(0, at);
    }

    public static String catalogOfCatalogVariant(String variantId) {
        int at = variantId == null ? -1 : variantId.lastIndexOf(CATALOG_VARIANT_SEPARATOR);
        return at < 0 ? "" : variantId.substring(at + 1);
    }

    public static int defaultPort(DialectKind kind) {
        // ⭐ 端口由方言给 —— 以前这里是 switch，加一种库要改这里。
        return kind.dialect().defaultPort();
    }

    public ConnectionProfile withFavorite(boolean value) {
        return new ConnectionProfile(id, name, kind, host, port, database, username, value,
                lastUsedAt, group, options);
    }

    public ConnectionProfile withLastUsed(long value) {
        return new ConnectionProfile(id, name, kind, host, port, database, username, favorite,
                value, group, options);
    }

    public ConnectionProfile withName(String value) {
        return new ConnectionProfile(id, value, kind, host, port, database, username, favorite,
                lastUsedAt, group, options);
    }

    /** ⭐ P5-1：移到某个分组；空串 = 移出分组。连接 id / 会话都不变。 */
    public ConnectionProfile withGroup(String value) {
        return new ConnectionProfile(id, name, kind, host, port, database, username, favorite,
                lastUsedAt, value, options);
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
        return kind.dialect().usesFilePath()
                ? database
                : host + ":" + port + "/" + database;
    }

    /**
     * 类型标签（全大写）—— 用在**表格的类型列**与紧凑徽标里。
     *
     * <p>全大写是刻意的：它是一个「类别标签」，不是句子。
     */
    public String kindLabel() {
        return kind.displayName().toUpperCase(Locale.ROOT);
    }

    /**
     * 类型显示名（正常大小写）—— 用在**句子语境**里（应用栏胶囊、菜单项）。
     *
     * <p>「SQLITE」出现在胶囊里像是拼写错误；「SQLite」才是产品名。
     */
    public String kindDisplay() {
        return kind.displayName();
    }

    public ConnectionProfile withPasswordlessOptions(Map<String, String> value) {
        return new ConnectionProfile(id, name, kind, host, port, database, username, favorite,
                lastUsedAt, group, value == null ? Map.of() : value);
    }

    /** 用于重复连接：新 id、新名称、不带使用记录；留在同一分组。 */
    public ConnectionProfile duplicate() {
        return new ConnectionProfile(UUID.randomUUID().toString(), name + " 副本", kind, host,
                port, database, username, false, 0, group, options);
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
