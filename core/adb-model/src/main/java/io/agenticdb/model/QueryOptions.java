package io.agenticdb.model;

/**
 * 一次查询的执行参数。
 *
 * <p>⚠️ 不接受「无上限」这种状态。{@code pageSize} 一定会被
 * {@link QueryLimits#clampPageSize} 处理，调用方无法绕过上限。
 *
 * @param maxRows 单页行数，上限 {@link QueryLimits#MAX_PAGE_SIZE}
 * @param offset  偏移量，上限 {@link QueryLimits#MAX_OFFSET}
 * @param sql     已经过 {@code DangerClassifier.classify} 的 SQL
 */
public record QueryOptions(int maxRows, long offset, String sql) {

    public QueryOptions {
        // ⭐ 在构造器里夹紧，不是靠调用方自觉。这是「上限不可绕过」的实现点。
        maxRows = QueryLimits.clampPageSize(maxRows);
        offset = Math.max(0, offset);
    }

    public static QueryOptions of(String sql, int maxRows) {
        return new QueryOptions(maxRows, 0, sql);
    }

    public int pageNumber() {
        return QueryLimits.pageNumberOf(offset, maxRows);
    }

    /** 深翻页是否已被拦下。UI 拿到 true 就显示「改用筛选条件」。 */
    public boolean offsetLimitExceeded() {
        return QueryLimits.exceedsOffsetLimit(offset);
    }
}