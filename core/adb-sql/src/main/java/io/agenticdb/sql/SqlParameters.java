package io.agenticdb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语句里的参数（占位符）。
 *
 * <p>支持两种写法：
 * <ul>
 *   <li>{@code ?} —— 位置参数，按出现顺序绑定；</li>
 *   <li>{@code :name} —— 命名参数，按名字绑定。</li>
 * </ul>
 *
 * <p>⭐ 关键：命名参数会被<b>改写成 {@code ?}</b>，值用
 * {@link java.sql.PreparedStatement} **参数化绑定** ——
 * 值永远不进 SQL 文本，所以注入风险从根上不存在。
 */
public final class SqlParameters {

    private SqlParameters() {
    }

    /**
     * 一个占位符。
     *
     * @param label 给人看的名字（{@code ? 1} / {@code :name}）
     * @param named  是不是命名参数
     * @param name   命名参数的名字；位置参数为 {@code null}
     */
    public record Parameter(String label, boolean named, String name) {
    }

    /**
     * 找出语句里的所有占位符（按出现顺序）。
     *
     * <p>字符串与注释里的 {@code ?} / {@code :x} 不算 ——
     * 靠 {@link SqlLexer} 的分词结果判断。
     */
    public static List<Parameter> detect(String sql) {
        List<Parameter> parameters = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return parameters;
        }
        int positional = 0;
        for (SqlToken token : SqlLexer.tokenize(sql)) {
            if (token.type() != SqlTokenType.PARAMETER) {
                continue;
            }
            String text = token.text();
            if (text.startsWith(":") && text.length() > 1) {
                String name = text.substring(1);
                parameters.add(new Parameter(text, true, name));
            } else {
                positional++;
                parameters.add(new Parameter("? " + positional, false, null));
            }
        }
        return parameters;
    }

    /**
     * 改写成位置参数，并按出现顺序给出要绑定的值。
     *
     * @param sql       原语句
     * @param values    命名参数的值（名字 → 值文本）
     * @param positionalValues 位置参数的值（按出现顺序）
     * @param missing   缺少值的参数名（收集出来给 UI 提示）
     */
    public static Prepared toPositional(String sql, Map<String, String> values,
                                        List<String> positionalValues, List<String> missing) {
        if (sql == null || sql.isEmpty()) {
            return new Prepared("", List.of());
        }
        StringBuilder out = new StringBuilder(sql.length() + 16);
        List<String> ordered = new ArrayList<>();
        int positionalIndex = 0;
        int cursor = 0;

        for (SqlToken token : SqlLexer.tokenize(sql)) {
            if (token.type() != SqlTokenType.PARAMETER) {
                continue;
            }
            String text = token.text();
            // 保留 token 之间的原文
            out.append(sql, cursor, token.start());
            cursor = token.end();

            if (text.startsWith(":") && text.length() > 1) {
                String name = text.substring(1);
                String value = values == null ? null : values.get(name);
                if (value == null) {
                    value = "";
                    if (missing != null) {
                        missing.add(text);
                    }
                }
                out.append('?');
                ordered.add(value);
            } else {
                String value = null;
                if (positionalValues != null && positionalIndex < positionalValues.size()) {
                    value = positionalValues.get(positionalIndex);
                }
                if (value == null) {
                    value = "";
                    if (missing != null) {
                        missing.add(text);
                    }
                }
                out.append('?');
                ordered.add(value);
                positionalIndex++;
            }
        }
        out.append(sql, cursor, sql.length());
        return new Prepared(out.toString(), ordered);
    }

    /**
     * 改写结果。
     *
     * @param sql    改写后的语句（全部是 {@code ?}）
     * @param values 按出现顺序要绑定的值
     */
    public record Prepared(String sql, List<String> values) {
    }
}
