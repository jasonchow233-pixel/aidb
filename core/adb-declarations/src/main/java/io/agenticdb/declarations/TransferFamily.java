package io.agenticdb.declarations;

import java.util.List;
import java.util.Map;

/**
 * 传输家族 —— 跨厂商兼容组。
 *
 * <p>达梦、人大金仓(O模式)、OceanBase(Oracle模式)、GBase8s、高斯、南大通用：
 * 它们的 DDL 语法、类型映射、标识符引用、IDENTITY 关键字几乎一样。
 * 每种写一份，就是 4 份重复代码 + 4 处不一致的 bug。
 *
 * <p><b>成本对比：</b>新增一个 Oracle 兼容国产库 =
 * 1 个 {@code connections/*.yaml}（4 行元信息）+ 往 {@code members} 加 1 个 id。
 * 传输规则零改动。
 */
public record TransferFamily(
        int schemaVersion,
        String id,
        String label,
        /** 属于本家族的连接 id，包括尚未实现驱动的（它们只是命名空间成员） */
        List<String> members,
        boolean supportsTransactionalDdl,
        String identityKeyword,
        String quoteIdent,
        Map<String, TypeRule> typeMapping) {

    /**
     * 一条类型映射规则。
     *
     * @param lossy 是否有损。UI 必须在传输计划里显式列出所有 lossy=true 的项，
     *              并要求用户勾选确认后才允许开始。
     * @param to    目标家族 id → 目标类型名
     */
    public record TypeRule(boolean lossy, Map<String, String> to) {
    }

    public boolean contains(String connectionId) {
        return members.contains(connectionId);
    }
}