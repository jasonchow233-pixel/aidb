package io.agenticdb.ui.shell;

import io.agenticdb.transfer.TableImporter;
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
        assertThat(summary.headline()).isEqualTo("已删除 3/3 张表，全部完成");
        assertThat(summary.body()).isEqualTo("全部完成。");
        assertThat(summary.copyText())
                .isEqualTo("删除表：已删除 3/3 张表，全部完成\n\n全部完成。");
    }

    @Test
    void failuresAreCountedAndListed() {
        BatchSummary summary = BatchSummary.of("删除表", "删除", "张表", 3, 2,
                List.of("logs：有外键依赖"));

        assertThat(summary.headline()).isEqualTo("已删除 2/3 张表，1 项失败");
        assertThat(summary.body())
                .isEqualTo("失败明细：\nlogs：有外键依赖");
    }

    @Test
    void failureCountIsNotTheNumberOfReportedLines() {
        BatchSummary summary = BatchSummary.of("导入数据", "导入", "行", 5100, 100,
                5000, List.of("第 1 行：constraint failed"),
                List.of("坏行共 5000 行，仅列出前 50 条。"));

        assertThat(summary.headline())
                .as("明细被截断时标题必须报真实失败数，而不是列表长度")
                .isEqualTo("已导入 100/5100 行，5000 项失败")
                .doesNotContain("1 项失败");
        assertThat(summary.body())
                .startsWith("坏行共 5000 行，仅列出前 50 条。")
                .contains("失败明细：\n第 1 行：constraint failed");
    }

    @Test
    void blankFailureLinesAreDroppedAndCountStillFollowsDetails() {
        BatchSummary summary = new BatchSummary("清理", "清理", "项", 2, 0, 0,
                Arrays.asList("  a  ", "", null, "b"), null);

        assertThat(summary.failed()).isEqualTo(2);
        assertThat(summary.headline()).isEqualTo("已清理 0/2 项，2 项失败");
        assertThat(summary.body()).isEqualTo("失败明细：\na\nb");
    }

    @Test
    void missingMetadataFallsBackToReadableDefaults() {
        BatchSummary summary = new BatchSummary(null, null, null, 0, 0, 0, null, null);

        assertThat(summary.title()).isEqualTo("批量操作");
        assertThat(summary.headline()).isEqualTo("已完成 0/0 项，全部完成");
        assertThat(summary.body()).isEqualTo("全部完成。");
    }

    @Test
    void aFailureCountWithoutDetailsIsStillHonest() {
        BatchSummary summary = BatchSummary.of("导入数据", "导入", "行", 10, 7,
                3, List.of(), List.of());

        assertThat(summary.headline()).isEqualTo("已导入 7/10 行，3 项失败");
        assertThat(summary.body()).isEqualTo("失败 3 项，但没有可展示的明细。");
    }

    @Test
    void importSummaryKeepsTheRealFailureCountWhenLinesAreTruncated() {
        TableImporter.ImportReport report = new TableImporter.ImportReport(
                100, 5000, List.of("第 1 行：constraint failed"), 5100);

        BatchSummary summary = ImportWizard.summaryOf(report);

        assertThat(summary.headline())
                .as("导入只保留前 50 条坏行；标题必须报 5000，不能报明细条数")
                .isEqualTo("已导入 100/5100 行，5000 项失败");
        assertThat(summary.body())
                .contains("坏行共 5000 行，仅列出前 1 条。")
                .contains("失败明细：\n第 1 行：constraint failed");
    }
}
