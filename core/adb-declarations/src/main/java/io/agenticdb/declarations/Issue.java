package io.agenticdb.declarations;

import java.util.List;

/**
 * 一条校验问题。
 *
 * <p>对应 Rust 侧 {@code build.rs::validate_semantics()} 里的 11 条编译期断言。
 * 区别在于：Rust 是<b>编译失败</b>，这里是<b>收集后统一抛</b>。
 * 之所以要收集而不是遇到第一条就抛：一次报全，改的人不用来回试。
 *
 * @param ruleId  规则编号，见 {@link Rule}
 * @param file    出问题的文件路径
 * @param message 人话描述
 */
public record Issue(Rule rule, String file, String message) {

    @Override
    public String toString() {
        return "[%s] %s —— %s".formatted(rule.id(), file, message);
    }

    /** 汇总成一段可直接打印的文本。 */
    public static String formatAll(List<Issue> issues) {
        if (issues.isEmpty()) {
            return "声明层校验通过";
        }
        StringBuilder sb = new StringBuilder()
                .append("声明层校验失败（").append(issues.size()).append(" 项）:\n");
        for (Issue i : issues) {
            sb.append("  · ").append(i).append('\n');
        }
        return sb.toString();
    }

    /**
     * 11 条语义校验规则。
     *
     * <p>编号与 Rust 侧注释里的编号一一对应，便于比对两侧实现。
     */
    public enum Rule {
        /** ① {@code schemaVersion} 必须为 1 */
        SCHEMA_VERSION("① schemaVersion 必须是 1"),
        /** ②③ {@code id} / {@code order} / {@code rustVariant} 不得重复 */
        DUPLICATE_FIELD("②③ id / order / rustVariant 重复"),
        /** ④ {@code dialect} 必须在 dialects/ 里存在 */
        UNKNOWN_DIALECT("④ dialect 未声明"),
        /** ⑤ {@code transferObjectFamily} 必须在 families 里存在 */
        UNKNOWN_FAMILY("⑤ transferObjectFamily 未声明"),
        /** ⑥ capabilities 全 false 且无 specializedSurface —— 说明作者没填 */
        CAPABILITIES_ALL_FALSE("⑥ capabilities 全为 false"),
        /** ⑦ runtimeMode=jdbc 必须有 driverClass */
        JDBC_WITHOUT_DRIVER("⑦ runtimeMode=jdbc 缺 driverClass"),
        /** ⑧ checksum 仍是占位符 */
        CHECKSUM_PLACEHOLDER("⑧ driverArtifact.checksum 仍是占位符"),
        /** ⑨ 语义自相矛盾：identityKind=sequence 但 sequenceObjects=false */
        IDENTITY_SEQUENCE_CONTRADICTION("⑨ identityKind 与 sequenceObjects 矛盾"),
        /** ⑩ 连接不在任何家族的 members 里 → 传输规划无从下手 */
        CONNECTION_IN_NO_FAMILY("⑩ 连接不属于任何传输家族"),
        /** ⑪ mcpMode=bridge 必须 runtimeMode=jdbc */
        MCP_BRIDGE_WITHOUT_JDBC("⑪ mcpMode=bridge 但 runtimeMode≠jdbc"),
        /** ⑫ explainFormats 为空 */
        EMPTY_EXPLAIN_FORMATS("⑫ explainFormats 为空"),
        /** ⑬ YAML 语法错误 */
        YAML_SYNTAX("⛔ YAML 语法错误"),
        /** ⑭ 不符合 connection.schema.json */
        SCHEMA_VIOLATION("⛔ 不符合 connection.schema.json"),
        /** ⑮ YAML 缺字段 */
        CAPABILITY_MISSING("⑮ capabilities 缺字段（必须显式写 true/false）");

        private final String text;

        Rule(String text) {
            this.text = text;
        }

        public String id() {
            return text;
        }
    }
}