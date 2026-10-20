package io.agenticdb.sql;

import java.util.Objects;

/**
 * 一个 SQL 词法单元。
 *
 * @param type  类别
 * @param text  原始文本
 * @param start 0 起、包含
 * @param end   0 起、不包含
 */
public record SqlToken(SqlTokenType type, String text, int start, int end) {

    public SqlToken {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("非法 token 区间: " + start + ".." + end);
        }
    }

    public int length() {
        return end - start;
    }
}
