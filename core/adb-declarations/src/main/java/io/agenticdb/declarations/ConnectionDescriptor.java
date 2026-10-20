package io.agenticdb.declarations;

import java.util.List;

/**
 * 连接描述符 —— 一个 YAML 的完整内容。
 *
 * <p>{@code capabilities} 的 15 个布尔值<b>必须显式全写</b>，
 * 不许依赖缺省。理由写在 {@code sqlite.yaml} 里：
 * 「明确关闭」和「没写」是两回事，前者是作者的判断，后者是遗漏。
 * 加载器会检查这一点（规则 ⑮）。
 *
 * @param runtimeMode           native = 走内置方言适配器；jdbc = 走通用 JDBC 通道
 * @param transferObjectFamily  传输规则家族；达梦指向 oracle 就是复用 Oracle 家族的映射表
 */
public record ConnectionDescriptor(
        int schemaVersion,
        int order,
        String id,
        String rustVariant,
        String label,
        String icon,
        String dialect,
        String runtimeMode,
        String mcpMode,
        int defaultPort,
        String urlTemplate,
        String driverClass,
        DriverArtifact driverArtifact,
        Traits traits,
        Capabilities capabilities,
        List<String> compareModes,
        String transferSupportLevel,
        String transferObjectFamily,
        String supportLevel) {

    /** JDBC 驱动的元信息。只在 runtimeMode=jdbc 时存在。 */
    public record DriverArtifact(String key, String jar, String checksum, String license) {
    }

    /**
     * 引擎特性 —— 驱动协议不变，只有元数据查询和方言需要区分。
     *
     * <p>⭐ 这些字段直接驱动 UI 提示。<b>组件不许写
     * {@code if (db.equals("mysql"))}，一律查这里。</b>
     * 加一种数据库时，提示必须自动跟着声明走，而不是等改 UI 代码。
     */
    public record Traits(
            boolean schemaAware,
            boolean singleDatabase,
            boolean clearableQuerySchema,
            /** identity | autoIncrement | sequence | none */
            String identityKind,
            boolean sequenceObjects,
            /** MySQL / 达梦 为 false —— DDL 隐式提交，UI 必须警告 */
            boolean transactionalDdl,
            boolean onlineDdlConcurrent,
            boolean multipleResultsets,
            boolean savepoints,
            List<String> explainFormats,
            /** 0 = 无限制 */
            int maxParams,
            String identifierQuote,
            /** positional | numbered | named */
            String paramStyle) {
    }

    /**
         * 能力矩阵 —— 全部显式 true/false，共 15 个。
     *
     * <p>关掉某项时 UI 自动隐藏入口。用户加一种不支持某功能的数据库，
     * 只要把这里的开关改成 false，界面就自己变了。
     */
    public record Capabilities(
            boolean queryExecution,
            boolean metadataBrowse,
            boolean objectBrowser,
            boolean objectSource,
            boolean schemaSearch,
            boolean diagram,
            boolean tableDataEdit,
            boolean tableStructureEdit,
            boolean tableImport,
            boolean dataTransfer,
            boolean sqlFileExecution,
            boolean databaseCreate,
            boolean fieldLineage,
            boolean sqlExplain,
            boolean userAdmin,
            boolean specializedSurface) {

        /** 校验「capabilities 是否全写」时用它。要求 15 个键一个不缺。 */
        public static final List<String> ALL_KEYS = List.of(
                "queryExecution", "metadataBrowse", "objectBrowser", "objectSource",
                "schemaSearch", "diagram", "tableDataEdit", "tableStructureEdit",
                "tableImport", "dataTransfer", "sqlFileExecution", "databaseCreate",
                "fieldLineage", "sqlExplain", "userAdmin", "specializedSurface");
    }

    public boolean isJdbc() {
        return "jdbc".equals(runtimeMode);
    }

    /** JDBC 驱动必须能用 Class.forName 加载到。启动时一次性验证并给出可读提示。 */
    public boolean driverClassLoadable() {
        if (driverClass == null || driverClass.isBlank()) {
            return false;
        }
        try {
            Class.forName(driverClass);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}