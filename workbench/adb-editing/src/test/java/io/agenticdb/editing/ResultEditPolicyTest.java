package io.agenticdb.editing;

import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.sql.SqlQueryShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这一屏结果能不能按主键改回去」的判定门禁。
 *
 * <p>这一层误判的代价是<b>把 UPDATE 打到不该打的地方</b>，所以每条「拒绝」都要有名字，
 * 每条「放行」都要能被 {@code EditSqlBuilder} 直接用（列名来内省、键来驱动标签）。
 */
class ResultEditPolicyTest {

    private static final List<String> USERS_LABELS = List.of("id", "name", "email");
    private static final List<String> USERS_KEYS = List.of("id", "name", "email");

    private static ColumnDef column(String name, boolean key) {
        return new ColumnDef(name, ColumnType.TEXT, !key, key,
                null, null, null, null, null, null, false);
    }

    private static TableSchema users() {
        return new TableSchema("users",
                List.of(column("id", true), column("name", false), column("email", false)),
                List.of("id"));
    }

    private static ResultEditPolicy.Decision decide(String sql, TableSchema schema,
                                                    List<String> labels, List<String> keys) {
        return ResultEditPolicy.judge(SqlQueryShape.parse(sql), schema, labels, keys);
    }

    private static String blockedKey(String sql, TableSchema schema,
                                     List<String> labels, List<String> keys) {
        ResultEditPolicy.Decision decision = decide(sql, schema, labels, keys);
        assertThat(decision).isInstanceOf(ResultEditPolicy.Blocked.class);
        return ((ResultEditPolicy.Blocked) decision).messageKey();
    }

    @Test
    void singleTableStarSelectIsEditable() {
        ResultEditPolicy.Decision decision =
                decide("SELECT * FROM users", users(), USERS_LABELS, USERS_KEYS);

        assertThat(decision).isInstanceOf(ResultEditPolicy.Editable.class);
        ResultEditPolicy.Editable editable = (ResultEditPolicy.Editable) decision;
        assertThat(editable.table()).isEqualTo("users");
        assertThat(editable.cells()).extracting(ResultEditPolicy.Cell::column)
                .containsExactly("id", "name", "email");
        assertThat(editable.keyCells()).extracting(ResultEditPolicy.Cell::resultKey)
                .containsExactly("id");
        // 写回用的是**表侧**的列定义（类型 / 可空），不是结果集里那份驱动报出来的。
        assertThat(editable.cell("email").definition().nullable()).isTrue();
    }

    @Test
    void namedColumnsMapThroughTheirAlias() {
        ResultEditPolicy.Decision decision = decide(
                "SELECT name AS label, id FROM users", users(),
                List.of("label", "id"), List.of("label", "id"));

        assertThat(decision).isInstanceOf(ResultEditPolicy.Editable.class);
        assertThat(((ResultEditPolicy.Editable) decision).cells())
                .extracting(ResultEditPolicy.Cell::resultKey, ResultEditPolicy.Cell::column)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("label", "name"),
                        org.assertj.core.groups.Tuple.tuple("id", "id"));
    }

    @Test
    void twoCellsSharingOneTableColumnAreAmbiguous() {
        // 驱动重名列会被加后缀（id / id_2），看着是两格，其实指向同一列 —— 改哪一格？
        assertThat(blockedKey("SELECT id, id FROM users", users(),
                List.of("id", "id"), List.of("id", "id_2")))
                .isEqualTo("query.edit.blocked.ambiguousLabel");
    }

    @Test
    void refusesWhenThePrimaryKeyIsNotInTheResult() {
        assertThat(blockedKey("SELECT name, email FROM users", users(),
                List.of("name", "email"), List.of("name", "email")))
                .isEqualTo("query.edit.blocked.primaryKeyNotSelected");
    }

    @Test
    void refusesWhenTheTableHasNoPrimaryKey() {
        TableSchema keyless = new TableSchema("users",
                List.of(column("id", false), column("name", false)));
        assertThat(blockedKey("SELECT * FROM users", keyless, USERS_LABELS, USERS_KEYS))
                .isEqualTo("query.edit.blocked.noPrimaryKey");
    }

    @Test
    void refusesQualifiedTableNames() {
        // 限定的那一段到底是 catalog 还是 schema，各家不同；describe 读的是当前库，
        // 于是"以为是同一张、其实不是"这个代价不能替用户赌。
        assertThat(blockedKey("SELECT * FROM other.users", users(), USERS_LABELS, USERS_KEYS))
                .isEqualTo("query.edit.blocked.otherSchema");
    }

    @Test
    void refusesWhenTheInnerTableCannotBeRead() {
        assertThat(blockedKey("SELECT * FROM users",
                new TableSchema("users", List.of()), USERS_LABELS, USERS_KEYS))
                .isEqualTo("query.edit.blocked.tableUnreadable");
        // describe 拿回来的不是查询里那张表 —— 名字对不上就停。
        assertThat(blockedKey("SELECT * FROM users",
                new TableSchema("orders", List.of(column("id", true))),
                List.of("id"), List.of("id")))
                .isEqualTo("query.edit.blocked.tableUnreadable");
    }

    @Test
    void refusesWhenAProjectedColumnIsNotInTheTable() {
        assertThat(blockedKey("SELECT phantom FROM users", users(),
                List.of("phantom"), List.of("phantom")))
                .isEqualTo("query.edit.blocked.columnNotInTable");
    }

    @Test
    void refusesMixedStarProjections() {
        assertThat(blockedKey("SELECT *, id FROM users", users(),
                List.of("id", "name", "email", "id"), List.of("id", "name", "email", "id_2")))
                .isEqualTo("query.edit.blocked.mixedProjection");
    }

    @Test
    void passesTheShapeLayersReasonThroughUntouched() {
        // 形状层拦下的，键跟着形状层走 —— 一条 JOIN 和一条没有主键的表要说两句不同的话。
        assertThat(blockedKey("SELECT * FROM users JOIN orders ON users.id = orders.id",
                users(), List.of("id"), List.of("id")))
                .isEqualTo("query.edit.blocked.joined");
        assertThat(blockedKey("SELECT COUNT(*) FROM users", users(),
                List.of("c"), List.of("c")))
                .isEqualTo("query.edit.blocked.expressionColumn");
    }

    @Test
    void upperCaseDriverLabelsStillMap() {
        // Oracle 把标签报成 ID / NAME；对不上大小写就拒，等于这一家永远不能改。
        assertThat(decide("SELECT * FROM users", users(),
                List.of("ID", "NAME", "EMAIL"), List.of("ID", "NAME", "EMAIL")))
                .isInstanceOf(ResultEditPolicy.Editable.class);
    }

    @Test
    void aRefusedShapeDoesNotNeedASchema() {
        // UI 在非 FX 线程读内省；形状层已经拒掉的，不该因为 schema 还没到而抛出去。
        assertThat(ResultEditPolicy.judge(
                SqlQueryShape.parse("UPDATE users SET name = 'x'"), null, List.of(), List.of()))
                .isInstanceOf(ResultEditPolicy.Blocked.class);
    }

    @Test
    void everyRefusalHasItsOwnKey() {
        List<String> keys = new java.util.ArrayList<>();
        for (ResultEditPolicy.Block block : ResultEditPolicy.Block.values()) {
            keys.add(block.messageKey());
        }
        for (SqlQueryShape.Block block : SqlQueryShape.Block.values()) {
            keys.add(block.messageKey());
        }
        assertThat(keys).doesNotHaveDuplicates().allSatisfy(key ->
                assertThat(key).startsWith("query.edit.blocked."));
        // 卡点数量掉下来说明有人合并了两条 —— 合并就等于有一类原因没人说。
        assertThat(ResultEditPolicy.Block.values()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(SqlQueryShape.Block.values()).hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    void mismatchedLabelAndKeyListsAreAnError() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                decide("SELECT * FROM users", users(), USERS_LABELS, List.of("id")));
    }

    // ─────────────────────────── 行身份（第 14 条契约的前置） ───────────────────────────

    @Test
    void identityIsKeyedByTheTableColumnAndReadThroughTheAlias() {
        // 主键被改名投影：结果行的键是 row_id，表里的列还是 id。
        ResultEditPolicy.Editable editable = (ResultEditPolicy.Editable) decide(
                "SELECT id AS row_id, name AS label FROM users", users(),
                List.of("row_id", "label"), List.of("row_id", "label"));

        RowIdentity identity = ResultEditPolicy.identity(editable,
                java.util.Map.<String, Object>of("row_id", 7, "label", "张三"));

        // 键必须是<b>表里的</b>名字：EditSqlBuilder 按 schema.primaryKeys() 的原文取 WHERE 值。
        assertThat(identity.values()).containsOnlyKeys("id");
        assertThat(identity.values()).containsEntry("id", 7);
        assertThat(identity.display()).isEqualTo("id=7");
    }

    @Test
    void identityIsAbsentWhenTheRowHasNoValueForAKeyCell() {
        ResultEditPolicy.Editable editable = (ResultEditPolicy.Editable) decide(
                "SELECT id, name FROM users", users(),
                List.of("id", "name"), List.of("id", "name"));

        // 行 Map 少一个主键 = 这一屏和判定落地时那份结果集已经不是同一份，给 null 而不是猜。
        assertThat(ResultEditPolicy.identity(editable, java.util.Map.<String, Object>of("name", "x")))
                .isNull();
        assertThat(ResultEditPolicy.identity(editable, null)).isNull();
        assertThat(ResultEditPolicy.identity(null, java.util.Map.<String, Object>of("id", 1)))
                .isNull();
    }
}
