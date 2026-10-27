package io.agenticdb.jdbc;

/**
 * ⭐ R31：表设计器「类型」下拉里的一项。
 *
 * <p>类型以前是一整串自由文本 —— 用户得背 {@code VARCHAR(255)}、{@code DECIMAL(10,2)}，
 * 拼错要到执行才报。拆成「类型 + 长度 + 小数」三格之后，格子该不该能填由类型自己说：
 * {@code INTEGER} 不接受长度，{@code DECIMAL} 两项都要，{@code VARCHAR} 只要长度。
 *
 * <p>放在 {@code adb-jdbc} 而不是 UI：<b>哪些类型名在目标库上真的存在</b>
 * 只有方言说得清（PostgreSQL 没有 {@code TINYINT}，SQLite 没有 {@code JSONB}）。
 *
 * @param name     类型名（写进 DDL 的原样，如 {@code DOUBLE PRECISION}）
 * @param length   是否接受长度，如 {@code VARCHAR(255)} 的 255
 * @param decimals 是否接受小数位，如 {@code DECIMAL(10,2)} 的 2
 */
public record ColumnTypeOption(String name, boolean length, boolean decimals) {

    /** 既不要长度也不要小数位的类型。 */
    public static ColumnTypeOption plain(String name) {
        return new ColumnTypeOption(name, false, false);
    }

    /** 只要长度的类型（字符 / 二进制 / 枚举）。 */
    public static ColumnTypeOption sized(String name) {
        return new ColumnTypeOption(name, true, false);
    }

    /** 长度和小数位都要的类型（定点数）。 */
    public static ColumnTypeOption scaled(String name) {
        return new ColumnTypeOption(name, true, true);
    }
}
