package io.agenticdb.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Linux Secret Service（libsecret 的 secret-tool）。 */
public final class SecretToolCredentialStore implements CredentialStore {

    private static final String SERVICE = "io.agenticdb.connection";
    private final String executable;

    public SecretToolCredentialStore() {
        this(System.getProperty("adb.secret-tool.path", "secret-tool"));
    }

    public SecretToolCredentialStore(String executable) {
        this.executable = executable;
    }

    @Override
    public Optional<String> password(String profileId) {
        ProcessResult result = run(3, "lookup", "service", SERVICE, "account", profileId);
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
        try {
            Process process = new ProcessBuilder(executable, "store", "--label=AIDB",
                    "service", SERVICE, "account", profileId).start();
            process.getOutputStream().write(password.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            ProcessResult result = wait(process, 5);
            if (result.exitCode() != 0) {
                throw new IllegalStateException("Secret Service 写入失败: " + result.stderr().trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Secret Service 不可用: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String profileId) {
        run(3, "clear", "service", SERVICE, "account", profileId);
    }

    private ProcessResult run(int timeoutSeconds, String... args) {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(executable);
            command.addAll(java.util.Arrays.asList(args));
            Process process = new ProcessBuilder(command).start();
            return wait(process, timeoutSeconds);
        } catch (IOException e) {
            return new ProcessResult(1, "", e.getMessage() == null ? "io error" : e.getMessage());
        }
    }

    private static ProcessResult wait(Process process, int timeoutSeconds) throws IOException {
        try {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(1, "", "interrupted");
        }
    }

    private static Thread reader(java.io.InputStream input, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(target);
            } catch (IOException ignored) {
                // 输出读取失败按空输出处理。
            }
        }, "adb-secret-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
