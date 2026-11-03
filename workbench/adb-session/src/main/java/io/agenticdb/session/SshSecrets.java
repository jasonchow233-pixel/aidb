package io.agenticdb.session;

/**
 * ⭐ G5a：SSH 的两把秘密，只在内存里活一瞬。
 *
 * <p>它<b>不是</b> {@link ConnectionProfile} 的字段，也不写进
 * {@code connections.json}：档案那边只有 {@link SshConfig}，秘密各自存一条
 * 系统凭据库条目。这个类存在的唯一理由是「把两条秘密一起递过去」比
 * 递两个 String 参数更难写错顺序。
 *
 * <p>⛔ {@link #toString()} 不含秘密。
 *
 * @param password SSH 登录口令（私钥认证时可以为 null）
 * @param keyPassphrase 私钥文件的口令短语（私钥未加密时为 null）
 */
public record SshSecrets(String password, String keyPassphrase) {

    public static final SshSecrets NONE = new SshSecrets(null, null);

    public SshSecrets {
        password = password == null || password.isEmpty() ? null : password;
        keyPassphrase = keyPassphrase == null || keyPassphrase.isEmpty() ? null : keyPassphrase;
    }

    public boolean hasAny() {
        return password != null || keyPassphrase != null;
    }

    @Override
    public String toString() {
        return "SshSecrets[" + (password != null ? "password" : "-")
                + ", " + (keyPassphrase != null ? "keyPassphrase" : "-") + "]";
    }
}
