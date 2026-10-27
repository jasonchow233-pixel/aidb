package io.agenticdb.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ⭐ P7：启动打点的口径与落盘行为。 */
class StartupTraceTest {

    @Test
    void marksAreOrderedAndFirstScreenIsRecordedOnlyOnce() {
        StartupTrace trace = new StartupTrace();
        trace.mark("shell.construct");
        trace.firstScreen();
        long first = trace.firstScreenMillis();
        trace.mark("after");
        trace.firstScreen();

        assertThat(first).as("首屏耗时必须是正数").isPositive();
        assertThat(trace.firstScreenMillis())
                .as("第二次 firstScreen 不能覆盖第一次（重入不是第二次启动）")
                .isEqualTo(first);
        assertThat(trace.marks()).extracting(StartupTrace.Mark::name)
                .containsExactly("trace.created", "shell.construct", "first-screen", "after");
        assertThat(trace.marks()).extracting(StartupTrace.Mark::elapsedMillis)
                .as("阶段耗时按发生顺序单调不减")
                .isSorted();
        assertThat(trace.marks()).allSatisfy(mark ->
                assertThat(mark.totalMillis())
                        .as("进程启动到该阶段的耗时不能小于打点内部耗时")
                        .isGreaterThanOrEqualTo(mark.elapsedMillis()));
        assertThat(trace.slow()).as("真实测试里首屏不应超过 2s").isFalse();
    }

    @Test
    void summaryCarriesFirstScreenAndEveryPhase() {
        StartupTrace trace = new StartupTrace();
        trace.mark("shell.chrome");
        trace.firstScreen();

        assertThat(trace.summary())
                .contains("首屏 " + trace.firstScreenMillis() + " ms")
                .contains("trace.created=")
                .contains("shell.chrome=")
                .contains("first-screen=")
                .contains("v" + AppVersion.VERSION);
    }

    @Test
    void summaryIsAppendedOncePerStartup(@TempDir Path directory) throws IOException {
        StartupTrace trace = new StartupTrace();
        trace.firstScreen();

        Path file = trace.writeSummary(directory);
        assertThat(file).isNotNull();
        assertThat(file.getFileName().toString()).isEqualTo("startup.log");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("首屏 ").contains("first-screen=");

        assertThat(trace.writeSummary(directory))
                .as("同一次启动只写一行（第二轮启动会新建 StartupTrace）")
                .isNull();
        assertThat(Files.readAllLines(file)).hasSize(1);
    }

    @Test
    void writeFailureNeverThrows(@TempDir Path directory) throws IOException {
        Path notADirectory = Files.createFile(directory.resolve("blocked"));
        StartupTrace trace = new StartupTrace();
        trace.firstScreen();

        assertThat(trace.writeSummary(notADirectory))
                .as("日志写不进去必须静默放弃，不能反过来影响启动")
                .isNull();
    }
}
