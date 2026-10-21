package io.agenticdb.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ Q2 变量（参数）的门禁。
 *
 * <p>最要紧的一条：<b>值不进 SQL 文本</b> ——
 * 命名参数被改写成 {@code ?}，值走参数化绑定。
 */
class SqlParametersTest {

    @Test
    void detectsPositionalParameters() {
        List<SqlParameters.Parameter> parameters =
                SqlParameters.detect("SELECT * FROM users WHERE id = ? AND name = ?");
        assertThat(parameters).hasSize(2);
        assertThat(parameters.getFirst().named()).isFalse();
        assertThat(parameters.getFirst().label()).isEqualTo("? 1");
        assertThat(parameters.get(1).label()).isEqualTo("? 2");
    }

    @Test
    void detectsNamedParameters() {
        List<SqlParameters.Parameter> parameters =
                SqlParameters.detect("SELECT * FROM users WHERE id = :id AND name = :name");
        assertThat(parameters).hasSize(2);
        assertThat(parameters.getFirst().named()).isTrue();
        assertThat(parameters.getFirst().name()).isEqualTo("id");
        assertThat(parameters.get(1).name()).isEqualTo("name");
    }

    @Test
    void ignoresParametersInsideStringsAndComments() {
        assertThat(SqlParameters.detect("SELECT '?' AS q -- ?\nFROM t")).isEmpty();
    }

    @Test
    void rewritesNamedParametersToPositional() {
        SqlParameters.Prepared prepared = SqlParameters.toPositional(
                "SELECT * FROM users WHERE id = :id AND name = :name",
                Map.of("id", "7", "name", "张三"),
                List.of(), null);

        assertThat(prepared.sql())
                .as("命名参数要改写成 ? —— 值不能进 SQL 文本")
                .isEqualTo("SELECT * FROM users WHERE id = ? AND name = ?");
        assertThat(prepared.values()).containsExactly("7", "张三");
    }

    @Test
    void keepsPositionalOrder() {
        SqlParameters.Prepared prepared = SqlParameters.toPositional(
                "SELECT * FROM users WHERE id = ? AND name = ?",
                Map.of(), List.of("7", "张三"), null);

        assertThat(prepared.sql()).isEqualTo("SELECT * FROM users WHERE id = ? AND name = ?");
        assertThat(prepared.values()).containsExactly("7", "张三");
    }

    @Test
    void mixesNamedAndPositionalInAppearanceOrder() {
        SqlParameters.Prepared prepared = SqlParameters.toPositional(
                "SELECT * FROM t WHERE a = :named AND b = ?",
                Map.of("named", "N"), List.of("P"), null);

        assertThat(prepared.sql()).isEqualTo("SELECT * FROM t WHERE a = ? AND b = ?");
        assertThat(prepared.values())
                .as("值要按占位符出现的顺序排")
                .containsExactly("N", "P");
    }

    @Test
    void reportsMissingValues() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        SqlParameters.toPositional("SELECT * FROM t WHERE a = :x", Map.of(),
                List.of(), missing);
        assertThat(missing).containsExactly(":x");
    }
}
