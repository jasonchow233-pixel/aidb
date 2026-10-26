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
     * ⭐ D1（2026-10-26 裁定）：**记录网格**（数据页网格 / 冻结列 / 转置表 / 查询结果）
     * 恒比当前档位**低一档**，其余表面对象（侧栏树、表清单、对象首页、连接页、结构页）
     * 照 {@link #of} 走。
     *
     * <p>为什么只有网格降：网格是唯一「逐行数数据」的地方 —— 28px 一屏 19 行，
     * Navicat 同窗口 45 行；而树和清单读的是名字，压到 24 只是让点击目标变小。
     * 「低一档」而不是「钉死 24」，是为了让四档密度开关在网格上仍然是连续可感的
     * （22 / 22 / 24 / 28），不出现「切了档但网格没反应」。
     *
     * <p>档位映射（默认 {@code normal} → 网格 24，其余仍 28）：
     * {@code dense 22 / compact 22 / normal 24 / comfort 28}，下限是 {@code dense}。
     */
    public static int grid(String density) {
        java.util.List<String> levels = java.util.List.of("dense", "compact", "normal", "comfort");
        int index = levels.indexOf(density == null ? "normal" : density);
        if (index < 0) {
            index = levels.indexOf("normal");
        }
        return of(levels.get(Math.max(0, index - 1)));
    }
}
