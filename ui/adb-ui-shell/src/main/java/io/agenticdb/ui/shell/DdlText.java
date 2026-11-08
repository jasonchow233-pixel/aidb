package io.agenticdb.ui.shell;

import java.util.regex.Pattern;

/**
 * 「这段执行文本里有没有 DDL」的唯一判定 —— 补全缓存（S-3）与 describe 缓存（H3-d）
 * 共用同一份形状，不许各抄一份正则慢慢跑偏。
 *
 * <p>挂在两处：{@code QueryCompletions.onStatementsExecuted}（作废补全的模型缓存）
 * 和 {@code QueryPage.apply} / {@code StructurePlanRunner.execute}
 * （作废 {@code SchemaService} 的 describe 缓存）。判定从宽 —— 词面上命中就作废，
 * 宁可白作废一次（下一次内省多打一遍库）也不许漏作废（旧结构活过 DDL）。
 */
final class DdlText {

    private static final Pattern DDL = Pattern.compile(
            "(?i)\\b(CREATE|ALTER|DROP|TRUNCATE|RENAME)\\b");

    private DdlText() {
    }

    static boolean isDdl(String text) {
        return text != null && DDL.matcher(text).find();
    }
}
