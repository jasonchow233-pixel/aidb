package io.agenticdb.asset;

import java.util.List;

/**
 * 资产族策略接口。
 *
 * <p>整个连接体系（向导磁贴、向导步骤、树、标签页、后端会话）都通过本接口向资产族提问，
 * UI 层<b>不得</b>出现资产族分支（同 {@code DialectBranchAuditTest} 的升级承诺：
 * 「加一种连接类型 = 在注册表登记一个族」）。
 *
 * <p>接口刻意只返回纯数据（{@link TileDescriptor} / 步骤 id 字符串），不引入任何 JavaFX 或
 * 后端会话类型 —— 这样族定义可以放在无 UI 依赖的 {@code adb-asset} 里，UI 再按数据去拼页面。
 */
public interface AssetFamily {

    /** 这个族拥有哪些 {@link AssetKind}（SQL 族拥有 6 个；Redis / 对象存储各 1 个）。 */
    List<AssetKind> kinds();

    /** 连接向导磁贴（顺序即展示顺序）。 */
    List<TileDescriptor> tiles();

    /**
     * 连接向导的逻辑步骤 id（SQL 复用现有页；非 SQL 在 P1 / P2 落地具体步骤）。
     * 空列表表示该族<b>尚未开放连接</b> —— 向导应给明确提示，不造假控件。
     */
    default List<String> wizardStepIds() {
        return List.of();
    }

    /** 这个族当前是否已能建立连接（{@link #wizardStepIds()} 非空即视为可连）。 */
    default boolean connectable() {
        return !wizardStepIds().isEmpty();
    }
}
