package io.agenticdb.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ P7：启动阶段打点。
 *
 * <h2>为什么要有</h2>
 * 「冷启动 &lt; 2s」不能靠感觉。这里从**类初始化**开始记录各阶段，
 * 首屏就绪时把一行摘要追加到 {@code ~/.agenticdb/logs/startup.log}，
 * 同时给出「进程启动 → 首屏」的总耗时（含 JVM 启动，比只看代码内部阶段更诚实）。
 *
 * <p>与 {@link CrashLog} 同口径：<b>写日志绝不能再抛异常</b>，
 * 任何失败都静默放弃 —— 启动性能诊断不能反过来拖慢启动。
 */
public final class StartupTrace {

    /** 首屏超过这个值就标记为慢启动（规格：冷启动 &lt; 2s）。 */
    public static final long SLOW_FIRST_SCREEN_MILLIS = 2_000;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个阶段点；两个耗时都从「开始打点」算起，方便互相对照。 */
    public record Mark(String name, long elapsedMillis, long processMillis) {

        /** 进程启动到该阶段的总耗时；拿不到进程启动时间时返回 -1。 */
        public long totalMillis() {
            return processMillis >= 0 ? processMillis : elapsedMillis;
        }
    }

    private final long createdNanos = System.nanoTime();
    private final Instant processStart = startInstant();
    private final List<Mark> marks = new ArrayList<>();
    private long firstScreenMillis = -1;
    private boolean written;

    public StartupTrace() {
        mark("trace.created");
    }

    /** 记录一个阶段。名字用 `区域.事件` 的短横线形式，便于日志里 grep。 */
    public synchronized void mark(String name) {
        marks.add(new Mark(name, sinceCreated(), sinceProcessStart()));
    }

    /** 首屏就绪：只记第一次（窗口 `show()` 之后的重入不算第二次启动）。 */
    public synchronized void firstScreen() {
        if (firstScreenMillis >= 0) {
            return;
        }
        mark("first-screen");
        firstScreenMillis = marks.getLast().totalMillis();
    }

    /** 首屏耗时（进程启动 → 首屏）；还没到首屏时返回 -1。 */
    public synchronized long firstScreenMillis() {
        return firstScreenMillis;
    }

    /** 是否被判定为慢启动。 */
    public synchronized boolean slow() {
        return firstScreenMillis >= SLOW_FIRST_SCREEN_MILLIS;
    }

    public synchronized List<Mark> marks() {
        return List.copyOf(marks);
    }

    /** 一行摘要：首屏耗时 + 各阶段 + 环境。 */
    public synchronized String summary() {
        StringBuilder text = new StringBuilder();
        if (firstScreenMillis >= 0) {
            text.append("首屏 ").append(firstScreenMillis).append(" ms");
            if (slow()) {
                text.append(" ⚠️ 超过 ").append(SLOW_FIRST_SCREEN_MILLIS).append(" ms");
            }
        } else {
            text.append("首屏未就绪");
        }
        text.append(" | ");
        for (Mark mark : marks) {
            text.append(mark.name()).append('=').append(mark.elapsedMillis())
                    .append('/').append(mark.totalMillis()).append(' ');
        }
        text.append("| v").append(AppVersion.VERSION)
                .append(" | ").append(System.getProperty("os.name", ""))
                .append(' ').append(System.getProperty("os.version", ""));
        return text.toString();
    }

    /** 追加一行到 {@code ~/.agenticdb/logs/startup.log}；失败返回 null。 */
    public Path writeSummary() {
        return writeSummary(CrashLog.logDirectory());
    }

    /** 追加一行摘要到指定目录；只写一次，失败返回 null。 */
    public synchronized Path writeSummary(Path directory) {
        if (written) {
            return null;
        }
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("startup.log");
            String line = FILE_STAMP.format(LocalDateTime.now()) + " " + summary()
                    + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written = true;
            return file;
        } catch (IOException | RuntimeException ignored) {
            // 诊断日志失败不能影响启动。
            return null;
        }
    }

    private long sinceCreated() {
        return Duration.ofNanos(System.nanoTime() - createdNanos).toMillis();
    }

    private long sinceProcessStart() {
        if (processStart == null) {
            return -1;
        }
        long millis = Duration.between(processStart, Instant.now()).toMillis();
        return millis >= 0 ? millis : -1;
    }

    private static Instant startInstant() {
        try {
            return ProcessHandle.current().info().startInstant().orElse(null);
        } catch (RuntimeException ignored) {
            // 某些沙箱 / 容器里拿不到进程启动时间；退化为只看类内部耗时。
            return null;
        }
    }
}
