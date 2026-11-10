package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoutineSql} 的纯单元门禁：不需要真数据库，把「支持 / 不支持 / 空输入 /
 * 原文保真 / 单条语句」逐条锁死。
 */
class RoutineSqlTest {

    private static final String PG_FUNCTION =
            "CREATE OR REPLACE FUNCTION public.add(a integer, b integer)\n"
            + " RETURNS integer\n"
            + " LANGUAGE plpgsql\n"
            + "AS $function$\n"
            + "BEGIN\n"
            + "    RETURN a + b;\n"
            + "END;\n"
            + "$function$";

    private static final String PG_PROCEDURE =
            "CREATE OR REPLACE PROCEDURE public.bump(IN x integer)\n"
            + " LANGUAGE plpgsql\n"
            + "AS $procedure$\n"
            + "BEGIN\n"
            + "    x := x + 1;\n"
            + "END;\n"
            + "$procedure$";

    @Test
    void onlyPostgreSqlIsSupported() {
        assertThat(RoutineSql.supported(DialectKind.POSTGRESQL)).isTrue();
        assertThat(RoutineSql.supported(DialectKind.SQLITE)).isFalse();
        assertThat(RoutineSql.supported(DialectKind.MYSQL)).isFalse();
        assertThat(RoutineSql.supported(DialectKind.MARIADB)).isFalse();
        assertThat(RoutineSql.supported(DialectKind.SQLSERVER)).isFalse();
        assertThat(RoutineSql.supported(DialectKind.ORACLE)).isFalse();
        assertThat(RoutineSql.supported(null)).isFalse();
    }

    @Test
    void unsupportedDialectsNeverProduceAnySql() {
        for (DialectKind kind : DialectKind.values()) {
            if (RoutineSql.supported(kind)) {
                continue;
            }
            assertThat(RoutineSql.preview("add", true, PG_FUNCTION, kind))
                    .as("no rebuild SQL for %s", kind)
                    .isEmpty();
        }
    }

    @Test
    void blankOrMissingDefinitionProducesNoPreview() {
        assertThat(RoutineSql.preview("add", true, null, DialectKind.POSTGRESQL)).isEmpty();
        assertThat(RoutineSql.preview("add", true, "", DialectKind.POSTGRESQL)).isEmpty();
        assertThat(RoutineSql.preview("add", true, "   \n", DialectKind.POSTGRESQL)).isEmpty();
    }

    @Test
    void nameIsValidatedBeforeAnySqlIsBuilt() {
        assertThat(RoutineSql.preview(null, true, PG_FUNCTION, DialectKind.POSTGRESQL)).isEmpty();
        assertThat(RoutineSql.preview("", true, PG_FUNCTION, DialectKind.POSTGRESQL)).isEmpty();
        assertThat(RoutineSql.preview("  ", true, PG_FUNCTION, DialectKind.POSTGRESQL)).isEmpty();
        assertThat(RoutineSql.preview("add; DROP TABLE t", true, PG_FUNCTION,
                DialectKind.POSTGRESQL)).isEmpty();
    }

    @Test
    void previewForcesOrReplaceAndKeepsTheWholeDefinitionVerbatim() {
        String body = PG_FUNCTION.replace("CREATE OR REPLACE FUNCTION", "CREATE FUNCTION");
        String sql = RoutineSql.preview("add", true, body, DialectKind.POSTGRESQL);

        assertThat(sql).startsWith("CREATE OR REPLACE FUNCTION public.add(a integer, b integer)");
        assertThat(sql).contains("RETURN a + b;");
        assertThat(sql).contains("LANGUAGE plpgsql");
        assertThat(sql).contains("$function$");
        assertThat(sql).doesNotEndWith(";");
    }

    @Test
    void trailingSemicolonsAreStrippedButNeverSplitIntoASecondStatement() {
        String sql = RoutineSql.preview("add", true, PG_FUNCTION + ";;;  \n",
                DialectKind.POSTGRESQL);

        assertThat(sql).doesNotEndWith(";");
        assertThat(sql).contains("RETURN a + b;");
        assertThat(sql).contains("END;");
        assertThat(occurrences(sql, "CREATE OR REPLACE FUNCTION")).isEqualTo(1);
    }

    @Test
    void aSecondCreateStatementIsRefusedRatherThanReplayed() {
        String tampered = PG_FUNCTION + ";\n"
                + "CREATE OR REPLACE FUNCTION public.evil() RETURNS void AS $$ BEGIN END; $$;";
        assertThat(RoutineSql.preview("add", true, tampered, DialectKind.POSTGRESQL)).isEmpty();
    }

    @Test
    void aBareFunctionBodyIsRefusedInsteadOfInventingASignature() {
        assertThat(RoutineSql.preview("add", true,
                "BEGIN RETURN a + b; END;", DialectKind.POSTGRESQL)).isEmpty();
    }

    @Test
    void editedBodyIsRegeneratedVerbatimSoExecuteRebuildsWhatTheUserTyped() {
        // ⭐ 例程「编辑 / 执行」的核心：用户在 PG 上把 `a + b` 改成 `a * b` 后点 Execute，
        // 重建语句必须包含这次编辑 —— 否则执行的不是用户写的东西。
        String edited = PG_FUNCTION.replace("RETURN a + b;", "RETURN a * b;");
        String sql = RoutineSql.preview("add", true, edited, DialectKind.POSTGRESQL);

        assertThat(sql).startsWith("CREATE OR REPLACE FUNCTION public.add(a integer, b integer)");
        assertThat(sql).contains("RETURN a * b;");
        assertThat(sql).doesNotContain("RETURN a + b;");
    }

    @Test
    void editThatDestroysTheCreateHeadCannotBeRebuilt() {
        // 用户把定义体改得不再是完整 CREATE（例如删了头） —— 必须返回空，
        // 否则 RoutinePage 会拿一段拼不出签名的文本去执行（触发 cannotRebuild 报错）。
        String broken = PG_FUNCTION.replace("CREATE OR REPLACE FUNCTION", "/* mangled */");
        assertThat(RoutineSql.preview("add", true, broken, DialectKind.POSTGRESQL)).isEmpty();
    }

    @Test
    void procedureKeywordIsKeptAndMismatchedTypeIsRefused() {
        assertThat(RoutineSql.preview("bump", false, PG_PROCEDURE, DialectKind.POSTGRESQL))
                .startsWith("CREATE OR REPLACE PROCEDURE public.bump");
        assertThat(RoutineSql.preview("bump", true, PG_PROCEDURE, DialectKind.POSTGRESQL))
                .isEmpty();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }
}
