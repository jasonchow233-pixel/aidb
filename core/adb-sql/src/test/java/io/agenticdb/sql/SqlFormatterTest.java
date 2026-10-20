package io.agenticdb.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlFormatterTest {

    @Test
    void uppercasesKeywordsAndBreaksMajorClauses() {
        String formatted = SqlFormatter.format(
                "select id, name from users where active = 1 and name like 'a%' order by id;");

        assertThat(formatted).contains("SELECT id, name");
        assertThat(formatted).contains("\nFROM users");
        assertThat(formatted).contains("\nWHERE active = 1");
        assertThat(formatted).contains("\nORDER BY id;");
        assertThat(formatted).doesNotContain("select");
    }

    @Test
    void keepsStringsCommentsAndQuotedIdentifiersIntact() {
        String formatted = SqlFormatter.format(
                "select \"Mixed Name\", 'a; b' -- why\nfrom t /* keep ; */;");

        assertThat(formatted).contains("\"Mixed Name\"");
        assertThat(formatted).contains("'a; b'");
        assertThat(formatted).contains("-- why");
        assertThat(formatted).contains("/* keep ; */");
    }

    @Test
    void doesNotAddSpacesInsideQualifiedNamesOrCasts() {
        String formatted = SqlFormatter.format("select t.id::text from public.t;");
        assertThat(formatted).contains("t.id::TEXT");
        assertThat(formatted).contains("public.t");
    }

    @Test
    void emptyInputStaysEmpty() {
        assertThat(SqlFormatter.format(null)).isEmpty();
        assertThat(SqlFormatter.format("  \n ")).isEmpty();
    }
}
