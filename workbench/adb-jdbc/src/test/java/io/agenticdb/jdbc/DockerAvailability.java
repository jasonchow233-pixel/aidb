package io.agenticdb.jdbc;

import org.testcontainers.DockerClientFactory;

/**
 * 容器运行时是否可用。
 *
 * <p>没有 Docker 的机器上，{@link PostgresMySqlIntegrationTest} 整个类跳过 ——
 * 「机器上没装 Docker」不该让构建失败，但「装了 Docker 却被跳过」也不行，
 * 所以这个判断要真的问一次 Docker。
 */
public final class DockerAvailability {

    private DockerAvailability() {
    }

    /** Docker 守护进程可用吗。 */
    public static boolean isAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            // Testcontainers 自己在没 Docker 时会抛各种异常 —— 一律当成「不可用」。
            return false;
        }
    }
}
