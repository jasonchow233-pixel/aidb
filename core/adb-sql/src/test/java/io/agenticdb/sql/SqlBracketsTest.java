package io.agenticdb.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 括号配对的门禁。
 *
 * <p>最要紧的一条：<b>字符串与注释里的括号不算</b>。
 * {@code SELECT '(' FROM t} 里的 {@code (} 是文本，
 * 按纯字符配对会得出完全错误的结果。
 */
class SqlBracketsTest {

    @Test
    void matchesSimpleParentheses() {
        SqlBrackets.Match match = SqlBrackets.matchAt("SELECT (id) FROM t", 8);
        assertThat(match).isNotNull();
        assertThat(match.start()).isEqualTo(7);
        assertThat(match.end()).isEqualTo(10);
    }

    @Test
    void matchesNestedParentheses() {
        // SELECT ((a + b) * c)
        //        7  8        14      19
        SqlBrackets.Match match = SqlBrackets.matchAt("SELECT ((a + b) * c)", 9);
        assertThat(match).isNotNull();
        assertThat(match.start()).as("光标紧邻内层左括号 → 配内层").isEqualTo(8);
        assertThat(match.end()).isEqualTo(14);

        SqlBrackets.Match outer = SqlBrackets.matchAt("SELECT ((a + b) * c)", 7);
        assertThat(outer).isNotNull();
        assertThat(outer.start()).isEqualTo(7);
        assertThat(outer.end()).isEqualTo(19);
    }

    @Test
    void ignoresBracketsInsideStringLiterals() {
        // 字符串里的 '(' 不参与配对 —— 它是文本。
        assertThat(SqlBrackets.matchAt("SELECT '(' FROM t", 8))
                .as("字符串里的括号不是括号")
                .isNull();
    }

    @Test
    void ignoresBracketsInsideComments() {
        assertThat(SqlBrackets.matchAt("SELECT 1 -- ((\nFROM t", 9))
                .as("注释里的括号不是括号")
                .isNull();
    }

    @Test
    void matchesFromTheClosingSide() {
        SqlBrackets.Match match = SqlBrackets.matchAt("SELECT (id) FROM t", 10);
        assertThat(match).isNotNull();
        assertThat(match.start()).isEqualTo(7);
        assertThat(match.end()).isEqualTo(10);
    }

    @Test
    void returnsNullWhenUnbalanced() {
        assertThat(SqlBrackets.matchAt("SELECT (id FROM t", 8)).isNull();
        assertThat(SqlBrackets.matchAt("SELECT id FROM t", 8)).isNull();
        assertThat(SqlBrackets.matchAt("", 0)).isNull();
    }

    @Test
    void squareBracketsAreIdentifierQuotesInSqlite() {
        // SQLite 里 a[1] 是一个标识符 —— 方括号不是括号，不能配对。
        assertThat(SqlBrackets.matchAt("SELECT a[1] FROM t", 9)).isNull();
    }
}
