package io.agenticdb.session;

import io.agenticdb.jdbc.SshEndpoint;

/**
 * ⭐ G5a：档案里那台 SSH 跳板机的<b>非秘密</b>部分。
 *
 * <p>刻意<b>没有</b>口令与私钥口令短语这两个字段：档案会明文写进
 * {@code ~/.agenticdb/connections.json}，而 {@link ConnectionStore} 那条红线是
 * 「秘密只活在凭据库」。所以秘密按 {@link #sshPasswordKey(String)} /
 * {@link #sshKeyPassphraseKey(String)} 两个独立条目存在系统凭据库里，
 * 连的时候才由 {@link SshSecrets} 拼回 {@link SshEndpoint}。
 *
 * <p>为什么不复用 {@link SshEndpoint} 当档案字段：那个类型是给驱动那一层用的，
 * 带秘密字段。让持久层用它，等于「谁哪天把口令 set 进去再存盘，红线就破了」——
 * 而这种破法编译期看不出来。分成两个类型，「秘密进不了 JSON」就是<b>结构性的</b>。
 *
 * @param host 跳板机地址
 * @param port SSH 端口
 * @param username 跳板机用户
 * @param privateKeyPath 私钥文件路径（空 = 用口令认证）
 * @param trustOnFirstUse 首次见到该主机密钥时是否记录并接受（OpenSSH 的默认）
 */
public record SshConfig(String host, int port, String username, String privateKeyPath,
                        boolean trustOnFirstUse) {

    /** 不启用隧道的规范值：一个字段都不用填。 */
    public static final SshConfig NONE = new SshConfig("", SshEndpoint.DEFAULT_PORT, "", "", true);

    public SshConfig {
        host = host == null ? "" : host.trim();
        port = port <= 0 ? SshEndpoint.DEFAULT_PORT : port;
        username = username == null ? "" : username.trim();
        privateKeyPath = privateKeyPath == null ? "" : privateKeyPath.trim();
    }

    /** 地址与用户齐了才算启用 —— 半填的表单不该把连接拖进隧道分支。 */
    public boolean enabled() {
        return !host.isBlank() && !username.isBlank();
    }

    /**
     * 秘密从凭据库拼回来，交给驱动那一层。
     *
     * <p>缺凭据不在这里拦：缺了要连一次才知道，而「SSH 隧道缺少凭据（私钥或口令至少一样）」
     * 那句该由隧道自己说，不该在界面里多摆一个提前报错的红框。
     */
    public SshEndpoint endpoint(SshSecrets secrets) {
        if (!enabled()) {
            return null;
        }
        SshSecrets value = secrets == null ? SshSecrets.NONE : secrets;
        return new SshEndpoint(host, port, username, value.password(), privateKeyPath,
                value.keyPassphrase(), trustOnFirstUse);
    }

    /**
     * ⛔ 这两个键名是凭据库里的<b>条目名</b>，不是档案字段。
     *
     * <p>它们和 {@code profileId} 一起决定钥匙串里那两条秘密，改一次就得迁一次用户机器，
     * 所以只有「同时带上旧键的读取兜底」才允许动。
     */
    public static String sshPasswordKey(String profileId) {
        return profileId + "#ssh";
    }

    public static String sshKeyPassphraseKey(String profileId) {
        return profileId + "#sshkey";
    }

    @Override
    public String toString() {
        return "SshConfig[" + username + "@" + host + ":" + port + "]";
    }
}
