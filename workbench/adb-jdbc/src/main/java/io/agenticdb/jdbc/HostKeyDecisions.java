package io.agenticdb.jdbc;

/**
 * 非交互场景的主机密钥决策兜底。
 *
 * <p>矩阵测试、批量导入、后台重连这类「没有真人能答」的调用，由这里按 OpenSSH 默认
 * （TOFU）或 strict 给出确定性答案 —— 行为与本类引入前完全一致，旧调用方无需改动。
 */
public final class HostKeyDecisions {

    private HostKeyDecisions() {
    }

    /**
     * 静默决策：{@code trustOnFirstUse=true} 记并接收未知密钥，否则拒绝。
     *
     * <p>这条刻意不弹窗：非交互调用若弹窗会卡死无人应答的线程。
     */
    public static HostKeyDecision silentFor(boolean trustOnFirstUse) {
        return info -> trustOnFirstUse
                ? HostKeyDecision.Choice.ACCEPT_AND_REMEMBER
                : HostKeyDecision.Choice.REJECT;
    }
}
