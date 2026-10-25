package io.agenticdb.ui.shell;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ P2-2：批量操作的**汇总页** ——「做成了多少 / 哪些没成 / 为什么」。
 *
 * <p>批量删表与导入过去各写一套字符串：一行塞状态栏，一段塞 Alert。
 * 用户拿不走失败明细，也分不清「3 张里的 2 张」还是「30 张里的 2 张」。
 * 这里把口径固定成 total / succeeded / failed / failures，正文与复制文本纯函数产出。
 *
 * <p>⚠️ {@code failed} 必须单独传，不能拿 {@code failures.size()} 冒充 ——
 * 导入只保留前 50 条坏行，但实际可能失败了 5000 行。
 */
record BatchSummary(String title, String action, String unit, int total,
                    int succeeded, int failed, List<String> failures, List<String> notes) {

    BatchSummary {
        title = blank(title) ? "批量操作" : title.trim();
        action = blank(action) ? "完成" : action.trim();
        unit = blank(unit) ? "项" : unit.trim();
        total = Math.max(total, 0);
        succeeded = Math.max(succeeded, 0);
        failures = clean(failures);
        notes = clean(notes);
        // 明细行比失败数还多一定是调用方传错了；宁可让标题跟着明细走，也不要少报。
        failed = Math.max(Math.max(failed, 0), failures.size());
    }

    /** 失败明细就是全部失败项时的便捷构造。 */
    static BatchSummary of(String title, String action, String unit, int total,
                           int succeeded, List<String> failures) {
        return new BatchSummary(title, action, unit, total, succeeded,
                failures == null ? 0 : failures.size(), failures, List.of());
    }

    /** 失败数可能大于明细行数（明细被截断）时用这个。 */
    static BatchSummary of(String title, String action, String unit, int total,
                           int succeeded, int failed, List<String> failures,
                           List<String> notes) {
        return new BatchSummary(title, action, unit, total, succeeded, failed, failures, notes);
    }

    boolean allSucceeded() {
        return failed == 0;
    }

    /** 标题行：先给「做成了多少」，再给「几项失败」。 */
    String headline() {
        String done = "已" + action + " " + succeeded + "/" + total + " " + unit;
        return failed == 0 ? done + "，全部完成" : done + "，" + failed + " 项失败";
    }

    /** 正文：备注 → 失败明细；全成功时给一句结论。 */
    String body() {
        List<String> sections = new ArrayList<>();
        if (!notes.isEmpty()) {
            sections.add(String.join("\n", notes));
        }
        if (failures.isEmpty()) {
            sections.add(allSucceeded()
                    ? "全部完成。"
                    : "失败 " + failed + " 项，但没有可展示的明细。");
        } else {
            sections.add("失败明细：\n" + String.join("\n", failures));
        }
        return String.join("\n\n", sections);
    }

    /** 一键复制的文本：标题 + 标题行 + 完整正文。 */
    String copyText() {
        String head = title + "：" + headline();
        String body = body();
        return body.isEmpty() ? head : head + "\n\n" + body;
    }

    private static List<String> clean(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .filter(line -> line != null && !line.isBlank())
                .map(String::trim)
                .toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
