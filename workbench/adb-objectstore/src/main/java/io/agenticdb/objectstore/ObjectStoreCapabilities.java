package io.agenticdb.objectstore;

import io.agenticdb.asset.ObjectStoreProvider;

/**
 * 一家 provider 明确支持哪些操作 —— <b>诚实性契约</b>。
 *
 * <p>规划 §3 要求：不支持的操作按钮必须置灰并说明原因，不许留「点了没反应」的假控件。
 * 能力声明放在后端而不是 UI 里散落的 if，UI 只问一次 {@link ObjectStoreSession#capabilities()}
 * 就把整页按钮状态定下来；后端对未声明的操作抛 {@link UnsupportedOperationException}，
 * 于是「UI 说不能点」和「后端真不给做」是同一份事实。
 *
 * <p>四家的差异（见 {@link #forProvider(ObjectStoreProvider)}）：
 * <ul>
 *   <li>S3 / OSS / COS：列出 bucket、预签名、服务端 copy/move、递归删前缀都支持；</li>
 *   <li>七牛 Kodo：bucket 由控制台管理（SDK 无列出全部 bucket 的可靠原语）、
 *       <b>没有服务端 move</b>（只能 copy + delete 组合，且非原子），
 *       {@code move=false} 让 UI 把「移动/重命名」置灰并给出说明。</li>
 * </ul>
 */
public record ObjectStoreCapabilities(
        ObjectStoreProvider provider,
        boolean supportsListBuckets,
        boolean supportsMove,
        boolean supportsCopy,
        boolean supportsPresign,
        boolean supportsServerSideFolders,
        String unsupportedNote) {

    /** 按 provider 取该家的能力矩阵；改动只在这一个 switch 里登记。 */
    public static ObjectStoreCapabilities forProvider(ObjectStoreProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        return switch (provider) {
            case S3, OSS, COS -> new ObjectStoreCapabilities(
                    provider, true, true, true, true, false, "");
            case QINIU -> new ObjectStoreCapabilities(
                    provider, false, false, true, true, false,
                    "objectstore.capability.qiniu.noMove");
        };
    }

    /** 「移动/重命名」不可用时给 UI 的 i18n 键；可用时返回空串。 */
    public String moveNote() {
        return supportsMove ? "" : unsupportedNote;
    }
}
