package io.agenticdb.objectstore;

import io.agenticdb.model.Danger;

import java.util.Locale;

/**
 * 对象存储操作 → {@link Danger} 分级（族无关化的一步）。
 *
 * <p>与 {@code RedisDanger} / {@code DangerClassifier} 同一口径：
 * <ul>
 *   <li>{@code READ_ONLY}：列举 / 读取 / 元数据 / 生成临时 URL —— 不改远端状态；</li>
 *   <li>{@code MUTATING}：上传 / 复制 / 移动 / 建 bucket —— 写数据，需确认；</li>
 *   <li>{@code DESTRUCTIVE}：删对象 / 递归删前缀 / 删 bucket —— 不可恢复；</li>
 *   <li>未知或空白操作名 <b>fail-closed 到 {@code ADMIN}</b>，与 SQL 分类器一致：
 *       宁可要求最高等级审批，也不悄悄当只读放过。</li>
 * </ul>
 *
 * <p>调用方（UI）按等级决定是否弹 {@code DestructiveConfirm}；会话本身不拦截写操作 ——
 * 与 Redis 的「分级 → 确认 → 执行」三级分工完全一致，不另造一套。
 */
public final class ObjectStoreDanger {

    private ObjectStoreDanger() {
    }

    public static Danger level(String op) {
        if (op == null || op.isBlank()) {
            return Danger.ADMIN;
        }
        return switch (op.toUpperCase(Locale.ROOT)) {
            case "LIST_BUCKETS", "LIST_OBJECTS", "GET_OBJECT", "HEAD_OBJECT",
                 "METADATA", "PRESIGN_URL", "STAT" -> Danger.READ_ONLY;
            case "PUT_OBJECT", "UPLOAD", "CREATE_BUCKET", "COPY_OBJECT",
                 "MOVE_OBJECT", "SET_ACL", "SET_METADATA" -> Danger.MUTATING;
            case "DELETE_OBJECT", "DELETE_PREFIX", "DELETE_BUCKET", "DELETE_OBJECTS" ->
                    Danger.DESTRUCTIVE;
            default -> Danger.ADMIN;
        };
    }
}
