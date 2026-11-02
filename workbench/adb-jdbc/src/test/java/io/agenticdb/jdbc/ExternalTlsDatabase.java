package io.agenticdb.jdbc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⭐ R47：TLS 对端层的连接参数。
 *
 * <p>与 {@link ExternalDatabase} 分成两个类的理由不是洁癖：主矩阵那五家走的是明文，
 * 每条契约要多带两样 TLS 才有的东西 —— <b>信任材料</b>（CA 文件）和
 * <b>连的地址在不在证书里</b>。塞进同一个 record，主矩阵那 45 例会凭空多出三个
 * 它们永远不读的字段，而「label 里不许有口令」这条门禁要在两个地方各写一遍。
 *
 * <p>口令与证书路径同样<b>只从环境变量读</b>：仓库里既没有口令，也没有证书内容。
 *
 * <pre>
 * ADB_TEST_MYSQL_TLS_{HOST,PORT,DB,USER,PASSWORD}      （MARIADB、PG 前缀同理）
 * ADB_TEST_TLS_CA            自签 CA 的 PEM 路径 —— 就是签发服务器证书的那一把
 * ADB_TEST_TLS_ROGUE_CA      另一把没签过服务器证书的 CA，用来验「链认不出」真的会失败
 * ADB_TEST_TLS_HOST_IN_CERT  服务器证书 SAN 里写的那个名字（本机脚本 = localhost）
 * </pre>
 *
 * <p>本机一键：{@code scripts/dbmatrix.sh tls-up}，
 * 再 {@code eval "$(scripts/dbmatrix.sh env)"}（TLS 的键写在同一个口令文件里）。
 *
 * <p>⚠️ {@link #hostInCert()} 是这套 PKI 的关键设计：证书只写了 {@code DNS:localhost}，
 * 而容器映射在 127.0.0.1 上 —— 同一台服务器因此<b>天然</b>给出两个地址：
 * 一个在证书里（校验主机名应该通过）、一个不在（应该失败）。
 * 不需要改 {@code /etc/hosts}，也不需要第二个容器。
 */
public record ExternalTlsDatabase(
        DialectKind kind,
        String host,
        int port,
        String database,
        String user,
        String password,
        String caPem,
        String rogueCaPem,
        String hostInCert) {

    public ExternalTlsDatabase {
        host = host == null ? "" : host.trim();
        database = database == null ? "" : database.trim();
        user = user == null ? "" : user.trim();
        caPem = caPem == null ? "" : caPem.trim();
        rogueCaPem = rogueCaPem == null ? "" : rogueCaPem.trim();
        hostInCert = hostInCert == null || hostInCert.isBlank() ? "localhost" : hostInCert.trim();
    }

    /** MySQL 的 TLS 对端配了吗？（{@code scripts/dbmatrix.sh tls-up}） */
    public static ExternalTlsDatabase mysql() {
        return read("MYSQL", DialectKind.MYSQL, 33071);
    }

    /** MariaDB 的 TLS 对端配了吗？ */
    public static ExternalTlsDatabase mariadb() {
        return read("MARIADB", DialectKind.MARIADB, 33072);
    }

    /** PostgreSQL 的 TLS 对端配了吗？ */
    public static ExternalTlsDatabase postgres() {
        return read("PG", DialectKind.POSTGRESQL, 54331);
    }

    private static ExternalTlsDatabase read(String prefix, DialectKind kind, int defaultPort) {
        String host = System.getenv("ADB_TEST_" + prefix + "_TLS_HOST");
        if (host == null || host.isBlank()) {
            return null;
        }
        return new ExternalTlsDatabase(kind, host,
                integer(System.getenv("ADB_TEST_" + prefix + "_TLS_PORT"), defaultPort),
                System.getenv("ADB_TEST_" + prefix + "_TLS_DB"),
                System.getenv("ADB_TEST_" + prefix + "_TLS_USER"),
                System.getenv("ADB_TEST_" + prefix + "_TLS_PASSWORD"),
                System.getenv("ADB_TEST_TLS_CA"),
                System.getenv("ADB_TEST_TLS_ROGUE_CA"),
                System.getenv("ADB_TEST_TLS_HOST_IN_CERT"));
    }

    private static int integer(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 换个地址连、别的一律不动：主机名校验的两条契约就靠这一件事区分成败。 */
    public ExternalTlsDatabase withHost(String otherHost) {
        return new ExternalTlsDatabase(kind, otherHost, port, database, user, password,
                caPem, rogueCaPem, hostInCert);
    }

    /**
     * 拼出「档位 + 信任材料」的连接档案。
     *
     * <p>档位到驱动参数的翻译<b>用方言自己的 {@link Dialect#sslOptions}</b> ——
     * 这条矩阵验的就是产品里那条路（连接向导 → options → {@link JdbcSession#open}），
     * 不是在测试里另写一套 SQL 文本。超时和主矩阵同一档（外部对端卡住不该让测试干等）。
     */
    public ConnectionSpec spec(Dialect.SslLevel level, Map<String, String> trustAnchor) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("connectTimeout", "8000");
        options.put("socketTimeout", "15000");
        options.putAll(kind.dialect().sslOptions(level));
        options.putAll(trustAnchor);
        return ConnectionSpec.server(kind, host, port, database, user, password, options);
    }

    /** 日志里用的名字（<b>不含口令</b>，也不含证书内容）。 */
    public String label() {
        return kind.displayName() + " TLS " + host + ":" + port + "/" + database;
    }

    @Override
    public String toString() {
        return label();
    }
}
