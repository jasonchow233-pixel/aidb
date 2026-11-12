package io.agenticdb.asset;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 连接类型的唯一判别轴（取代 {@link DialectKind} 作为档案判别的地位）。
 *
 * <p>⭐ 架构反转（EXT 规划 §0）：旧版只有 SQL 一种资产族，判别轴就是 {@code DialectKind}。
 * 现在资产族扩到三类 —— SQL（6 家）、Redis、对象存储（4 家 provider 共用一个 kind）。
 * 把判别轴提到 {@code AssetKind}，让 {@code DialectKind} 退居「AssetKind 的 SQL 子集」：
 * SQL 成员各自持有一个 {@code DialectKind} 并委派其全部行为，非 SQL 成员不带方言。
 *
 * <p><b>持久化兼容</b>：旧 {@code connections.json} 里 {@code kind} 是 {@code "MYSQL"} 这种
 * 枚举名，新代码用 {@link #id()}（小写 {@code "mysql"}）序列化，{@link #of(String)} 两种都认，
 * 旧档案自动映射，不会要求用户迁移。
 *
 * <p>加一种连接类型 = 在这里加一项 + 在对应的 {@link AssetFamily} 登记（见
 * {@link AssetFamilyRegistry}）。UI 不得出现资产族分支（{@code DialectBranchAuditTest} 的升级版）。
 */
public enum AssetKind {

    // ───────────────────────── SQL 族（委派 DialectKind） ─────────────────────────
    SQLITE("sqlite", "SQLite", DialectKind.SQLITE),
    POSTGRESQL("postgresql", "PostgreSQL", DialectKind.POSTGRESQL),
    MYSQL("mysql", "MySQL", DialectKind.MYSQL),
    MARIADB("mariadb", "MariaDB", DialectKind.MARIADB),
    SQLSERVER("sqlserver", "SQL Server", DialectKind.SQLSERVER),
    ORACLE("oracle", "Oracle", DialectKind.ORACLE),

    // ───────────────────────── 非 SQL 族 ─────────────────────────
    /** Redis 键值库。 */
    REDIS("redis", "Redis", null, true),
    /** 对象存储：S3 / 阿里云 OSS / 七牛 / 腾讯云 COS 共用此 kind，靠 provider 子判别。 */
    OBJECT_STORE("object_store", "Object Storage", null, false);

    private final String id;
    private final String displayName;
    /** SQL 成员持有对应方言；非 SQL 为 {@code null}。 */
    private final DialectKind dialectKind;
    /**
     * 非 SQL 成员自己回答「有没有主机 + 端口这一层」；SQL 成员为 {@code null} ——
     * 那一层由方言决定（SQLite 走文件路径）。
     */
    private final Boolean hostPort;

    AssetKind(String id, String displayName, DialectKind dialectKind) {
        this(id, displayName, dialectKind, null);
    }

    AssetKind(String id, String displayName, DialectKind dialectKind, Boolean hostPort) {
        this.id = id;
        this.displayName = displayName;
        this.dialectKind = dialectKind;
        this.hostPort = hostPort;
    }

    /** 持久化 id（小写，稳定）。序列化走它，旧档案的枚举名也认。 */
    @JsonValue
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 是不是 SQL 族（持有方言）。 */
    public boolean isSql() {
        return dialectKind != null;
    }

    public boolean isRedis() {
        return this == REDIS;
    }

    public boolean isObjectStore() {
        return this == OBJECT_STORE;
    }

    /** 反查 SQL 方言（非 SQL 抛）。 */
    public DialectKind dialectKind() {
        if (dialectKind == null) {
            throw new IllegalStateException(displayName + " is not a SQL asset and has no DialectKind");
        }
        return dialectKind;
    }

    /** 反查方言行为实现（非 SQL 抛）。 */
    public Dialect dialect() {
        return dialectKind().dialect();
    }

    /**
     * 这一族有没有「主机 + 端口」这一层。
     *
     * <p>SQL 族问方言（{@code usesFilePath()}：SQLite 走文件路径，没有主机与端口）；
     * 非 SQL 族自己声明 —— Redis 有 host/port，对象存储走 endpoint（域名），没有端口。
     *
     * <p>⚠️ 这一格存在的理由是一批**真实崩溃**：非 SQL 成员没有方言，而 UI 有几处
     * 直接调 {@code kind().dialect()}，档案列表里一旦有 Redis / 对象存储连接就会抛
     * {@code IllegalStateException}。判据收敛到注册表层之后，UI 不会再长出新的裸方言调用。
     */
    public boolean hasHostPort() {
        if (isSql()) {
            return !dialect().usesFilePath();
        }
        return hostPort != null && hostPort;
    }

    /**
     * 这一族要不要「切库就重连」（页签标题 {@code <对象> @<库> (<连接>)} 那种形态）。
     *
     * <p>SQL 族问方言；非 SQL 族一律 false —— Redis 的 db 下标是**另一个键浏览器实例**
     * （同一台服务器上的另一个库视图），不是「同一连接切库」；对象存储只有 bucket，同理。
     */
    public boolean switchesCatalogByReconnect() {
        return isSql() && dialect().switchesCatalogByReconnect();
    }

    // ───────────── 把 SQL 常用行为直接委派出来，调用点少一层 .dialectKind() ─────────────

    public boolean usesFilePath() {
        return isSql() && dialect().usesFilePath();
    }

    public int defaultPort() {
        if (isSql()) {
            return dialect().defaultPort();
        }
        if (isRedis()) {
            return 6379;
        }
        // 对象存储是 endpoint 语义，没有固定端口；给 443（HTTPS 默认）作占位。
        return 443;
    }

    public String jdbcUrl(String host, int port, String database) {
        return dialect().jdbcUrl(host, port, database);
    }

    public String quote(String identifier) {
        return dialect().quote(identifier);
    }

    public boolean bareResolvesTo(String name) {
        return isSql() && dialect().bareResolvesTo(name);
    }

    public boolean supportsColumnComment() {
        return isSql() && dialect().supportsColumnComment();
    }

    /** SQLite 文件 URL（仅 SQL + 文件型有意义）。 */
    public String sqliteUrl(Path file) {
        return "jdbc:sqlite:" + file.toAbsolutePath();
    }

    /**
     * 由持久化 id 或枚举名解析（大小写不敏感）。旧档案的 {@code "MYSQL"} 与新档案的
     * {@code "mysql"} 都映射到 {@link #MYSQL}；未知值抛。
     */
    @JsonCreator
    public static AssetKind of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("connection type must not be empty");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AssetKind kind : values()) {
            if (kind.id.equals(normalized) || kind.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown connection type: " + value);
    }

    /** 由 SQL 方言构造（迁移桥：旧 DialectKind 调用点可直接转）。 */
    public static AssetKind from(DialectKind dialectKind) {
        if (dialectKind == null) {
            throw new IllegalArgumentException("dialect must not be empty");
        }
        return of(dialectKind.id());
    }
}
