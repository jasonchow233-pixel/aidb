package io.agenticdb.ui;

/**
 * ⭐ 密度档位 → 行高。
 *
 * <p>以前密度只改对象树与数据网格，查询结果、表清单、结构页的三张表**纹丝不动** ——
 * 用户在数据页调到「紧凑」，切到查询结果页行高没变，
 * 于是密度设置**看起来时灵时不灵**。
 *
 * <p>行高取自 {@code row-*} 令牌（不写死在 CSS 里）。
 */
public final class RowHeights {

    private RowHeights() {
    }

    /** 密度名（{@code dense} / {@code compact} / {@code normal} / {@code comfort}）→ 行高（像素）。 */
    public static int of(String density) {
        return switch (density == null ? "normal" : density) {
            case "dense" -> Tokens.ROW_DENSE_LIGHT;
            case "compact" -> Tokens.ROW_COMPACT_LIGHT;
            case "normal" -> Tokens.ROW_NORMAL_LIGHT;
            case "comfort" -> Tokens.ROW_COMFORT_LIGHT;
            default -> Tokens.ROW_NORMAL_LIGHT;
        };
    }

    /**
     * ⭐ 2026-10-26 用户裁定「保持和 Navicat 一样」：记录网格（数据页 / 冻结列 /
     * 转置表 / 查询结果）比外壳清单高 **2px** —— Navicat 实测清单 21 / 网格 23。
     * 之前「网格比档位低一档」的裁定随本次对齐作废。
     *
     * <p>档位映射（默认 {@code normal}）：清单/树 21，网格 23；
     * {@code dense 19/21 · compact 20/22 · normal 21/23 · comfort 26/28}。
     */
    public static int grid(String density) {
        return of(density) + 2;
    }
}
