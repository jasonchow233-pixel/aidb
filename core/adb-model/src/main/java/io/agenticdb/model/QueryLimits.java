package io.agenticdb.model;

/**
 * ⭐⭐⭐ 查询行数限额 —— 这是全系统最重要的一个约束。
 *
 * <h2>为什么要有硬上限</h2>
 * JavaFX 的 {@code TableView} <b>自带虚拟化</b>（底层 {@code VirtualFlow}，
 * 只创建可见行的 Node），所以「行数」本身不是瓶颈。
 * 它唯一的致命误用是：<b>把结果一次性塞进 {@code ObservableList}</b>。
 * 一旦这样做，100 万行和 100 行的区别不是「慢一点」，
 * 而是构造列表时 OOM 或卡死 UI 线程。
 *
 * <p>所以上限不是"为了性能打折"，而是<b>架构不变量</b>：
 * 任何路径都只能拿到「一页」，拿不到「全表」。
 *
 * <h2>参照</h2>
 * <ul>
 *   <li>DBeaver 数据编辑器默认页大小 <b>200</b>（社区长期默认，见其设置项）</li>
 *   <li>本项目历史实现 {@code store.ts:318} 的默认值也是 <b>200</b></li>
 * </ul>
 *
 * <h2>五层闸门</h2>
 * <pre>
 *  L1 默认页大小        200    首次打开一张表
 *  L2 页大小可选档位    100 / 200 / 500 / 1000    UI 下拉，不许手输任意值
 *  L3 单页硬上限        1000   服务端 Statement.setMaxRows 强制，不可绕过
 *  L4 OFFSET 深翻页    500_000   超过则要求用户改用「按条件筛选」而不是继续翻
 *  L5 单次导出行数      1_000_000  超过需二次确认，且必须流式写盘不得缓冲
 * </pre>
 *
 * <h2>为什么不设更高</h2>
 * Navicat / DBeaver 都不做「50 万行网格」。它们也是分页的。
 * 把 {@code L3} 设成 1000 意味着任何一次查询的内存占用有界：
 * 1000 行 × 约 30 列 × 约 200 字节 ≈ <b>6 MB</b>。
 * 这个数字与「用户选了多大的页」无关，与「表里有多少行」也无关。
 *
 * @see PageSize
 * @see QueryOptions
 */
public final class QueryLimits {

    private QueryLimits() {
    }

    /** L1 —— 默认页大小。与 DBeaver 一致，也是本项目历史默认值。 */
    public static final int DEFAULT_PAGE_SIZE = 200;

    /**
     * L2 —— 页大小可选档位。UI 只提供这几档。
     * <p>为什么不给「自定义」：一旦允许手输，用户会输 999999，
     * 然后 UI 卡死，而这不是他能理解的因果关系。
     */
    public static final int[] ALLOWED_PAGE_SIZES = {100, 200, 500, 1000};

    /**
     * L3 —— 单页硬上限。{@code Statement.setMaxRows(1000)} 在 JDBC 层强制，
     * 即使 UI 被绕过、即使调用方直接调 {@code Workbench} 门面。
     */
    public static final int MAX_PAGE_SIZE = 1000;

    /**
     * L4 —— OFFSET 上限。
     * <p>为什么必须有：{@code LIMIT n OFFSET m} 的代价是 O(m)。
     * PG 在一张百万行无索引表上翻到第 40 页（offset 8000）就要全表扫；
     * 继续翻只会越来越慢，而用户以为自己在「浏览」。
     * 到达这个上限时应提示改用筛选条件或按主键跳转，而不是继续翻页。
     */
    public static final long MAX_OFFSET = 500_000L;

    /**
     * L5 —— 单次导出行数上限。超过需二次确认，且必须流式写出。
     * <p>与 L3 的区别：导出不渲染到网格，没有 {@code ObservableList} 的问题，
     * 但有内存与耗时问题，所以它是「确认门」而不是「硬砍」。
     */
    public static final long MAX_EXPORT_ROWS = 1_000_000L;

    /** 超过这个字节数，单元格内容在传输途中被截断，由详情面板按需再取。 */
    public static final int MAX_CELL_BYTES = 20 * 1024;

    // ──────────────────────────── 行为 ────────────────────────────

    /**
     * 把请求的页大小夹到 {@link #MAX_PAGE_SIZE}。
     * <p>非正数一律退回默认值 —— 不抛异常，因为调用方可能是从配置里读到的脏值，
     * 让它崩掉不如让它用默认值并在 UI 上显示「已被限制」。
     */
    public static int clampPageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    /**
     * 深翻页判定。
     *
     * @return true 表示这次翻页超出了 OFFSET 上限
     */
    public static boolean exceedsOffsetLimit(long offset) {
        return offset > MAX_OFFSET;
    }

    /** 该 offset 对应的页码（从 1 开始）。 */
    public static int pageNumberOf(long offset, int pageSize) {
        return (int) (offset / clampPageSize(pageSize)) + 1;
    }

    /** 该 offset / pageSize 下的总页数。total 未知时返回 -1（UI 显示「—」）。 */
    public static long totalPages(long total, int pageSize) {
        if (total < 0) {
            return -1;
        }
        int size = clampPageSize(pageSize);
        return total == 0 ? 0 : (total + size - 1) / size;
    }

    /**
     * 「加载全部」是否需要二次确认。
     * <p>UI 上必须把真实数字写出来（「将读取 1,000,000 行」），不能只问一个「确定吗」。
     */
    public static boolean exportRequiresConfirmation(long rowCount) {
        return rowCount > MAX_EXPORT_ROWS;
    }
}