package io.agenticdb.ui.shell;

import io.agenticdb.introspect.QueryPaginator;
import io.agenticdb.sql.SqlQueryShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ D-64（R66 余账）：「界面说可改」必须等于「保存后重取得到」。
 *
 * <p>这两件事分属两层：可改性由 {@link SqlQueryShape} 判（{@code QueryPage.java:1704}），
 * 重取钩子却挂在分页那一支上（{@code QueryPage.java:1636} 的
 * {@code if (QueryPaginator.isPaginable(sql) && …) edit.reload = loader::load}）。
 * 于是有一条**单向包含关系**撑着整件事：shape 判可改的每一句，paginator 都必须判可分页。
 * 一旦漏出差集，症状是「状态那句『已保存』是真的、格子里的值是旧的」——
 * 而且不报错，用户没有任何线索。R58-c 当时只把这段关系写进注释，零门禁；
 * 注释还说它是**潜在**边界，本文件量下来的结论见下面「口径三」。
 *
 * <p>⚠️ 口径一：两层各读同一份语句文本，但读法不同 —— shape 走词法（字符串与注释
 * 是独立 token），paginator 走 {@code String.contains}。粗判层不需要读懂投影，
 * 但它不许把字面量里的字样当成 SQL 结构。
 *
 * <p>⚠️ 口径二：这条断言**单向**。反向（可分页但不可改）是正常且必要的 ——
 * {@code WITH … SELECT …} 分页取数没问题，回写却说不清那一行来自哪张表。
 * 所以 corpus 里 {@code WITH} 那一行钉的是「paginator 说可分页、shape 说不可改」，
 * 别把它当差集删掉。
 *
 * <p>⚠️ 口径三：corpus 里每条都带期望的拒绝理由，最后一条断言要求
 * {@link SqlQueryShape.Block} 的**每一档**都被至少一句样本走到 ——
 * 否则「包含关系全绿」可能只是因为样本没碰到会出事的那一档。
 */
class RefetchBoundaryTest {

    /** 一句样本：SQL、期望停在哪个拒绝档（{@code null} = 期望判成可改）。 */
    private record Sample(String sql, SqlQueryShape.Block expected) {
    }

    private static final List<Sample> CORPUS = List.of(
            // —— 判成可改的：这些句句都得走得到重取钩子 ——
            new Sample("SELECT * FROM users", null),
            new Sample("SELECT * FROM users;", null),
            new Sample("select id, name from users where id = 3", null),
            new Sample("SELECT id, name FROM users ORDER BY id DESC", null),
            new Sample("SELECT u.id, u.name FROM users u WHERE u.id > ?", null),

            // ⭐ 口径一的落点：这些句子结构上是普通可改 SELECT，
            //    只是文本里出现了「into / for update / ;」的**字样**（在字符串字面量或注释里）。
            new Sample("SELECT * FROM messages WHERE body LIKE '% into %'", null),
            new Sample("SELECT * FROM tickets WHERE note = 'log in for update first'", null),
            new Sample("SELECT * FROM t WHERE note = 'a;b'", null),
            new Sample("SELECT * FROM t WHERE note = 'it''s a; test'", null),
            new Sample("SELECT \"for update\" FROM users", null),
            new Sample("-- 先说明一下\nSELECT id FROM users", null),
            new Sample("/* 头部注释 */ SELECT id FROM users", null),
            new Sample("SELECT id FROM users -- 尾部行注释", null),
            // 分号坐在注释前面：原文 endsWith(";") 看不见它，包进外层 SELECT 之前必须剥掉。
            new Sample("SELECT id FROM users; -- 尾部行注释", null),

            // —— 判成不可改的：拒绝理由逐档钉住，且不许误挂重取钩子之外的行为 ——
            new Sample("", SqlQueryShape.Block.EMPTY),
            new Sample("   ", SqlQueryShape.Block.EMPTY),
            new Sample("SELECT 1; SELECT 2", SqlQueryShape.Block.NOT_ONE_STATEMENT),
            new Sample("INSERT INTO users VALUES (1)", SqlQueryShape.Block.NOT_A_SELECT),
            // ⚠️ 口径二：这一句 paginator 说可分页，shape 说不许改 —— 单向包含，反向合法。
            new Sample("WITH t AS (SELECT 1 AS n) SELECT * FROM t",
                    SqlQueryShape.Block.COMMON_TABLE_EXPRESSION),
            new Sample("SELECT 1", SqlQueryShape.Block.NO_SOURCE_TABLE),
            new Sample("SELECT * FROM a JOIN b ON a.id = b.id", SqlQueryShape.Block.JOINED),
            new Sample("SELECT * FROM (SELECT 1 AS n) x", SqlQueryShape.Block.DERIVED_TABLE),
            new Sample("SELECT * FROM mydb.dbo.users", SqlQueryShape.Block.OVER_QUALIFIED_NAME),
            new Sample("SELECT * FROM a UNION SELECT * FROM b", SqlQueryShape.Block.SET_OPERATION),
            new Sample("SELECT DISTINCT * FROM users", SqlQueryShape.Block.DISTINCT),
            new Sample("SELECT id, COUNT(*) FROM users GROUP BY id", SqlQueryShape.Block.AGGREGATED),
            new Sample("SELECT id + 1 FROM users", SqlQueryShape.Block.EXPRESSION_COLUMN),
            new Sample("SELECT * FROM users FOR UPDATE", SqlQueryShape.Block.SIDE_CLAUSE),
            new Sample("SELECT * INTO backup FROM users", SqlQueryShape.Block.SIDE_CLAUSE),
            // FROM 后面那个「有 FROM 但读不出表」的写法停在 NO_SOURCE_TABLE，
            // 而「FROM 里跟了一个既不是名字也不是 AS 的东西」才是 UNRECOGNIZED。
            new Sample("SELECT * FROM", SqlQueryShape.Block.NO_SOURCE_TABLE),
            new Sample("SELECT * FROM users AS", SqlQueryShape.Block.UNRECOGNIZED));

    /**
     * 主断言：shape 判可改的集合 ⊆ paginator 判可分页的集合。
     *
     * <p>差集逐条列出（带 shape 的读法与 paginator 的读法），这样红的时候直接能看出
     * 是哪一句、哪一层先撒手 —— 只报「有 3 条不满足」等于让人重新量一遍。
     */
    @Test
    void everyEditableStatementReachesTheRefetchHook() {
        List<String> stranded = new ArrayList<>();
        for (Sample sample : CORPUS) {
            SqlQueryShape.Shape shape = SqlQueryShape.parse(sample.sql());
            boolean paginable = QueryPaginator.isPaginable(sample.sql());
            if (shape instanceof SqlQueryShape.Readable && !paginable) {
                stranded.add("判可改、却没分页可挂重取：" + quote(sample.sql())
                        + " → shape=" + shape + "，isPaginable=false");
            }
        }
        assertThat(stranded)
                .as("界面会说「已保存」而屏幕留旧值 —— 这条语句走不到重取钩子")
                .isEmpty();
    }

    /**
     * 拒绝理由逐档对账：每条样本停在的档必须等于期望，且每个 Block 都有样本走到。
     *
     * <p>这一条守着上面那条断言的**覆盖面**。主断言只遍历 corpus，
     * corpus 少一档它就一起变空 —— 全绿但什么都没量到（[[aidb-mutation-proofs-expose-fixture-blindspots]]
     * 的「遍历口径」那一族）。
     */
    @Test
    void eachRefusalReasonHasItsOwnSample() {
        List<String> mismatches = new ArrayList<>();
        Set<SqlQueryShape.Block> covered = EnumSet.noneOf(SqlQueryShape.Block.class);
        for (Sample sample : CORPUS) {
            SqlQueryShape.Shape shape = SqlQueryShape.parse(sample.sql());
            if (sample.expected() == null) {
                if (!(shape instanceof SqlQueryShape.Readable)) {
                    mismatches.add(quote(sample.sql()) + " 期望判成可改，实得 " + shape);
                }
                continue;
            }
            if (!(shape instanceof SqlQueryShape.Refused refused)) {
                mismatches.add(quote(sample.sql()) + " 期望停在 " + sample.expected()
                        + "，实得 " + shape);
                continue;
            }
            covered.add(refused.reason());
            if (refused.reason() != sample.expected()) {
                mismatches.add(quote(sample.sql()) + " 期望停在 " + sample.expected()
                        + "，实得 " + refused.reason());
            }
        }
        assertThat(mismatches)
                .as("样本的拒绝理由与期望不符 —— 主断言的口径已经漂了")
                .isEmpty();
        assertThat(covered)
                .as("SqlQueryShape.Block 里有整档没样本走到")
                        .containsExactlyInAnyOrderElementsOf(
                                EnumSet.allOf(SqlQueryShape.Block.class));
    }

    private static String quote(String sql) {
        return "「" + sql + "」";
    }
}
