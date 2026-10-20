package io.agenticdb.declarations;

import java.util.List;

/**
 * SQL 方言的词表 —— 给语法高亮和补全用。
 *
 * <p>⚠️ 注意：Rust 侧的 {@code build.rs} 只检查这些文件的<b>存在性</b>，
 * <b>从未读取内容</b>。这是本模块接手后第一次真正消费它们。
 */
public record Dialect(
        int schemaVersion,
        String id,
        String label,
        String identifierQuote,
        String paramStyle,
        List<String> keywords,
        List<String> functions,
        List<String> types) {

    /** 关键字转小写集合，供 {@code adb-sql} 的分词器查表。 */
    public java.util.Set<String> keywordSet() {
        return keywords.stream()
                .map(k -> k.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public java.util.Set<String> functionSet() {
        return functions.stream()
                .map(k -> k.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}