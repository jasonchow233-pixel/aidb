package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ Data Profiling 统计内核的门禁。
 *
 * <p>画像页会算错的地方不是「按钮没反应」，而是<b>数字算错却不报错</b> ——
 * 给用户一个看起来很专业的错数字比报错更难发现。所以这里逐条钉住口径：
 * null 率、去重数、数值聚合、样例取法、以及「混进非数」时怎么办。
 */
class ProfilingStatsTest {

    private static final List<String> HEADERS = List.of("id", "email", "score");
    private static final List<String> TYPES = List.of("INTEGER", "VARCHAR", "INTEGER");

    private static boolean[] flags(boolean id, boolean email, boolean score) {
        return new boolean[]{id, email, score};
    }

    private static List<ProfilingStats.Column> summarize(List<List<String>> rows,
                                                         boolean[] numeric) {
        return ProfilingStats.summarize(HEADERS, TYPES, numeric, rows);
    }

    @Test
    void nullRateAndDistinctAreCountedPerColumn() {
        List<ProfilingStats.Column> stats = summarize(Arrays.asList(
                Arrays.asList("1", "a@x", "10"),
                Arrays.asList("2", "a@x", "20"),
                Arrays.asList("3", null, null)),
                flags(true, false, true));

        ProfilingStats.Column id = stats.get(0);
        assertThat(id.total()).isEqualTo(3);
        assertThat(id.nulls()).isZero();
        assertThat(id.nullPercent()).isZero();
        assertThat(id.distinct()).isEqualTo(3);

        ProfilingStats.Column email = stats.get(1);
        assertThat(email.total()).isEqualTo(3);
        assertThat(email.nulls()).isEqualTo(1);
        assertThat(email.nullPercent()).isCloseTo(33.333, within(0.01));
        assertThat(email.distinct()).as("去重不看大小写以外的东西，原样比")
                .isEqualTo(1);

        ProfilingStats.Column score = stats.get(2);
        assertThat(score.nulls()).isEqualTo(1);
        assertThat(score.nullPercent()).isCloseTo(33.333, within(0.01));
        assertThat(score.distinct()).isEqualTo(2);
    }

    @Test
    void numericColumnsGetMinMaxAndAverageOverNonNullValues() {
        List<ProfilingStats.Column> stats = summarize(List.of(
                List.of("1", "a@x", "10"),
                List.of("2", "b@x", "20"),
                List.of("3", "c@x", "30")),
                flags(true, false, true));

        ProfilingStats.Column score = stats.get(2);
        assertThat(score.min()).isEqualTo(10.0);
        assertThat(score.max()).isEqualTo(30.0);
        assertThat(score.average()).isEqualTo(20.0);
        assertThat(score.hasNumericSummary()).isTrue();
    }

    /**
     * ⭐ 关键口径：数值列里混进非数时，<b>整列的数值聚合作废</b>，
     * 而不是把那一行当 0 继续算 —— 否则均值会被悄悄拉低，用户看不出来。
     */
    @Test
    void aNonNumericValueInANumericColumnInvalidatesTheAggregate() {
        List<ProfilingStats.Column> stats = summarize(List.of(
                List.of("1", "a@x", "10"),
                List.of("2", "b@x", "not-a-number"),
                List.of("3", "c@x", "30")),
                flags(true, false, true));

        ProfilingStats.Column score = stats.get(2);
        assertThat(score.hasNumericSummary())
                .as("混进非数后不许再给出一个看似精确的均值")
                .isFalse();
        assertThat(score.average()).isNull();
        assertThat(score.min()).isNull();
        assertThat(score.max()).isNull();
        // 去重与样例仍然可用 —— 它们不依赖数值解析。
        assertThat(score.distinct()).isEqualTo(3);
        assertThat(score.samples()).containsExactly("10", "not-a-number", "30");
    }

    @Test
    void aColumnOfNothingButNullsHasNoNumericSummary() {
        List<ProfilingStats.Column> stats = summarize(Arrays.asList(
                Arrays.asList("1", null, null),
                Arrays.asList("2", null, null)),
                flags(true, false, true));

        ProfilingStats.Column score = stats.get(2);
        assertThat(score.total()).isEqualTo(2);
        assertThat(score.nulls()).isEqualTo(2);
        assertThat(score.nullPercent()).isEqualTo(100.0);
        assertThat(score.distinct()).isZero();
        assertThat(score.hasNumericSummary()).isFalse();
    }

    @Test
    void nonNumericColumnsNeverGetMinMaxOrAverage() {
        List<ProfilingStats.Column> stats = summarize(List.of(
                List.of("1", "10", "10"),
                List.of("2", "20", "20")),
                flags(true, false, true));

        ProfilingStats.Column email = stats.get(1);
        assertThat(email.hasNumericSummary())
                .as("文本列不许被当成数值列聚合")
                .isFalse();
        assertThat(email.min()).isNull();
        assertThat(email.average()).isNull();
    }

    @Test
    void samplesKeepFirstSeenOrderAndAreCappedAtFive() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            rows.add(Arrays.asList(String.valueOf(i), "v" + i, null));
        }
        List<ProfilingStats.Column> stats = summarize(rows, flags(true, false, true));

        assertThat(stats.get(1).samples())
                .as("样例按首次出现顺序，最多 5 个")
                .containsExactly("v1", "v2", "v3", "v4", "v5");
    }

    @Test
    void emptyInputProducesZeroedColumnsRatherThanNothing() {
        List<ProfilingStats.Column> stats = summarize(List.of(), flags(true, false, true));

        assertThat(stats).hasSize(3);
        assertThat(stats.get(0).total()).isZero();
        assertThat(stats.get(0).nullPercent()).isZero();
        assertThat(stats.get(0).distinct()).isZero();
        assertThat(stats.get(0).samples()).isEmpty();
    }

    @Test
    void theDisplayFormatMatchesTheColumnVocabulary() {
        assertThat(ProfilingStats.cell(null)).isEqualTo("—");
        assertThat(ProfilingStats.cell(12.5)).isEqualTo("12.5");
        assertThat(ProfilingStats.nullPercentText(33.333)).isEqualTo("33.3%");
        assertThat(ProfilingStats.nullPercentText(0)).isEqualTo("0.0%");
        assertThat(ProfilingStats.nullPercentText(100)).isEqualTo("100.0%");
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
