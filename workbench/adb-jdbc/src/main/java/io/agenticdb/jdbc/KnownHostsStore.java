package io.agenticdb.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * ⭐ SSH 已知主机文件的<b>只读解析 + 单行删除</b>。
 *
 * <p>{@link SshTunnel} 的 {@code HostKeys} verifier 会在首次连接时把跳板机的公钥
 * 追加到 {@link SshTunnel#knownHostsFile()}；机房换过密钥之后，旧条目会让后续连接
 * 一律被拒。用户以前只能手工去 {@code ~/.agenticdb/known_hosts} 里删那一行，
 * 现在由界面列出条目、算指纹、按行删 —— <b>不改 verifier 本身</b>，
 * TOFU 与 strict 的判定逻辑、五套真库矩阵的门禁都原样不动。
 *
 * <p>格式按 OpenSSH known_hosts：{@code host[,ip] key-type base64-key [comment]}。
 * 无法解析 / 无法解码的行照样列出来（指纹显示 unavailable），不静默丢掉 ——
 * 静默丢掉会让「明明文件里有东西，界面却说没有」。
 *
 * <p>这里只认得文件，不认识 JavaFX 与 i18n；文案由 UI 层渲染。
 */
public final class KnownHostsStore {

    private static final String UNAVAILABLE = "unavailable";
    /** 指纹取摘要前 8 字节 = 16 位 hex，够肉眼比对，不冒充 OpenSSH 的 base64 显示格式。 */
    private static final int FINGERPRINT_BYTES = 8;

    private KnownHostsStore() {
    }

    /** 一行已知主机；{@code lineNumber} 是 1-based 的原始行号，删除时按它定位。 */
    public record Entry(int lineNumber, String host, String keyType,
                        String keyBase64, String fingerprint) {
    }

    /** 文件不存在 = 还没记录过任何主机密钥，返回空列表而不是抛异常。 */
    public static List<Entry> read(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String keyBase64 = parts.length >= 3 ? parts[2] : "";
            entries.add(new Entry(i + 1, parts[0], parts[1], keyBase64,
                    fingerprint(keyBase64)));
        }
        return List.copyOf(entries);
    }

    /**
     * 删掉指定原始行号那一行，其余内容（含注释与空行）原样保留。
     * 用 {@code Files.write} 覆盖已存在的文件，权限位不会被重建 —— known_hosts
     * 的 0600 是 MINA 的硬要求，重写时把权限弄松等于让隧道下次直接拒读。
     */
    public static void remove(Path file, int lineNumber) throws IOException {
        if (file == null || !Files.exists(file)) {
            return;
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        int index = lineNumber - 1;
        if (index < 0 || index >= lines.size()) {
            return;
        }
        lines.remove(index);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** 对公钥 blob 求 SHA-256，返回 {@code SHA256:} + 前 16 位十六进制。 */
    public static String fingerprint(String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            return UNAVAILABLE;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(keyBase64);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            StringBuilder hex = new StringBuilder(FINGERPRINT_BYTES * 2);
            for (int i = 0; i < Math.min(FINGERPRINT_BYTES, digest.length); i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return "SHA256:" + hex;
        } catch (IllegalArgumentException e) {
            return UNAVAILABLE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }
}
