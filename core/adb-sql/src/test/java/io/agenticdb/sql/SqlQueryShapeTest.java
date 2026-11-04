package io.agenticdb.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这条查询能不能按主键改回去」的识别层门禁。
 *
 * <p>为什么值得单独一层：结果集本身不知道自己来自哪张表
 * （{@code ExecutionResult} / {@code RowPage} 都没有表名字段，列纯粹出自 {@code ResultSetMetaData}），
 * 而回写要的是「哪张表的哪一行、哪些列」。这一层唯一的产出就是那个判定，
 * 判错了后面接的是真 UPDATE，所以**每一条拒绝都要有名字**（界面上的实话靠它）。
 */
class SqlQueryShapeTest {

    private static SqlQueryShape.Shape parse(String sql) {
        return SqlQueryShape.parse(sql);
    }

    private static SqlQueryShape.Select read(String sql) {
        SqlQueryShape.Shape shape = parse(sql);
        assertThat(shape)
                .as("这条应当被认成单表 SELECT，实际：%s", describe(shape))
                .isInstanceOf(SqlQueryShape.Readable.class);
        return ((SqlQueryShape.Readable) shape).select();
    }

    private static SqlQueryShape.Block block(String sql) {
        SqlQueryShape.Shape shape = parse(sql);
        assertThat(shape)
                .as("这条应当被拒，实际认成了：%s", describe(shape))
                .isInstanceOf(SqlQueryShape.Refused.class);
        return ((SqlQueryShape.Refused) shape).reason();
    }

    private static String describe(SqlQueryShape.Shape shape) {
        if (shape instanceof SqlQueryShape.Readable readable) {
            return "可读 " + readable.select();
        }
        SqlQueryShape.Refused refused = (SqlQueryShape.Refused) shape;
        return refused.reason() + " / " + refused.detail();
    }

    // ── 认得出的那一面 ──────────────────────────────────────────────

    @Test
    void recognisesPlainSingleTableProjections() {
        SqlQueryShape.Select all = read("SELECT * FROM users");
        assertThat(all.table()).isEqualTo("users");
        assertThat(all.schema()).isEmpty();
        assertThat(all.alias()).isEmpty();
        assertThat(all.allColumns()).isTrue();
        assertThat(all.columns()).isEmpty();

        SqlQueryShape.Select named = read("SELECT id, name FROM users");
        assertThat(named.columns()).containsExactly(
                new SqlQueryShape.Column("id", "id"),
                new SqlQueryShape.Column("name", "name"));
        assertThat(named.allColumns()).isFalse();
    }

    @Test
    void filtersSortingAndRowLimitsDoNotBreakEditability() {
        // WHERE / ORDER BY / LIMIT 只筛行与排序，行本身还是那张表里带主键的一行。
        for (String sql : List.of(
                "SELECT id, name FROM users WHERE status = 'a' ORDER BY name",
                "SELECT * FROM users LIMIT 20 OFFSET 40",
                "SELECT * FROM users ORDER BY id DESC LIMIT 10")) {
            assertThat(read(sql).table()).as(sql).isEqualTo("users");
        }
    }

    @Test
    void understandsAliasesAndQualifiedNames() {
        SqlQueryShape.Select aliased = read("SELECT u.id, u.name FROM users AS u");
        assertThat(aliased.alias()).isEqualTo("u");
        assertThat(aliased.columns()).containsExactly(
                new SqlQueryShape.Column("id", "id"),
                new SqlQueryShape.Column("name", "name"));

        SqlQueryShape.Select implicitAlias = read("SELECT name n FROM users u");
        assertThat(implicitAlias.alias()).isEqualTo("u");
        assertThat(implicitAlias.columns())
                .containsExactly(new SqlQueryShape.Column("name", "n"));

        SqlQueryShape.Select qualified = read("SELECT * FROM shop.users");
        assertThat(qualified.schema()).isEqualTo("shop");
        assertThat(qualified.table()).isEqualTo("users");

        assertThat(read("SELECT t.* FROM users t").allColumns()).isTrue();
    }

    @Test
    void acceptsEveryQuotingStyleTheLexerKnows() {
        assertThat(read("SELECT * FROM `users`").table()).isEqualTo("users");
        assertThat(read("SELECT * FROM \"users\"").table()).isEqualTo("users");
        assertThat(read("SELECT * FROM [users]").table()).isEqualTo("users");
        // 列名叫 date / time 这类「关键字也是常见列名」的写法，不许被词性误伤。
        SqlQueryShape.Select keywordsAsColumns = read("SELECT date, time FROM events");
        assertThat(keywordsAsColumns.columns()).containsExactly(
                new SqlQueryShape.Column("date", "date"),
                new SqlQueryShape.Column("time", "time"));
    }

    @Test
    void toleratesCommentsAndTrailingSemicolon() {
        // 尾分号是 SqlSplitter 交回来的语句文本的一部分，留着它就判不成表名。
        assertThat(read("SELECT * FROM users;").table()).isEqualTo("users");
        assertThat(read("-- 取全部\nSELECT * /* 注释 */ FROM users").table()).isEqualTo("users");
    }

    @Test
    void whereSubqueriesStillDescribeOneRealTable() {
        // WHERE 里的子查询只筛行，行的身份还是那张表的主键。
        SqlQueryShape.Select select = read(
                "SELECT id FROM users WHERE id IN (SELECT user_id FROM orders)");
        assertThat(select.table()).isEqualTo("users");
        assertThat(select.columns()).containsExactly(new SqlQueryShape.Column("id", "id"));
    }

    // ── 认不出的那一面：每一条都要有自己的名字，界面才有实话可说 ──────

    @Test
    void refusesAnythingThatIsNotOnePlainSelect() {
        assertThat(block("")).isEqualTo(SqlQueryShape.Block.EMPTY);
        assertThat(block("   ")).isEqualTo(SqlQueryShape.Block.EMPTY);
        assertThat(block("UPDATE users SET name = 'a'"))
                .isEqualTo(SqlQueryShape.Block.NOT_A_SELECT);
        assertThat(block("SELECT * FROM users; SELECT 1"))
                .isEqualTo(SqlQueryShape.Block.NOT_ONE_STATEMENT);
        assertThat(block("WITH x AS (SELECT 1) SELECT * FROM x"))
                .isEqualTo(SqlQueryShape.Block.COMMON_TABLE_EXPRESSION);
        assertThat(block("SELECT 1 + 1"))
                .isEqualTo(SqlQueryShape.Block.NO_SOURCE_TABLE);
    }

    @Test
    void refusesRowSetsThatAreNotOneTablesRows() {
        assertThat(block("SELECT * FROM users JOIN orders ON users.id = orders.user_id"))
                .isEqualTo(SqlQueryShape.Block.JOINED);
        assertThat(block("SELECT * FROM users, orders"))
                .isEqualTo(SqlQueryShape.Block.JOINED);
        assertThat(block("SELECT id FROM users UNION SELECT id FROM archive"))
                .isEqualTo(SqlQueryShape.Block.SET_OPERATION);
        assertThat(block("SELECT DISTINCT name FROM users"))
                .isEqualTo(SqlQueryShape.Block.DISTINCT);
        assertThat(block("SELECT status, COUNT(*) FROM users GROUP BY status"))
                .isEqualTo(SqlQueryShape.Block.AGGREGATED);
        assertThat(block("SELECT * FROM users GROUP BY status HAVING COUNT(*) > 1"))
                .isEqualTo(SqlQueryShape.Block.AGGREGATED);
        assertThat(block("SELECT * FROM users FOR UPDATE"))
                .isEqualTo(SqlQueryShape.Block.SIDE_CLAUSE);
        assertThat(block("SELECT a, b INTO backup_table FROM users"))
                .isEqualTo(SqlQueryShape.Block.SIDE_CLAUSE);
        assertThat(block("SELECT * FROM srv.db.users"))
                .isEqualTo(SqlQueryShape.Block.OVER_QUALIFIED_NAME);
    }

    /**
     * ⭐ 两处**假阳性**测试 —— 这一版最初就错在这儿。
     *
     * <p>把括号连同里面的内容一起丢掉看着省事，但 {@code FROM (SELECT …) x} 会被读成
     * 「表 x」，于是对着一张并不存在的表宣布可编辑；{@code COUNT(*)} 会被读成一列，
     * 于是「改这一格」写回的是一个算出来的值。两者都必须在识别层就挡住。
     */
    @Test
    void neverMistakesParenthesisedThingsForTableOrColumn() {
        assertThat(block("SELECT id FROM (SELECT * FROM users) x"))
                .isEqualTo(SqlQueryShape.Block.DERIVED_TABLE);
        assertThat(block("SELECT id, COUNT(*) AS n FROM users GROUP BY id"))
                .isEqualTo(SqlQueryShape.Block.AGGREGATED);
        assertThat(block("SELECT id, COUNT(*) AS n FROM users"))
                .isEqualTo(SqlQueryShape.Block.EXPRESSION_COLUMN);
        assertThat(block("SELECT price * 2 FROM items"))
                .isEqualTo(SqlQueryShape.Block.EXPRESSION_COLUMN);
        assertThat(block("SELECT CASE WHEN a THEN b END FROM t"))
                .isEqualTo(SqlQueryShape.Block.EXPRESSION_COLUMN);
        assertThat(block("SELECT 'x' AS kind FROM t"))
                .isEqualTo(SqlQueryShape.Block.EXPRESSION_COLUMN);
    }

    @Test
    void everyRefusalHasItsOwnMessageKey() {
        // 界面靠这个键说「为什么这条不能改」；两条卡点共用一句就等于没说。
        List<String> keys = java.util.Arrays.stream(SqlQueryShape.Block.values())
                .map(SqlQueryShape.Block::messageKey)
                .toList();
        assertThat(keys).doesNotHaveDuplicates();
        for (String key : keys) {
            assertThat(key).startsWith("query.edit.blocked.");
        }
        assertThat(SqlQueryShape.Block.JOINED.messageKey())
                .isEqualTo("query.edit.blocked.joined");
    }

    @Test
    void refusesWhenTheFromClauseIsUnreadable() {
        assertThat(block("SELECT * FROM"))
                .isEqualTo(SqlQueryShape.Block.NO_SOURCE_TABLE);
        assertThat(block("SELECT * FROM users.orders.items"))
                .isEqualTo(SqlQueryShape.Block.OVER_QUALIFIED_NAME);
    }
}
