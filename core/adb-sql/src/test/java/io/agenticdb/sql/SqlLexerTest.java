package io.agenticdb.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLexerTest {

    @Test
    void highlightsKeywordsStringsNumbersAndParameters() {
        List<SqlToken> tokens = SqlLexer.tokenize(
                "SELECT name, 42, 'a''b', :id FROM users WHERE id = ?");

        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.KEYWORD)
                .extracting(SqlToken::text)
                .containsExactly("SELECT", "FROM", "WHERE");
        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.STRING)
                .extracting(SqlToken::text)
                .containsExactly("'a''b'");
        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.NUMBER)
                .extracting(SqlToken::text)
                .containsExactly("42");
        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.PARAMETER)
                .extracting(SqlToken::text)
                .containsExactly(":id", "?");
    }

    @Test
    void commentsAndQuotedIdentifiersStaySingleTokens() {
        List<SqlToken> tokens = SqlLexer.tokenize(
                "-- line ; comment\nSELECT \"weird ; name\", `x;y`, [z;w] /* a ; b */ FROM t");

        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.COMMENT)
                .extracting(SqlToken::text)
                .containsExactly("-- line ; comment", "/* a ; b */");
        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.QUOTED_IDENTIFIER)
                .extracting(SqlToken::text)
                .containsExactly("\"weird ; name\"", "`x;y`", "[z;w]");
    }

    @Test
    void tokenOffsetsCoverOriginalText() {
        String sql = "SELECT 1;";
        List<SqlToken> tokens = SqlLexer.tokenize(sql);
        assertThat(tokens.getFirst().start()).isZero();
        assertThat(tokens.getLast().end()).isEqualTo(sql.length());
        for (SqlToken token : tokens) {
            assertThat(sql.substring(token.start(), token.end())).isEqualTo(token.text());
        }
    }

    @Test
    void backslashIsLiteralInStandardSqlStrings() {
        List<SqlToken> tokens = SqlLexer.tokenize("SELECT 'a\\\\' ; SELECT 2;");
        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.STRING)
                .extracting(SqlToken::text)
                .containsExactly("'a\\\\'");
    }

    @Test
    void toleratesUnterminatedStringAndBlockComment() {
        assertThat(SqlLexer.tokenize("SELECT 'oops"))
                .filteredOn(token -> token.type() == SqlTokenType.STRING)
                .extracting(SqlToken::text)
                .containsExactly("'oops");
        assertThat(SqlLexer.tokenize("SELECT /* oops"))
                .filteredOn(token -> token.type() == SqlTokenType.COMMENT)
                .extracting(SqlToken::text)
                .containsExactly("/* oops");
    }

    @Test
    void recognizesPostgresDollarQuotedString() {
        List<SqlToken> tokens = SqlLexer.tokenize("SELECT $tag$ a ; b $tag$");
        assertThat(tokens)
                .filteredOn(token -> token.type() == SqlTokenType.STRING)
                .extracting(SqlToken::text)
                .containsExactly("$tag$ a ; b $tag$");
    }
}
