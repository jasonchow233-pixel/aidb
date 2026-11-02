package io.agenticdb.ui.shell;

import io.agenticdb.safety.ErrorClassifier;
import io.agenticdb.ui.I18n;

/**
 * ⭐ R45 G3-b：错误<b>文案</b>的唯一渲染口 —— 模型层归类，界面层说话。
 *
 * <h2>为什么只能有这一个口</h2>
 * V-12 之后，{@code ErrorClassifier} 交回的是「一句说明 + 一个建议」，但它把中文
 * <b>写死在了模型层</b>：英文界面上状态栏会长出
 * {@code Object Not Found：查询超时 —— 缩小范围…} 这种半英半中的句子，
 * 连连接符「：」「——」都是中文的（D-9 家族里最大的一块，也是<b>最容易上屏</b>的一块：
 * 任何一次失败都会走它）。
 * 五个页面各自抄了一份一模一样的 {@code readable()}
 * （{@code StructurePage} / {@code QueryPage} / {@code DataGridPage} / {@code AppShell} / {@code TableDdl}），
 * 所以「换一处渲染口径」在过去等于「改五个地方，漏一个」。这里把它们收成一处：
 * 页面只剩 {@code ErrorText.readable(error)}，怎么拼、用什么标点，由界面层说了算。
 *
 * <p>详情弹窗（{@link ErrorDetails}）读的是同一份渲染口，
 * 于是「状态栏那一句」和「弹窗里那三行」不会各说各话。
 */
final class ErrorText {

    private ErrorText() {
    }

    /** 一句说明（已按当前语言渲染）。 */
    static String summary(ErrorClassifier.Diagnosis diagnosis) {
        return render(diagnosis.summaryKey());
    }

    /** 建议动作（已按当前语言渲染）；这一类没有可建议的动作时为空串。 */
    static String advice(ErrorClassifier.Diagnosis diagnosis) {
        return render(diagnosis.adviceKey());
    }

    /**
     * 状态栏那一行的唯一口径：<b>说明 + 驱动原文 + 建议</b>。
     *
     * <p>保留原文很重要 —— 归类给的是「方向」，原文给的是「细节」；
     * 只留归类说明，用户还得再点开弹窗才知道是哪张表。
     */
    static String readable(Throwable error) {
        return message(ErrorClassifier.classify(error));
    }

    static String message(ErrorClassifier.Diagnosis diagnosis) {
        String summary = summary(diagnosis);
        String detail = diagnosis.detail();
        String text = summary.isEmpty() || detail.isEmpty() || detail.equals(summary)
                ? summary
                : I18n.t("error.join.colon", summary, detail);
        String advice = advice(diagnosis);
        return advice.isEmpty() ? text : I18n.t("error.join.advice", text, advice);
    }

    /** 约束类错误的一条建议（{@code ErrorLocator.constraintAdviceKey} 的渲染口）。 */
    static String constraintAdvice(String driverMessage) {
        return render(io.agenticdb.safety.ErrorLocator.constraintAdviceKey(driverMessage));
    }

    /** 空键 = 这一类本来就没有话要说，不能渲染成 {@code ⚠} 或空串拼进句子。 */
    private static String render(String key) {
        return key == null || key.isBlank() ? "" : I18n.t(key);
    }
}
