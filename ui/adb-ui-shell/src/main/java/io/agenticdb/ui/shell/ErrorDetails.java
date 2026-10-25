package io.agenticdb.ui.shell;

import io.agenticdb.safety.ErrorClassifier;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ P2-2：一次失败的**完整说明** —— 分类 / 原因 / 建议 / 驱动原文 / 异常类型 / SQL。
 *
 * <p>V-12 的 {@link ErrorClassifier} 已经能归类，但界面过去只把 {@code message()}
 * 塞进一个 {@code Alert}：用户看得到一句原因，却拿不走细节，也分不清
 * 「权限」和「语法」—— 处置动作完全不同。这里把归类结果整理成固定结构，
 * 正文与复制文本全部由纯函数产出，门禁能直接断言，不依赖弹窗。
 */
record ErrorDetails(String title, ErrorClassifier.Kind kind, String summary,
                    String advice, String detail, String exception, String sql) {

    ErrorDetails {
        title = blank(title) ? "错误详情" : title.trim();
        kind = kind == null ? ErrorClassifier.Kind.OTHER : kind;
        summary = clean(summary);
        advice = clean(advice);
        detail = clean(detail);
        exception = clean(exception);
        sql = clean(sql);
    }

    /** 从异常生成详情；{@code sql} 可为空（例如导出这类与语句无关的失败）。 */
    static ErrorDetails of(String title, Throwable error, String sql) {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(error);
        return new ErrorDetails(title, diagnosis.kind(), diagnosis.summary(),
                diagnosis.advice(), driverDetail(error, diagnosis.detail()),
                exceptionTypeOf(error), sql);
    }

    /** 分类的中文名 —— 弹窗上的徽标文案。 */
    String kindLabel() {
        return switch (kind) {
            case CONNECTION_LOST -> "连接断开";
            case PERMISSION_DENIED -> "权限不足";
            case NOT_FOUND -> "对象不存在";
            case TIMEOUT -> "超时";
            case LOCK_CONFLICT -> "锁冲突";
            case CONSTRAINT_VIOLATION -> "约束冲突";
            case SYNTAX -> "语法错误";
            case OTHER -> "其它";
        };
    }

    /**
     * 标题行：**先给能在标题栏一眼看懂的那句**。
     *
     * <p>分类名与说明重复时（例如「对象不存在」），把驱动原文顶上来 ——
     * 只写「对象不存在」，用户还得去正文里找是哪张表。
     */
    String headline() {
        if (summary.isEmpty() || summary.equals(kindLabel())) {
            String specific = headlineDetail();
            return specific.isEmpty() ? kindLabel() : kindLabel() + "：" + specific;
        }
        return kindLabel() + " · " + summary;
    }

    /** 正文：驱动原文 / 建议 / 异常类型 / SQL，缺哪段跳过哪段。 */
    String body() {
        List<String> sections = new ArrayList<>();
        boolean promotedToHeadline = detailInHeadline()
                && !detail.equals(summary) && !detail.contains("\n");
        if (!detail.isEmpty() && !detail.equals(summary) && !promotedToHeadline) {
            sections.add("驱动原文：\n" + detail);
        }
        if (!advice.isEmpty()) {
            sections.add("建议：\n" + advice);
        }
        if (!exception.isEmpty()) {
            sections.add("异常类型：\n" + exception);
        }
        if (!sql.isEmpty()) {
            sections.add("SQL：\n" + sql);
        }
        return String.join("\n\n", sections);
    }

    /** 一键复制的文本：标题 + 标题行 + 完整正文。 */
    String copyText() {
        String head = title + "：" + headline();
        String body = body();
        return body.isEmpty() ? head : head + "\n\n" + body;
    }

    private boolean detailInHeadline() {
        return (summary.isEmpty() || summary.equals(kindLabel())) && !detail.isEmpty();
    }

    /** 标题栏只能放一行：包装异常的根因在链尾，取链尾那一句最能说明问题。 */
    private String headlineDetail() {
        if (detail.isEmpty()) {
            return "";
        }
        String[] lines = detail.split("\n");
        String last = lines[lines.length - 1].trim();
        return last.length() <= 120 ? last : last.substring(0, 119) + "…";
    }

    /**
     * 驱动原文取**整条异常链**的消息。
     *
     * <p>{@link ErrorClassifier.Diagnosis#detail()} 只给最外层消息；
     * 包装异常的外层常常是「执行失败」，真正的原因（{@code no such table}）在 cause 里。
     * 归类用的是整条链，展示却只剩外层，用户就会拿到一句正确但没用的说明。
     */
    private static String driverDetail(Throwable error, String topLevel) {
        if (error == null) {
            return topLevel;
        }
        List<String> messages = new ArrayList<>();
        Throwable current = error;
        while (current != null && messages.size() < 5) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                String trimmed = message.trim();
                if (!messages.contains(trimmed)) {
                    messages.add(trimmed);
                }
            }
            current = current.getCause();
        }
        return messages.isEmpty() ? topLevel : String.join("\n", messages);
    }

    private static String exceptionTypeOf(Throwable error) {
        if (error == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        Throwable current = error;
        while (current != null && names.size() < 5) {
            String name = current.getClass().getName();
            if (!names.contains(name)) {
                names.add(name);
            }
            current = current.getCause();
        }
        return String.join(" ← ", names);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
