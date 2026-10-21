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

    /** 密度名（{@code compact} / {@code normal} / {@code comfort}）→ 行高（像素）。 */
    public static int of(String density) {
        return switch (density == null ? "normal" : density) {
            case "compact" -> Tokens.ROW_COMPACT_LIGHT;
            case "comfort" -> Tokens.ROW_COMFORT_LIGHT;
            default -> Tokens.ROW_NORMAL_LIGHT;
        };
    }
}
