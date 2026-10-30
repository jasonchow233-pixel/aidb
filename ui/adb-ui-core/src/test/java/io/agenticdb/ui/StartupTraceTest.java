package io.agenticdb.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
        // ⚠️ 这里**不**断言「首屏 < 2s」——2026-10-30 改判，理由记在 CHANGELOG：
        //   这个 `trace` 的起点是**当前 JVM 的进程启动时刻**，在共享的 surefire fork 里
        //   它必然把前面几十个测试类的时间算进来，于是「全量跑必红、单跑本类全绿」——
        //   那条断言测的是测试运行器的进程年龄，不是产品的启动性能。
        //   阈值本身改由下面那条**注入起点**的门禁钉住；真实冷启动耗时只在真启动时才有意义，
        //   量法是 `~/.agenticdb/logs/startup.log`（对外记的 3.5–3.9 s 就是从那儿读的）。
    }

    @Test
    void theTwoSecondBudgetIsEnforcedInBothDirections() {
        StartupTrace fast = new StartupTrace(Instant.now().minusMillis(500));
        fast.firstScreen();
        assertThat(fast.firstScreenMillis())
                .as("起点交给我们之后，首屏耗时应读作约 0.5s")
                .isBetween(400L, 1_500L);
        assertThat(fast.slow()).as("0.5s 的首屏不能判成慢启动").isFalse();

        StartupTrace slow = new StartupTrace(Instant.now().minusMillis(2_500));
        slow.firstScreen();
        assertThat(slow.slow())
                .as("越过 " + StartupTrace.SLOW_FIRST_SCREEN_MILLIS + " ms 必须判成慢启动（规格：冷启动 <2s）")
                .isTrue();
        assertThat(slow.summary())
                .as("判了慢就得在日志里喊出来，否则 slow() 是个没人看的布尔值")
                .contains("(SLOW, over " + StartupTrace.SLOW_FIRST_SCREEN_MILLIS + " ms");
        assertThat(fast.summary())
                .as("没越线就不能在日志里喊慢")
                .doesNotContain("SLOW");
    }

    @Test
    void summaryCarriesFirstScreenAndEveryPhase() {
        StartupTrace trace = new StartupTrace();
        trace.mark("shell.chrome");
        trace.firstScreen();

        assertThat(trace.summary())
                .contains("first paint " + trace.firstScreenMillis() + " ms")
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
        assertThat(lines.getFirst()).contains("first paint ").contains("first-screen=");

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
