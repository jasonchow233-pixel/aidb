package io.agenticdb.asset;

import java.util.Locale;

/**
 * 对象存储 provider 子判别。
 *
 * <p>{@link AssetKind#OBJECT_STORE} 一个 kind 共用 95% 的数据模型（bucket / object / prefix），
 * 只在 SDK、endpoint、鉴权上不同 —— 用 provider 区分，而不是拆成 4 个独立 kind。
 *
 * <p>⚠ 商标：AWS / 阿里云 / 七牛 / 腾讯云均为注册商标，品牌图须用官方矢量件
 * （见 {@code brand/TRADEMARK-NOTICE.txt}；当前为占位素材，R4 一并替换）。
 */
public enum ObjectStoreProvider {

    S3("s3", "Amazon S3", "aws-s3", "adb-db--aws-s3"),
    OSS("oss", "Alibaba Cloud OSS", "aliyun-oss", "adb-db--aliyun-oss"),
    QINIU("qiniu", "Qiniu Cloud Kodo", "qiniu", "adb-db--qiniu"),
    COS("cos", "Tencent Cloud COS", "tencent-cos", "adb-db--tencent-cos");

    private final String id;
    private final String displayName;
    /** 品牌图形资产名（{@code icons/brand/<brandKey>.svg}）。 */
    private final String brandKey;
    /** 品牌作用域类（{@code .adb-db--aws-s3} 之类），与 SQL 磁贴共用 {@code .adb-db--*} 机制。 */
    private final String styleClass;

    ObjectStoreProvider(String id, String displayName, String brandKey, String styleClass) {
        this.id = id;
        this.displayName = displayName;
        this.brandKey = brandKey;
        this.styleClass = styleClass;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String brandKey() {
        return brandKey;
    }

    /**
     * 品牌作用域类（{@code .adb-db--aws-s3} 之类），与 SQL 磁贴共用 {@code .adb-db--*} 机制。
     *
     * <p>返回的是<b>完整字面量</b>（枚举常量里逐家写死），而不是 {@code "adb-db--" + brandKey}
     * 拼接 —— 后者会留下一个假的 {@code "adb-db--"} 前缀字面量，被
     * {@code StyleClassCoverageTest}（只扫字符串字面量）误当成「用了却没在 CSS 定义」的类。
     * 写死完整字面量，覆盖门禁才能去 {@code app.css} 校验它确实有品牌色规则。
     */
    public String styleClass() {
        return styleClass;
    }

    public static ObjectStoreProvider of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("object store provider must not be empty");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ObjectStoreProvider provider : values()) {
            if (provider.id.equals(normalized) || provider.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("unknown object store provider: " + value);
    }
}
