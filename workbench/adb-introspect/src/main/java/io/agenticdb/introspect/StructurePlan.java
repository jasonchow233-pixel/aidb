package io.agenticdb.introspect;

import io.agenticdb.model.Danger;

import java.util.List;

/**
 * 一次结构变更的完整计划。
 *
 * <p>UI 先把 {@link #sql()} 原样展示给用户，再执行；执行时整批语句在同一个
 * 事务里完成，任何一条失败都回滚。{@code approvalRequired} 表示删列、改类型、
 * 重建表这类高风险动作，需要第二道确认。
 *
 * <p>⭐ R45 G3-a：标题不再是模型层拼好的中文句子，而是<b>i18n 键 + 参数</b>
 * （{@code I18nAuditTest.modelLayerChineseIsRatchetedDown} 钉住这条边界）。
 * 渲染归界面层（{@code StructurePlanRunner.title(plan)}），模型层只负责判定与取名 ——
 * 于是同一份计划在中英文界面里各自成句，而不会像 {@code title()} 时代那样
 * 顺着状态栏、确认框、toast 三个出口把中文漏进英文界面。
 */
public record StructurePlan(
        String titleKey,
        List<Object> titleArgs,
        List<String> statements,
        Danger danger,
        boolean approvalRequired,
        boolean foreignKeysOff) {

    /** 没有标题时的兜底键（不是兜底句子：兜底句子又会写死一种语言）。 */
    public static final String DEFAULT_TITLE_KEY = "plan.title.default";

    public StructurePlan {
        titleKey = titleKey == null || titleKey.isBlank() ? DEFAULT_TITLE_KEY : titleKey;
        titleArgs = List.copyOf(titleArgs == null ? List.of() : titleArgs);
        statements = List.copyOf(statements == null ? List.of() : statements);
        danger = danger == null ? Danger.DESTRUCTIVE : danger;
    }

    /** 无参标题的便捷构造。 */
    public StructurePlan(String titleKey, List<String> statements, Danger danger,
                         boolean approvalRequired, boolean foreignKeysOff) {
        this(titleKey, List.of(), statements, danger, approvalRequired, foreignKeysOff);
    }

    public String sql() {
        return String.join(";\n\n", statements) + (statements.isEmpty() ? "" : ";");
    }

    public boolean empty() {
        return statements.isEmpty();
    }
}
