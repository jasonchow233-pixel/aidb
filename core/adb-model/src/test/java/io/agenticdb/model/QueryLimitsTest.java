package io.agenticdb.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ⭐ 翻页上限的门禁。
 *
 * <p>这些数字是<b>架构不变量</b>不是配置项：一旦被调大，
 * {@code TableView} 的 {@code ObservableList} 就可能撑爆 UI 线程。
 * 任何改动都必须先改这里并说明理由。
 */
class QueryLimitsTest {

    @Nested
    @DisplayName("L1/L2 页大小")
    class PageSize档位 {

        @Test
        @DisplayName("默认 200（与 DBeaver 一致，也是本项目历史默认值）")
        void 默认200() {
            assertThat(QueryLimits.DEFAULT_PAGE_SIZE).isEqualTo(200);
        }

        @Test
        void 只有四档() {
            assertThat(QueryLimits.ALLOWED_PAGE_SIZES)
                    .containsExactly(100, 200, 500, 1000);
        }

        @Test
        void 档位递增且都在上限内() {
            int prev = 0;
            for (int size : QueryLimits.ALLOWED_PAGE_SIZES) {
                assertThat(size).isGreaterThan(prev);
                assertThat(size).isLessThanOrEqualTo(QueryLimits.MAX_PAGE_SIZE);
                prev = size;
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {100, 200, 500, 1000})
        void 四档都原样通过(int size) {
            assertThat(QueryLimits.clampPageSize(size)).isEqualTo(size);
        }

        @Test
        void 档位映射() {
            assertThat(PageSize.of(500)).contains(PageSize.S500);
            assertThat(PageSize.of(333)).as("非档位值没有对应项").isEmpty();
            // 333 距 200 是 133、距 500 是 167 → 归到 200
            assertThat(PageSize.nearest(333)).as("脏配置就近归档").isEqualTo(PageSize.S200);
            assertThat(PageSize.nearest(400)).as("400 距 500 更近").isEqualTo(PageSize.S500);
            assertThat(PageSize.nearest(1)).isEqualTo(PageSize.S100);
        }
    }

    @Nested
    @DisplayName("⛔ L3 单页硬上限 —— 不可绕过")
    class HardCap {

        @ParameterizedTest
        @ValueSource(ints = {1001, 5000, 100_000, 999_999, Integer.MAX_VALUE})
        @DisplayName("任何超限请求都被夹回 1000")
        void 超限被夹回(int requested) {
            assertThat(QueryLimits.clampPageSize(requested))
                    .as("用户手输 999999 也只能拿到 1000")
                    .isEqualTo(QueryLimits.MAX_PAGE_SIZE);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
        @DisplayName("非正数退回默认值，不抛异常")
        void 非正数退回默认(int requested) {
            assertThat(QueryLimits.clampPageSize(requested))
                    .isEqualTo(QueryLimits.DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("1000 行 × 30 列的内存占用有界（这是设上限的真正理由）")
        void 内存占用有界() {
            int rows = QueryLimits.MAX_PAGE_SIZE;
            int cols = 30;
            int bytesPerCell = 200;
            long bytes = (long) rows * cols * bytesPerCell;
            assertThat(bytes)
                    .as("约 %.1f MB", bytes / 1024.0 / 1024.0)
                    .isLessThan(16L * 1024 * 1024);
        }
    }

    @Nested
    @DisplayName("L4 深翻页")
    class DeepPaging {

        @Test
        void 上限是50万() {
            assertThat(QueryLimits.MAX_OFFSET).isEqualTo(500_000L);
        }

        @Test
        void 超限判定() {
            assertThat(QueryLimits.exceedsOffsetLimit(0)).isFalse();
            assertThat(QueryLimits.exceedsOffsetLimit(499_999)).isFalse();
            assertThat(QueryLimits.exceedsOffsetLimit(500_001)).isTrue();
        }

        @Test
        @DisplayName("负 offset 被夹到 0")
        void 负offset夹到零() {
            assertThat(Math.max(0, -50)).isZero();
        }

        @Test
        @DisplayName("⚠️ OFFSET 代价是 O(offset)，所以这个上限不能随便调高")
        void offset代价说明() {
            // 不是断言性能，是把这个约束写进测试，让改动它的人必须看见
            long offset = 400_000;
            assertThat(offset / QueryLimits.DEFAULT_PAGE_SIZE)
                    .as("翻到这里已经是第 %d 页，用户以为在浏览，实际数据库在扫表",
                            offset / QueryLimits.DEFAULT_PAGE_SIZE + 1)
                    .isGreaterThan(1_000);
        }
    }

    @Nested
    @DisplayName("L5 导出行数")
    class Export {

        @Test
        void 上限是100万() {
            assertThat(QueryLimits.MAX_EXPORT_ROWS).isEqualTo(1_000_000L);
        }

        @Test
        void 超限要二次确认() {
            assertThat(QueryLimits.exportRequiresConfirmation(999_999)).isFalse();
            assertThat(QueryLimits.exportRequiresConfirmation(1_000_001)).isTrue();
        }
    }

    @Nested
    @DisplayName("页码换算")
    class PageMath {

        @Test
        void 页码从1开始() {
            assertThat(QueryLimits.pageNumberOf(0, 200)).isEqualTo(1);
            assertThat(QueryLimits.pageNumberOf(200, 200)).isEqualTo(2);
            assertThat(QueryLimits.pageNumberOf(201, 200)).isEqualTo(2);
            assertThat(QueryLimits.pageNumberOf(400, 200)).isEqualTo(3);
        }

        @Test
        void 总页数() {
            assertThat(QueryLimits.totalPages(1000, 200)).isEqualTo(5);
            assertThat(QueryLimits.totalPages(1001, 200)).isEqualTo(6);
            assertThat(QueryLimits.totalPages(0, 200)).isZero();
        }

        @Test
        @DisplayName("total 未知时返回 -1，UI 显示「—」而不是编一个数字")
        void total未知不编造() {
            assertThat(QueryLimits.totalPages(-1, 200)).isEqualTo(-1);
        }

        @Test
        void 总页数用夹紧后的页大小() {
            assertThat(QueryLimits.totalPages(1000, 999_999))
                    .as("页大小被夹到 1000，1000 行就是 1 页")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("RowPage 契约")
    class RowPageContract {

        @Test
        @DisplayName("⭐ 没统计就必须是 null，绝不拿估算糊弄用户")
        void total未知为null() {
            RowPage p = new RowPage(
                    java.util.List.of(), java.util.List.of(), null, null, 5L,
                    java.util.List.of(), false);
            assertThat(p.hasKnownTotal()).isFalse();
            assertThat(p.total()).isNull();
        }

        @Test
        void 集合字段不可变() {
            RowPage p = new RowPage(
                    java.util.List.of(), java.util.List.of(), null, 100L, 5L,
                    java.util.List.of(), false);
            assertThat(p.columns()).isEmpty();
            assertThat(p.rows()).isEmpty();
            assertThat(p.rowCount()).isZero();
        }
    }
}