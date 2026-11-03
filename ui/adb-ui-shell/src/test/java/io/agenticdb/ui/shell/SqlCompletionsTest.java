package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R57-a：补全规则的门禁 —— 纯逻辑，不发连接、不建界面。
 *
 * <p>这条纪律与 R53 的 {@code EditValues.emptiedCell} 同族：规则只有一份，
 * 界面吃的也是 {@link SqlCompletions#suggest}，这里钉住的行为就是弹层将来的行为。
 */
class SqlCompletionsTest {

    private static final List<SqlCompletions.Entry> OBJECTS = List.of(
            new SqlCompletions.Entry("users", SqlCompletions.Kind.TABLE),
            new SqlCompletions.Entry("products", SqlCompletions.Kind.TABLE),
            new SqlCompletions.Entry("v_paid", SqlCompletions.Kind.VIEW),
            new SqlCompletions.Entry("my table", SqlCompletions.Kind.TABLE),
            new SqlCompletions.Entry("count_logs", SqlCompletions.Kind.ROUTINE));

    private static final Set<String> KEYWORDS =
            new LinkedHashSet<>(List.of("SELECT", "FROM", "WHERE", "WITH"));

    /** 替身模式源：列清单写死，loaded 集合控制「读到没读到」，与真控制器的语义一致。 */
    private record Fake(boolean objectsReady, List<SqlCompletions.Entry> objects,
                        Set<String> loadedColumns,
                        Map<String, List<String>> columns) implements SqlCompletions.Source {

        @Override
        public List<SqlCompletions.Entry> objects() {
            return objectsReady ? objects : List.of();
        }

        @Override
        public boolean objectsReady() {
            return objectsReady;
        }

        @Override
        public List<String> columnsOf(String table) {
            return columns.getOrDefault(table.toLowerCase(Locale.ROOT), List.of());
        }

        @Override
        public boolean columnsLoaded(String table) {
            return loadedColumns.contains(table.toLowerCase(Locale.ROOT));
        }

        @Override
        public Set<String> keywords() {
            return KEYWORDS;
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }
    }

    private static final Fake READY = new Fake(true, OBJECTS, Set.of("users", "products"),
            Map.of("users", List.of("id", "name"),
                    "products", List.of("price", "qty")));

    private static SqlCompletions.Proposal suggest(String sql) {
        return SqlCompletions.suggest(sql, sql.length(), READY);
    }

    private static List<String> names(SqlCompletions.Proposal proposal) {
        return proposal.candidates().stream().map(SqlCompletions.Candidate::name).toList();
    }

    @Test
    void fromContextOffersTablesAndViewsOnly() {
        SqlCompletions.Proposal proposal = suggest("SELECT * FROM ");
        assertThat(names(proposal)).contains("users", "products", "v_paid", "my table");
        assertThat(proposal.candidates()).allMatch(candidate ->
                candidate.kind() == SqlCompletions.Kind.TABLE
                        || candidate.kind() == SqlCompletions.Kind.VIEW);
    }

    @Test
    void partialWord_filtersByPrefixAndCoversOnlyTheWord() {
        String sql = "SELECT * FROM us";
        SqlCompletions.Proposal proposal = suggest(sql);
        assertThat(names(proposal)).containsExactly("users");
        assertThat(proposal.replaceStart()).isEqualTo(sql.length() - 2);
        assertThat(proposal.replaceEnd()).isEqualTo(sql.length());
    }

    @Test
    void prefixMatchIgnoresCase() {
        assertThat(names(suggest("SELECT * FROM US"))).containsExactly("users");
    }

    @Test
    void qualifiedName_givesThatTablesColumns() {
        SqlCompletions.Proposal proposal = suggest("SELECT * FROM users.");
        assertThat(names(proposal)).containsExactly("id", "name");
        assertThat(proposal.candidates()).allMatch(candidate ->
                candidate.kind() == SqlCompletions.Kind.COLUMN
                        && "users".equals(candidate.detail()));
        assertThat(proposal.modelReady()).isTrue();
    }

    @Test
    void qualifiedName_filtersPartialColumn() {
        assertThat(names(suggest("SELECT * FROM users.na"))).containsExactly("name");
    }

    @Test
    void alias_resolvesToItsTable() {
        assertThat(names(suggest("SELECT * FROM users u WHERE u.")))
                .containsExactly("id", "name");
        assertThat(names(suggest("SELECT * FROM users AS u WHERE u.i")))
                .containsExactly("id");
    }

    @Test
    void schemaQualified_nameUsesTailSegment() {
        assertThat(names(suggest("SELECT * FROM shop.users."))).containsExactly("id", "name");
    }

    @Test
    void unknownQualifier_doesNotGuess() {
        SqlCompletions.Proposal proposal = suggest("SELECT * FROM nope.");
        assertThat(proposal.candidates()).isEmpty();
        assertThat(proposal.modelReady()).isTrue();
    }

    @Test
    void aliasDoesNotLeakAcrossStatements() {
        assertThat(suggest("SELECT * FROM users u;\nSELECT u.").candidates()).isEmpty();
    }

    @Test
    void numberBeforeDot_staysQuiet() {
        assertThat(suggest("SELECT 1.").candidates()).isEmpty();
    }

    @Test
    void insideStringLiteral_staysQuiet() {
        assertThat(suggest("SELECT 'abc").candidates()).isEmpty();
    }

    @Test
    void insideComment_staysQuiet() {
        assertThat(suggest("-- SELECT * FROM ").candidates()).isEmpty();
    }

    @Test
    void genericContext_leadsWithColumnsOfReferencedTables() {
        SqlCompletions.Proposal proposal = suggest("SELECT * FROM users WHERE ");
        assertThat(proposal.candidates().getFirst().kind()).isEqualTo(SqlCompletions.Kind.COLUMN);
        assertThat(names(proposal)).contains("id", "name", "users", "SELECT");
    }

    @Test
    void emptyEditor_returnsKeywordsWhileModelStillLoading() {
        // 对象还没读回来（冷弹层第一次按 ⌘Space）：关键字先顶上，ready=false 让控制器挂「正在读」。
        SqlCompletions.Proposal proposal =
                SqlCompletions.suggest("", 0, new Fake(false, OBJECTS, Set.of(), Map.of()));
        assertThat(names(proposal)).contains("SELECT");
        assertThat(proposal.modelReady()).isFalse();
    }

    @Test
    void unloadedColumns_areReportedAsMissingNotAbsent() {
        Fake cold = new Fake(true, OBJECTS, Set.of(), Map.of());
        SqlCompletions.Proposal proposal = SqlCompletions.suggest("SELECT * FROM users.",
                20, cold);
        assertThat(proposal.candidates()).isEmpty();
        assertThat(proposal.missingTables()).containsExactly("users");
        assertThat(proposal.modelReady()).isFalse();
    }

    @Test
    void unsafeIdentifier_insertIsQuoted() {
        SqlCompletions.Proposal proposal = suggest("SELECT * FROM my ");
        SqlCompletions.Candidate candidate = proposal.candidates().stream()
                .filter(entry -> "my table".equals(entry.name()))
                .findFirst().orElseThrow();
        assertThat(candidate.insertText()).isEqualTo("\"my table\"");
    }

    @Test
    void plainIdentifier_insertStaysBare() {
        SqlCompletions.Proposal proposal = suggest("SELECT * FROM users.");
        assertThat(proposal.candidates().getFirst().insertText()).isEqualTo("id");
    }

    @Test
    void updateContext_offersTables() {
        assertThat(names(suggest("UPDATE "))).contains("users", "products");
    }

    @Test
    void candidatesAreCapped() {
        List<SqlCompletions.Entry> many = new ArrayList<>();
        for (int i = 0; i < SqlCompletions.MAX_CANDIDATES + 100; i++) {
            many.add(new SqlCompletions.Entry("t" + i, SqlCompletions.Kind.TABLE));
        }
        Fake flood = new Fake(true, List.copyOf(many), Set.of(), Map.of());
        assertThat(suggest(flood, "SELECT * FROM ").candidates().size())
                .isEqualTo(SqlCompletions.MAX_CANDIDATES);
    }

    private static SqlCompletions.Proposal suggest(SqlCompletions.Source source, String sql) {
        return SqlCompletions.suggest(sql, sql.length(), source);
    }
}
