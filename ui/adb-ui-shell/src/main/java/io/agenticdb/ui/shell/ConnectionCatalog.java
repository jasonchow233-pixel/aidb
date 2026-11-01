package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;

import java.util.List;

/**
 * 连接类型页的两份清单：{@code Connection Filter} 9 项、{@code Vendor Filter} 13 项。
 *
 * <p>清单按 Navicat 17 的类型页原样摆，<b>不按「我方支持几种」裁剪</b>（N27 拍板：
 * 磁贴全摆）。{@link Entry#kind()} 为 {@code null} 的项没有驱动 —— 点进去是
 * 如实说明页，不是一张填了也不生效的表单。
 *
 * <p>{@code styleClass} 只用来选磁贴的图标色（{@code .adb-db--*}），与「是否支持」无关。
 */
final class ConnectionCatalog {

    enum Group {
        /** 数据库类型（左栏 Connection Filter）。 */
        CONNECTION,
        /** 云厂商 / 国产库厂商（左栏 Vendor Filter）。 */
        VENDOR
    }

    record Entry(String id, String name, Group group, DialectKind kind, String styleClass,
                 String hintKey) {

        boolean supported() {
            return kind != null;
        }
    }

    private ConnectionCatalog() {
    }

    /** 顺序与 Navicat 17 类型页一致。 */
    static final List<Entry> CONNECTIONS = List.of(
            supported("MYSQL", "MySQL", DialectKind.MYSQL, "adb-db--mysql", "wizard.tile.mysql"),
            supported("POSTGRESQL", "PostgreSQL", DialectKind.POSTGRESQL, "adb-db--postgresql",
                    "wizard.tile.postgresql"),
            supported("SQLSERVER", "SQL Server", DialectKind.SQLSERVER, "adb-db--sqlserver",
                    "wizard.tile.sqlserver"),
            supported("ORACLE", "Oracle", DialectKind.ORACLE, "adb-db--oracle", "wizard.tile.oracle"),
            supported("SQLITE", "SQLite", DialectKind.SQLITE, "adb-db--sqlite", "wizard.tile.sqlite"),
            supported("MARIADB", "MariaDB", DialectKind.MARIADB, "adb-db--mariadb",
                    "wizard.tile.mariadb"),
            unsupported("MONGODB", "MongoDB", "adb-db--document", "wizard.tile.mongodb"),
            unsupported("REDIS", "Redis", "adb-db--redis", "wizard.tile.redis"),
            unsupported("SNOWFLAKE", "Snowflake", "adb-db--snowflake", "wizard.tile.snowflake"));

    static final List<Entry> VENDORS = List.of(
            vendor("ALIBABA", "Alibaba Cloud"),
            vendor("HUAWEI", "Huawei Cloud"),
            vendor("TENCENT", "Tencent Cloud"),
            vendor("DAMENG", "Dameng"),
            vendor("KINGBASE", "Kingbase"),
            vendor("PINGCAP", "PingCAP"),
            vendor("HIGHGO", "HighGo"),
            vendor("OCEANBASE", "OceanBase"),
            vendor("AWS", "AWS"),
            vendor("FUJITSU", "Fujitsu"),
            vendor("GOOGLE", "Google Cloud"),
            vendor("MICROSOFT", "Microsoft Azure"),
            vendor("ORACLE_CLOUD", "Oracle Cloud"));

    /** 全部条目（先连接类型、后厂商），门禁按这个顺序数总数。 */
    static List<Entry> all() {
        return java.util.stream.Stream.concat(CONNECTIONS.stream(), VENDORS.stream()).toList();
    }

    private static Entry supported(String id, String name, DialectKind kind, String styleClass,
                                   String hintKey) {
        return new Entry(id, name, Group.CONNECTION, kind, styleClass, hintKey);
    }

    private static Entry unsupported(String id, String name, String styleClass, String hintKey) {
        return new Entry(id, name, Group.CONNECTION, null, styleClass, hintKey);
    }

    private static Entry vendor(String id, String name) {
        return new Entry("VENDOR_" + id, name, Group.VENDOR, null, "adb-db--cloud",
                "wizard.tile.vendor");
    }
}
