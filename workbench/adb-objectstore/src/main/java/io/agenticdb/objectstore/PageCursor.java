package io.agenticdb.objectstore;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 分页游标的编码与校验 —— 四家 SDK 会话共用。
 *
 * <p>游标里装的不只是 SDK 的续页凭证，还有 {@code (bucket, prefix, delimiter)}
 * 三元组。原因很实际：用户在等第一页时可以改前缀、进别的层级，旧游标若被原样
 * 用在新上下文上，服务端会返回一个「看着像成功、其实串了层」的列表。
 * 这里统一 fail-closed：上下文对不上或游标本身解不开，一律抛
 * {@link IllegalArgumentException}，而不是悄悄从首页重来。
 *
 * <p>编码用 URL-safe Base64 只为把任意字符的 marker / token 收成不透明串，
 * <b>不是</b>加密，也不含凭据；里面只有用户自己界面上的前缀和 SDK 给的续页凭证。
 */
final class PageCursor {

    /** 默认每页条数；四家 SDK 的单页上限都是 1000。 */
    static final int DEFAULT_PAGE_SIZE = 200;
    static final int MAX_PAGE_SIZE = 1000;

    private static final String SEP = "\u0000";

    private PageCursor() {
    }

    /** 把 SDK 续页凭证连上下文一起封成不透明游标；{@code null} 表示没有下一页。 */
    static String of(String bucket, String prefix, String delimiter, String sdkToken) {
        if (sdkToken == null || sdkToken.isBlank()) {
            return null;
        }
        String raw = safe(bucket) + SEP + safe(prefix) + SEP + safe(delimiter) + SEP + sdkToken;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解出 SDK 续页凭证；{@code cursor == null} 是首页，返回 {@code null}。
     * 上下文不匹配 / 格式损坏 / 凭证为空都拒绝，绝不当成首页蒙混过去。
     */
    static String tokenFor(String cursor, String bucket, String prefix, String delimiter) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid object list cursor", e);
        }
        int a = raw.indexOf(SEP);
        int b = a < 0 ? -1 : raw.indexOf(SEP, a + 1);
        int c = b < 0 ? -1 : raw.indexOf(SEP, b + 1);
        if (a < 0 || b < 0 || c < 0
                || !raw.substring(0, a).equals(safe(bucket))
                || !raw.substring(a + 1, b).equals(safe(prefix))
                || !raw.substring(b + 1, c).equals(safe(delimiter))) {
            throw new IllegalArgumentException(
                    "object list cursor does not match bucket/prefix/delimiter");
        }
        String token = raw.substring(c + 1);
        if (token.isBlank()) {
            throw new IllegalArgumentException("object list cursor has no continuation token");
        }
        return token;
    }

    /** 页大小夹到 SDK 能接受的区间；非正数用默认值，不许把 0 传给服务端。 */
    static int pageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
