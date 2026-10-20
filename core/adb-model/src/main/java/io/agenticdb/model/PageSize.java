package io.agenticdb.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * 页大小。UI 下拉的数据源 —— 只有四档，没有「自定义」。
 *
 * @see QueryLimits#ALLOWED_PAGE_SIZES
 */
public enum PageSize {

    S100(100),
    S200(200),
    S500(500),
    S1000(1000);

    private final int value;

    PageSize(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** 最接近该值的档位。用于「上次用了 300 行」这种脏配置的迁移。 */
    public static PageSize nearest(int requested) {
        return Arrays.stream(values())
                .min((a, b) -> Integer.compare(
                        Math.abs(a.value - requested),
                        Math.abs(b.value - requested)))
                .orElse(S200);
    }

    public static Optional<PageSize> of(int value) {
        return Arrays.stream(values()).filter(p -> p.value == value).findFirst();
    }
}