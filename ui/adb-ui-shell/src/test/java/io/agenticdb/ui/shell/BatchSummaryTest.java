package io.agenticdb.ui.shell;

import io.agenticdb.transfer.TableImporter;
import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2-2：批量汇总的**口径**门禁。
 *
 * <p>最容易错的两件事：
 * <ul>
 *   <li>把「失败明细条数」当成「失败数」—— 导入只保留前 50 条坏行，
 *       用户会以为只坏了 50 行；</li>
 *   <li>全部成功时也说「失败」，让人以为出了问题。</li>
 * </ul>
 */
class BatchSummaryTest {

    @Test
    void allSucceededSaysSoWithoutMentioningFailures() {
        BatchSummary summary = BatchSummary.of("删除表", "删除", "张表", 3, 3, List.of());

        assertThat(summary.allSucceeded()).isTrue();
        assertThat(summary.headline())
                .isEqualTo(I18n.t("batch.headline.allDone", "删除", 3, 3, "张表"));
        assertThat(summary.body()).isEqualTo(I18n.t("batch.body.allDone"));
        assertThat(summary.copyText())
                .isEqualTo("删除表" + I18n.t("batch.copy.separator")
                        + I18n.t("batch.headline.allDone", "删除", 3, 3, "张表")
                        + "\n\n" + I18n.t("batch.body.allDone"));
    }

    @Test
    void failuresAreCountedAndListed() {
        BatchSummary summary = BatchSummary.of("删除表", "删除", "张表", 3, 2,
                List.of("logs：有外键依赖"));

        assertThat(summary.headline())
                .isEqualTo(I18n.t("batch.headline.failed", "删除", 2, 3, "张表", 1));
        assertThat(summary.body())
                .isEqualTo(I18n.t("batch.body.details", "logs：有外键依赖"));
    }

    @Test
    void failureCountIsNotTheNumberOfReportedLines() {
        BatchSummary summary = BatchSummary.of("导入数据", "导入", "行", 5100, 100,
                5000, List.of("第 1 行：constraint failed"),
                List.of("坏行共 5000 行，仅列出前 50 条。"));

        assertThat(summary.headline())
                .as("明细被截断时标题必须报真实失败数，而不是列表长度")
                .isEqualTo(I18n.t("batch.headline.failed", "导入", 100, 5100, "行", 5000))
                .doesNotContain(I18n.t("batch.headline.failed", "导入", 100, 5100, "行", 1));
        assertThat(summary.body())
                .startsWith("坏行共 5000 行，仅列出前 50 条。")
                .contains(I18n.t("batch.body.details", "第 1 行：constraint failed"));
    }

    @Test
    void blankFailureLinesAreDroppedAndCountStillFollowsDetails() {
        BatchSummary summary = new BatchSummary("清理", "清理", "项", 2, 0, 0,
                Arrays.asList("  a  ", "", null, "b"), null);

        assertThat(summary.failed()).isEqualTo(2);
        assertThat(summary.headline())
                .isEqualTo(I18n.t("batch.headline.failed", "清理", 0, 2, "项", 2));
        assertThat(summary.body()).isEqualTo(I18n.t("batch.body.details", "a\nb"));
    }

    @Test
    void missingMetadataFallsBackToReadableDefaults() {
        BatchSummary summary = new BatchSummary(null, null, null, 0, 0, 0, null, null);

        assertThat(summary.title()).isEqualTo(I18n.t("batch.fallback.title"));
        assertThat(summary.headline()).isEqualTo(I18n.t("batch.headline.allDone",
                I18n.t("batch.fallback.action"), 0, 0, I18n.t("batch.fallback.unit")));
        assertThat(summary.body()).isEqualTo(I18n.t("batch.body.allDone"));
    }

    @Test
    void aFailureCountWithoutDetailsIsStillHonest() {
        BatchSummary summary = BatchSummary.of("导入数据", "导入", "行", 10, 7,
                3, List.of(), List.of());

        assertThat(summary.headline())
                .isEqualTo(I18n.t("batch.headline.failed", "导入", 7, 10, "行", 3));
        assertThat(summary.body()).isEqualTo(I18n.t("batch.body.noDetails", 3));
    }

    @Test
    void importSummaryKeepsTheRealFailureCountWhenLinesAreTruncated() {
        TableImporter.ImportReport report = new TableImporter.ImportReport(
                100, 5000, List.of("第 1 行：constraint failed"), 5100);

        BatchSummary summary = ImportWizard.summaryOf(report);

        assertThat(summary.headline())
                .as("导入只保留前 50 条坏行；标题必须报 5000，不能报明细条数")
                .isEqualTo(I18n.t("batch.headline.failed", I18n.t("import.summary.action"),
                        100, 5100, I18n.t("import.summary.unit"), 5000));
        assertThat(summary.body())
                .contains(I18n.t("import.note.truncated", 5000, 1))
                .contains(I18n.t("batch.body.details", "第 1 行：constraint failed"));
    }
}
