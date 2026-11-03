package io.agenticdb.safety;

import java.sql.SQLException;

/**
 * ⭐ G5a：SSH 跳板隧道自己失败了 —— 和「数据库连不上」是两件不同的事。
 *
 * <p>为什么不复用 {@code CONNECTION_LOST}：隧道断在前、数据库连在后，把隧道故障归进
 * 「连接断了，请检查网络与服务器进程」会让人去查<b>没坏的那一层</b>。D-32（证书失败被
 * 归成网络断）与 D-34（连不上被归成超时）都是同一类账：分类错了，建议就把人支开。
 *
 * <p>这个类型<b>在 adb-safety</b>（不在 adb-jdbc）的原因就在这里：
 * {@code ErrorClassifier} 要能用 {@code instanceof} 认它，而依赖方向是
 * adb-jdbc → adb-safety，反过来的类型它看不见。用类型而不是消息文本判，还有一层好处：
 * 隧道的报错文案是中文，随界面语言走，而 {@code contains(lower, …)} 那套探针认的是
 * 驱动吐出来的英文原文 —— 把自家文案塞进探针，等于再埋一颗「换语言就失效」的雷。
 *
 * @param untrustedHostKey true = 卡在<b>主机密钥</b>上（没见过 / 变了），
 *                         这种必须单独提醒：用户该确认的是密钥，不是网络
 */
public class SshTunnelException extends SQLException {

    private final boolean untrustedHostKey;

    public SshTunnelException(String message, boolean untrustedHostKey) {
        this(message, untrustedHostKey, null);
    }

    public SshTunnelException(String message, boolean untrustedHostKey, Throwable cause) {
        super(message, cause);
        this.untrustedHostKey = untrustedHostKey;
    }

    public boolean untrustedHostKey() {
        return untrustedHostKey;
    }
}
