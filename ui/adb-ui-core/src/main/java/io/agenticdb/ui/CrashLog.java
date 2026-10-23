package io.agenticdb.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ⭐ P7：崩溃日志。
 *
 * <h2>为什么要有</h2>
 * 桌面应用最怕「用户说它闪退了」—— 而没有日志就只能猜。
 * 这里把**未捕获异常**落到 {@code ~/.agenticdb/logs/crash-*.log}，
 * 包含：时间、线程、JavaFX 线程、版本、操作系统、完整堆栈。
 *
 * <p>写日志**绝不能**再抛异常（否则会把原始崩溃掩盖掉）——
 * 所以全程 try/catch，失败就放弃写。
 */
public final class CrashLog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private CrashLog() {
    }

    /** 装上全局兜底处理器。应用启动时调一次。 */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            write(error);
            // 交给默认行为（打印到 stderr），别把原始信息吞掉。
            System.err.println("[AIDB] 未捕获异常（已写入崩溃日志）：" + error);
        });
    }

    /** 写一份崩溃日志，返回落盘路径（失败返回 null）。 */
    public static Path write(Throwable error) {
        try {
            Path directory = logDirectory();
            Files.createDirectories(directory);
            Path file = directory.resolve("crash-" + STAMP.format(LocalDateTime.now()) + ".log");
            StringBuilder text = new StringBuilder();
            text.append("AIDB 崩溃日志\n");
            text.append("时间: ").append(LocalDateTime.now()).append('\n');
            text.append("版本: ").append(AppVersion.VERSION).append('\n');
            text.append("Java : ").append(System.getProperty("java.version"))
                    .append(" (").append(System.getProperty("java.vendor")).append(")\n");
            text.append("系统 : ").append(System.getProperty("os.name")).append(' ')
                    .append(System.getProperty("os.version")).append('\n');
            text.append("线程: ").append(Thread.currentThread().getName()).append('\n');
            text.append("FX线程: ").append(javafx.application.Platform.isFxApplicationThread()).append('\n');
            text.append('\n');
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            text.append(stack);
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (Throwable ignored) {
            // 写日志失败**不能**影响崩溃处理本身。
            return null;
        }
    }

    /** 日志目录：{@code ~/.agenticdb/logs}。 */
    public static Path logDirectory() {
        return Path.of(System.getProperty("user.home"), ".agenticdb", "logs");
    }
}
