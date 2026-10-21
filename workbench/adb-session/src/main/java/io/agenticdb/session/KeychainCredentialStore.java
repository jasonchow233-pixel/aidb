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
        // ⭐ A13：口令走 <b>stdin</b>，不进 argv。
        //    `security add-generic-password -w <password>` 会把明文口令暴露在
        //    进程参数里 —— `ps` / 活动监视器一眼就能看到，那是明文密码泄露。
        //    `security` 支持从 stdin 读：`-w` 不带值时提示两次输入，
        //    这里用「`-w` + 空格 + 换行」的形式喂给它（等价于交互输入一次）。
        ProcessResult result = runWithInput(5, password,
                "add-generic-password", "-U", "-s", SERVICE, "-a", profileId, "-w");
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Keychain 写入失败: " + result.stderr().trim());
        }
    }

    @Override
    public void delete(String profileId) {
        run(3, "delete-generic-password", "-s", SERVICE, "-a", profileId);
    }

    private ProcessResult run(int timeoutSeconds, String... args) {
        return runWithInput(timeoutSeconds, null, args);
    }

    /**
     * 跑一条命令；{@code stdin} 非空时把它写进子进程的标准输入。
     *
     * <p>口令只能走这条路，绝不能出现在 argv 里。
     */
    private ProcessResult runWithInput(int timeoutSeconds, String stdin, String... args) {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(executable);
            command.addAll(java.util.Arrays.asList(args));
            Process process = new ProcessBuilder(command).start();
            if (stdin == null) {
                process.getOutputStream().close();
            } else {
                // 口令以「一行」的形式送出，末尾补换行 —— `security` 按行读。
                byte[] payload = (stdin + "\n").getBytes(StandardCharsets.UTF_8);
                process.getOutputStream().write(payload);
                process.getOutputStream().flush();
                process.getOutputStream().close();
            }
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
