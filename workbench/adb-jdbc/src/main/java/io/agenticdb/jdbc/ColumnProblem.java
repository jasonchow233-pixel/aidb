package io.agenticdb.jdbc;

/**
 * 列属性校验失败：携带 i18n 键与参数，由界面层渲染文案。
 *
 * <p>模型层不再写死中文 —— {@code I18nAuditTest.modelLayerChineseIsRatchetedDown}
 * 钉住这条边界：新增的可展示错误必须给「键 + 参数」，文案归 {@code messages_*.properties}。
 * 界面层用 {@link #key()} / {@link #args()} 取词，模型层只负责判定。
 */
public final class ColumnProblem extends IllegalArgumentException {

    private final String key;
    private final Object[] args;

    public ColumnProblem(String key, Object... args) {
        super(key);
        this.key = key;
        this.args = args.clone();
    }

    public String key() {
        return key;
    }

    public Object[] args() {
        return args.clone();
    }
}
