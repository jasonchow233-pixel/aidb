package io.agenticdb.jdbc;

/**
 * SSH 跳板机主机密钥的信任决策。
 *
 * <p>MINA 在<b>自己那条 NIO 工作线程</b>上调用 {@link #decide} —— 绝不会在 JavaFX 线程上，
 * 所以交互式实现可以安全地阻塞这条线程、把问题派发到 FX 线程去弹窗，等用户作答后再返回。
 *
 * <p>两条既定策略（非交互）由 {@link HostKeyDecisions#silentFor} 给出，矩阵测试与后台重连这类
 * 「没有真人能答」的调用保持旧行为：OpenSSH 默认（首次见到的密钥记并接收）或 strict（没记过就拒）。
 */
public interface HostKeyDecision {

    /** 用户对未知主机密钥的回答。 */
    enum Choice {
        /** 接受并把密钥写进 known_hosts（下次同主机不再问）。 */
        ACCEPT_AND_REMEMBER,
        /** 拒绝本次连接（不记录密钥）。 */
        REJECT
    }

    /** 未知主机密钥出现时询问；返回用户的选择。 */
    Choice decide(HostKeyPromptInfo info);

    /**
     * 弹窗要展示的、让用户肉眼比对的「这一把密钥是谁」。
     *
     * @param hostPort 跳板机地址（{@code 用户@主机:端口}）
     * @param keyType 密钥算法（如 {@code ssh-ed25519} / {@code ssh-rsa}）
     * @param fingerprintSha256 OpenSSH 格式的 SHA256 指纹（{@code SHA256:…}）
     */
    record HostKeyPromptInfo(String hostPort, String keyType, String fingerprintSha256) {
    }
}
