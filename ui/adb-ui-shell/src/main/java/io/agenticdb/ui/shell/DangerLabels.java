package io.agenticdb.ui.shell;

import io.agenticdb.model.Danger;
import io.agenticdb.ui.I18n;

/**
 * SQL 风险等级的**界面文案**。
 *
 * <p>为什么不直接用 {@link Danger#label()}：那个中文标签长在 {@code core/adb-model} 里，
 * 而棘轮门禁只扫 {@code ui/} —— 于是默认英文的界面上会钉着一颗「只读」，
 * 门禁一条都不红（2026-10-29 第六批抽查询页时撞上的）。
 * 模型层管语义（要不要确认、要不要审批），界面层管文案，两边不互相借道。
 */
final class DangerLabels {

    private DangerLabels() {
    }

    static String t(Danger danger) {
        return switch (danger) {
            case READ_ONLY -> I18n.t("status.readOnly");
            case MUTATING -> I18n.t("danger.mutating");
            case DESTRUCTIVE -> I18n.t("danger.destructive");
            case ADMIN -> I18n.t("danger.admin");
        };
    }
}
