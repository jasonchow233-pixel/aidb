package io.agenticdb.jdbc;

/**
 * ⭐ G5a：一台 SSH 跳板机。数据库连接先经它转发，再由驱动去连库。
 *
 * <p>认证两条腿，二选一：{@link #password()}（口令）或 {@link #privateKeyPath()}
 * （私钥文件，可带 {@link #passphrase()}）。两边都给时<b>私钥优先</b> ——
 * 私钥能签就不必把口令递出去，这与 ssh 自己的顺序一致，也少一次失败重试。
 *
 * <p>⛔ {@link #toString()} 不含口令与口令短语。这个对象会进日志、异常文本和
 * 「测试连接」的回报里，和 {@link ConnectionSpec} 同一条规矩。
 *
 * <p>{@link #trustOnFirstUse()} 是「首次见到这把主机密钥就记下」（OpenSSH 的默认行为），
 * 不是「不校验」：记完之后密钥<b>变了</b>照样拒绝。关掉它等于 strict host key checking，
 * 没记过的机器一律连不上（也不再弹窗询问）。开着时，连接会弹一张「未知密钥，是否接受」
 * 的指纹比对窗（{@code HostKeyPrompt}），由真人决定是否信任并记录 —— 不再默默接受任何密钥。
 *
 * @param host 跳板机地址
 * @param port SSH 端口；{@code <= 0} 归一成 22
 * @param username 跳板机用户
 * @param password 口令；null/空 = 不用口令
 * @param privateKeyPath 私钥文件路径；null/空 = 不用私钥
 * @param passphrase 私钥口令短语；null/空 = 私钥未加密
 * @param trustOnFirstUse 首次见到该主机密钥时是否记录并接受
 */
public record SshEndpoint(String host, int port, String username, String password,
                          String privateKeyPath, String passphrase, boolean trustOnFirstUse) {

    public static final int DEFAULT_PORT = 22;

    public SshEndpoint {
        host = host == null ? "" : host.trim();
        port = port <= 0 ? DEFAULT_PORT : port;
        username = username == null ? "" : username.trim();
        password = password == null || password.isEmpty() ? null : password;
        privateKeyPath = privateKeyPath == null ? "" : privateKeyPath.trim();
        passphrase = passphrase == null || passphrase.isEmpty() ? null : passphrase;
    }

    /** 地址与用户齐了才算「要用隧道」；半填的表单不该把连接拖进隧道分支。 */
    public boolean usable() {
        return !host.isBlank() && !username.isBlank();
    }

    /** 有没有可递出去的凭据（私钥或口令至少一样）。 */
    public boolean hasCredential() {
        return !privateKeyPath.isBlank() || password != null;
    }

    public boolean usesPrivateKey() {
        return !privateKeyPath.isBlank();
    }

    /** 只给界面/日志看的身份串：{@code 用户@主机:端口}，没有秘密。 */
    public String describe() {
        return username + "@" + host + ":" + port;
    }

    /** 换一份口令（私钥与主机不变）——给「凭据库读回来的那一刻」用。 */
    public SshEndpoint withPassword(String value) {
        return new SshEndpoint(host, port, username, value, privateKeyPath, passphrase,
                trustOnFirstUse);
    }

    @Override
    public String toString() {
        return "SshEndpoint[" + describe() + ", auth="
                + (usesPrivateKey() ? "key" : password != null ? "password" : "none") + "]";
    }
}
