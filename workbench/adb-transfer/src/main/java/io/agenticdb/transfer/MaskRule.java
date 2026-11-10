package io.agenticdb.transfer;

import java.util.Objects;

/**
 * 一条<b>列级</b>脱敏规则：哪一列、用哪种方式。
 *
 * <p>⭐ P-3（P0 默认走向「做，挂在导出向导，含列级掩码」）。规则只描述数据，
 * 不认识 JavaFX、不认识 i18n —— 枚举的本地化文案由 UI 层的
 * {@code StringConverter} 负责（领域层不许反向依赖 UI 包）。
 *
 * <p>没出现在规则列表里的列 = 原样导出。刻意没有 {@code NONE} 模式：
 * 「不脱敏」在 UI 上是「这一列没被勾选」，不是一条规则，否则规则列表里
 * 会混进一堆什么都不做的项，审计时看不出到底保护了哪几列。
 *
 * @param column 列名（与导出结果集的列标签精确匹配）
 * @param mode   掩码方式
 */
public record MaskRule(String column, MaskMode mode) {

    public MaskRule {
        Objects.requireNonNull(column, "column");
        mode = mode == null ? MaskMode.FULL : mode;
    }

    /** 掩码方式。 */
    public enum MaskMode {
        /** 整串换成固定星号，不泄露原值长度。 */
        FULL,
        /** 保留首字符与末字符，中间打码（手机号 / 卡号这类要留核对线索的列）。 */
        EDGES,
        /** SHA-256 前 16 位十六进制；同一原值稳定可比，但不可逆。 */
        HASH
    }
}
