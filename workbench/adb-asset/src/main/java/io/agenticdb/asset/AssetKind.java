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
    REDIS("redis", "Redis", null),
    /** 对象存储：S3 / 阿里云 OSS / 七牛 / 腾讯云 COS 共用此 kind，靠 provider 子判别。 */
    OBJECT_STORE("object_store", "Object Storage", null);

    private final String id;
    private final String displayName;
    /** SQL 成员持有对应方言；非 SQL 为 {@code null}。 */
    private final DialectKind dialectKind;

    AssetKind(String id, String displayName, DialectKind dialectKind) {
        this.id = id;
        this.displayName = displayName;
        this.dialectKind = dialectKind;
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
