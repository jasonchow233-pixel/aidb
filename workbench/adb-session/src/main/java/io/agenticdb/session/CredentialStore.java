package io.agenticdb.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 口令存储抽象。默认实现走系统安全存储：Windows 凭据管理器（WinCred）、
 * macOS 钥匙串、Linux Secret Service；找不到系统服务时退回内存实现，
 * 并明确打印警告 —— 口令<b>绝不</b>落盘。
 */
public interface CredentialStore extends AutoCloseable {

    Optional<String> password(String profileId);

    void save(String profileId, String password);

    void delete(String profileId);

    static CredentialStore inMemory() {
        return new InMemoryCredentialStore();
    }

    static CredentialStore platform() {
        if (WindowsCredentialStore.supported()) {
            try {
                return new WindowsCredentialStore();
            } catch (LinkageError e) {
                System.err.println("[adb] Windows 凭据管理器不可用（" + e.getMessage()
                        + "），口令仅保存在当前进程内存中");
                return inMemory();
            }
        }
        if (isCommandAvailable("security")) {
            return new KeychainCredentialStore();
        }
        if (isCommandAvailable("secret-tool")) {
            return new SecretToolCredentialStore();
        }
        System.err.println("[adb] 未找到系统凭据服务，口令仅保存在当前进程内存中");
        return inMemory();
    }

    private static boolean isCommandAvailable(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String override = System.getProperty("adb." + command + ".path");
        if (override != null && !override.isBlank()) {
            return java.nio.file.Files.isExecutable(java.nio.file.Path.of(override));
        }
        if (command.equals("security") && !os.contains("mac")) {
            return false;
        }
        if (command.equals("secret-tool") && os.contains("mac")) {
            return false;
        }
        return java.util.Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
                .anyMatch(directory -> {
                    try {
                        return java.nio.file.Files.isExecutable(
                                java.nio.file.Path.of(directory, command));
                    } catch (RuntimeException e) {
                        return false;
                    }
                });
    }

    @Override
    default void close() {
    }

    final class InMemoryCredentialStore implements CredentialStore {
        private final Map<String, String> passwords = new ConcurrentHashMap<>();

        @Override
        public Optional<String> password(String profileId) {
            return Optional.ofNullable(passwords.get(profileId));
        }

        @Override
        public void save(String profileId, String password) {
            if (password == null || password.isBlank()) {
                passwords.remove(profileId);
            } else {
                passwords.put(profileId, password);
            }
        }

        @Override
        public void delete(String profileId) {
            passwords.remove(profileId);
        }
    }
}
