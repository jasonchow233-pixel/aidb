package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MetadataColumns} 门禁 —— 「这一格有没有值」的判定。
 *
 * <p>⭐ R37 N14 之后列恒在、缺值显示「—」，这条判定只用来区分
 * 「真值」与「占位符」（决定工具提示等说法的口径），
 * 不再承担「整列隐不隐藏」的职责。
 */
class MetadataColumnsTest {

    @Test
    void isPlaceholderMatchesWhatTheTableMetaRendersForMissingValues() {
        assertThat(MetadataColumns.isPlaceholder("—")).isTrue();
        assertThat(MetadataColumns.isPlaceholder(null)).isTrue();
        assertThat(MetadataColumns.isPlaceholder("")).isTrue();
        assertThat(MetadataColumns.isPlaceholder("   ")).isTrue();
        assertThat(MetadataColumns.isPlaceholder("InnoDB")).isFalse();
        // 「0」和「0 B」是真值 —— 空表也要显示 0 行、0 字节。
        assertThat(MetadataColumns.isPlaceholder("0")).isFalse();
        assertThat(MetadataColumns.isPlaceholder("0 B")).isFalse();
    }

    /** ⭐ T7：dash 的解释只说「引擎不提供」，不塞任何看起来像真的值。 */
    @Test
    void theUnavailableHintExplainsWithoutInventingData() {
        assertThat(MetadataColumns.UNAVAILABLE_HINT)
                .as("解释不能是空串（空串的 contains 恒真，门禁会空转）")
                .isNotBlank();
        assertThat(MetadataColumns.UNAVAILABLE_HINT)
                .as("解释里不许编出 0 / B 这类假数据")
                .doesNotContain("0", " B ");
    }
}
