package io.agenticdb.asset;

import java.util.List;

/**
 * SQL 资产族：把现有 6 家数据库的「连接类型」收敛进族策略。
 *
 * <p>这是 P0-2 的收敛动作 —— 旧 {@code ConnectionCatalog} 里那 6 行硬编码登记平移到这里，
 * 行为（向导步骤、树、标签页）仍由现有 SQL 代码路径处理，本族只负责「声明我有哪些类型与磁贴」。
 * 真正的向导/树/Tab 接线在后续阶段把 {@code ConnectionWizard} / {@code SideBar} /
 * {@code WorkspaceView} 改成读注册表时完成；本族先给出磁贴，让注册表立即可用。
 */
public final class SqlFamily implements AssetFamily {

    /** 单例：SQL 族不持有状态。 */
    public static final SqlFamily INSTANCE = new SqlFamily();

    private SqlFamily() {
    }

    @Override
    public List<AssetKind> kinds() {
        return List.of(
                AssetKind.SQLITE, AssetKind.POSTGRESQL, AssetKind.MYSQL,
                AssetKind.MARIADB, AssetKind.SQLSERVER, AssetKind.ORACLE);
    }

    @Override
    public List<TileDescriptor> tiles() {
        return List.of(
                tile(AssetKind.MYSQL, "MySQL", "adb-db--mysql", "wizard.tile.mysql", "mysql"),
                tile(AssetKind.POSTGRESQL, "PostgreSQL", "adb-db--postgresql",
                        "wizard.tile.postgresql", "postgresql"),
                tile(AssetKind.SQLSERVER, "SQL Server", "adb-db--sqlserver",
                        "wizard.tile.sqlserver", "sqlserver"),
                tile(AssetKind.ORACLE, "Oracle", "adb-db--oracle", "wizard.tile.oracle", "oracle"),
                tile(AssetKind.SQLITE, "SQLite", "adb-db--sqlite", "wizard.tile.sqlite", "sqlite"),
                tile(AssetKind.MARIADB, "MariaDB", "adb-db--mariadb",
                        "wizard.tile.mariadb", "mariadb"));
    }

    /** SQL 复用现有向导 7 页（与旧 {@code ConnectionWizard} 的 SQL 路径一致）。 */
    @Override
    public List<String> wizardStepIds() {
        return List.of("sql");
    }

    private static TileDescriptor tile(AssetKind kind, String name, String styleClass,
                                       String hintKey, String brandKey) {
        return new TileDescriptor(kind, name, styleClass, hintKey, brandKey, null);
    }
}
