package io.agenticdb.model;

import java.util.Locale;

/**
 * SQL 风险等级 —— 安全基础。
 *
 * <p>决定「这条语句需不需要用户确认」「驱动层要不要再判一次」。
 * 完整的使用方式是<b>三层门禁</b>，任何一层都不能省：
 * <ol>
 *   <li>服务端分级 —— {@code DangerClassifier.classify(sql)}</li>
 *   <li>用户确认 —— UI 弹确认框，且把 {@link #label()} 原文给用户看</li>
 *   <li>驱动再判 —— {@code DbSession.execute(sql, danger)}，纵深防御</li>
 * </ol>
 */
public enum Danger {

    /** SELECT / PRAGMA / EXPLAIN / SHOW / WITH / VALUES / DESC */
    READ_ONLY("只读"),

    /** INSERT / UPDATE / REPLACE —— 改数据，但可回滚 */
    MUTATING("写数据"),

    /** DELETE / CREATE / ALTER / DROP / TRUNCATE —— 破坏性 */
    DESTRUCTIVE("改结构"),

    /** GRANT / REVOKE / ATTACH / DETACH */
    ADMIN("管理操作");

    private final String label;

    Danger(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否需要用户确认。唯一不需要的是 READ_ONLY。 */
    public boolean requiresConfirm() {
        return this != READ_ONLY;
    }

    /**
     * 是否需要「额外授权」—— 破坏性与管理操作走更重的审批流。
     * 与 {@link #requiresConfirm()} 分开，是为了 UI 能分两级提示。
     */
    public boolean requiresApproval() {
        return this == DESTRUCTIVE || this == ADMIN;
    }

    /** 取两等级中更危险的那个。用于多语句。 */
    public Danger worse(Danger other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }

    /** 供 UI 显示，不做本地化处理。 */
    @Override
    public String toString() {
        return name() + "(" + label.toLowerCase(Locale.ROOT) + ")";
    }
}