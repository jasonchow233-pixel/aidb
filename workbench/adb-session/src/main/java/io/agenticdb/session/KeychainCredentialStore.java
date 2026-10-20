package io.agenticdb.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** macOS Keychain（security CLI）。口令不进配置文件，也不进日志。 */
public final class KeychainCredentialStore implements CredentialStore {

    private static final String SERVICE = "io.agenticdb.connection";
    private final String executable;

    public KeychainCredentialStore() {
        this(System.getProperty("adb.security.path", "security"));
    }

    public KeychainCredentialStore(String executable) {
        this.executable = executable;
    }

    @Override
    public Optional<String> password(String profileId) {
        ProcessResult result = run(3, "find-generic-password", "-s", SERVICE, "-a", profileId,
                "-w");
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String value = result.stdout().trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    @Override
    public void save(String profileId, String password) {
        if (password == null || password.isBlank()) {
            delete(profileId);
            return;
        }
        ProcessResult result = run(5, "add-generic-password", "-U", "-s", SERVICE, "-a",
                profileId, "-w", password);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Keychain 写入失败: " + result.stderr().trim());
        }
    }

    @Override
    public void delete(String profileId) {
        run(3, "delete-generic-password", "-s", SERVICE, "-a", profileId);
    }

    private ProcessResult run(int timeoutSeconds, String... args) {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(executable);
            command.addAll(java.util.Arrays.asList(args));
            Process process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread outReader = reader(process.getInputStream(), out);
            Thread errReader = reader(process.getErrorStream(), err);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(124, "", "timeout");
            }
            outReader.join(200);
            errReader.join(200);
            return new ProcessResult(process.exitValue(),
                    out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ProcessResult(1, "", e.getMessage() == null ? "io error" : e.getMessage());
        }
    }

    private static Thread reader(java.io.InputStream input, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(target);
            } catch (IOException ignored) {
                // 命令输出读取失败时按空输出处理。
            }
        }, "adb-keychain-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
