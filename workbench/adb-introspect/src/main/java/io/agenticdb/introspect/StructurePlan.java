package io.agenticdb.introspect;

import io.agenticdb.model.Danger;

import java.util.List;

/**
 * 一次结构变更的完整计划。
 *
 * <p>UI 先把 {@link #sql()} 原样展示给用户，再执行；执行时整批语句在同一个
 * 事务里完成，任何一条失败都回滚。{@code approvalRequired} 表示删列、改类型、
 * 重建表这类高风险动作，需要第二道确认。
 */
public record StructurePlan(
        String title,
        List<String> statements,
        Danger danger,
        boolean approvalRequired,
        boolean foreignKeysOff) {

    public StructurePlan {
        title = title == null ? "结构变更" : title;
        statements = List.copyOf(statements == null ? List.of() : statements);
        danger = danger == null ? Danger.DESTRUCTIVE : danger;
    }

    public String sql() {
        return String.join(";\n\n", statements) + (statements.isEmpty() ? "" : ";");
    }

    public boolean empty() {
        return statements.isEmpty();
    }
}
