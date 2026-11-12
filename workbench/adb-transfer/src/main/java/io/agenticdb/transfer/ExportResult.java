package io.agenticdb.transfer;

/**
 * 一次导出的结果（D-86 新增返回值）。
 *
 * <p>⭐ 为什么要新增它：D-86 之前所有导出入口都返回 {@code long}（写出行数），
 * 而 {@code continueOnError} 要求「跳过坏行之后把错误数回报出去，不许静默吞掉」——
 * 一个 {@code long} 装不下第二个数字。但二十多处既有调用点（BackupDumper、
 * ResultExport、跨模块契约测试）都按 {@code long} 接返回值，改签名会把它们全打翻。
 * 于是保留那 6 个 {@code long} 入口，另开 {@code *WithResult} 入口返回本类型；
 * 旧入口只是 {@code .rows()} 的薄包装。导出向导用新入口把错误数写进日志。
 *
 * @param rows   成功写出的行数（被跳过的坏行不计）
 * @param errors 因渲染失败被跳过的行数（仅 {@code continueOnError} 开启时可能非 0）
 */
public record ExportResult(long rows, int errors) {

    public boolean hasErrors() {
        return errors > 0;
    }
}
