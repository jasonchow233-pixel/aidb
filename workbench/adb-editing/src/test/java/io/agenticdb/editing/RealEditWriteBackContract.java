package io.agenticdb.editing;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.ExternalDatabase;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.Danger;
import io.agenticdb.sql.SqlQueryShape;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R53 · 真库契约第 10 条：<b>格子改完，落库落的到底是什么</b>。
 *
 * <p>这条路是 Navicat 用得最多的一条：双击一格 → 改 → 保存 →  UPDATE/INSERT/DELETE 按主键打回去。
 * 产品侧的实现只有三个入口，全部在 {@link EditSqlBuilder}：{@code plan} / {@code inversePlan} /
 * {@code execute}，界面（{@code DataGridPage.saveChanges}）就是按
 * 「先 plan、再 inversePlan、然后 execute(plan)，撤销时 execute(inverse)」这个顺序走的 ——
 * <b>本契约刻意按同一个顺序调同三个方法</b>，不另写一条平行路径。
 *
 * <p>⚠️ 为什么此前它是「零真库证据」：{@code adb-editing} 此前只有两个测试类
 * （{@code EditBufferTest} / {@code EditSqlBuilderTest}），全部跑在内存替身上；
 * 生成的 SQL 形状钉得住，<b>那条 SQL 打到真服务器上落成什么</b>从来没量过 ——
 * 中文会不会变问号、{@code 10.50} 会不会变 {@code 10.5}、布尔在 BIT / TINYINT(1) /
 * {@code NUMBER(1)} 三种形状下各回成什么 Java 类型、清空一格到底写的是
 * {@code ''} 还是 {@code NULL}，这些都不是单测能回答的。
 *
 * <p>⚠️ 记一条 D-38 的教训（见 {@code RealDatabaseContract} 的视图契约）：
 * 契约不许写「产品那条路失败了我就自己补一遍」的兜底 —— 那样契约和界面走的是两条路，
 * 五家全绿也照样抓不到缺陷。这里所有写操作只经 {@link EditSqlBuilder}，
 * 读回只经 {@code JdbcSession}（也就是界面渲染格子用的那同一个映射口）。
 *
 * <p>⚠️ 再记一条<b>本轮自己踩的、方向相反的</b>教训（第 11 条那一节写得更细）：
 * 契约也不许<b>猜</b>界面交出来的形状。第一版按「界面把清空折成空串」直接往
 * {@code DECIMAL} 列绑 {@code ""}，端回来四个服务端拒绝加一个静默折 0，
 * 而产品根本不走那个形状。现在界面和这里都调
 * {@link EditValues#emptiedCell}（判档依据是 {@code ColumnType.textual()}），
 * 规则只有一个出处，猜的空间没有了。
 */
final class RealEditWriteBackContract {

    private RealEditWriteBackContract() {
    }

    /** 契约体允许抛异常。 */
    @FunctionalInterface
    interface Body {
        void run(ExternalDatabase target) throws Exception;
    }

    // ─────────────────────── 契约 10：写回往返（增 / 改 / 撤销 / 删） ───────────────────────

    static void writeBackRoundTrip(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_wb_" + System.nanoTime();
            try {
                createEditableTable(session, dialect, target.kind(), table);

                // ① 架构由<b>内省</b>读回来 —— 界面就是拿这个决定「这张表能不能编辑」的。
                //    主键读不到 → editable() 为 false → 整条写回路径在真库上根本不会发生，
                //    所以这一步本身就是证据（不是给后面几步做铺垫的样板代码）。
                TableSchema schema = new SchemaService().describe(session, table);
                expectCatalogNames(schema, dialect);
                assertThat(schema.primaryKeys())
                        .as("%s：主键要从目录里读得回来（读不到界面就把这张表判成不可编辑）",
                                dialect.displayName())
                        .containsExactly("id");
                assertThat(schema.editable())
                        .as("%s：有主键的表要判成可编辑", dialect.displayName())
                        .isTrue();
                assertThat(columnType(schema, "amount"))
                        .as("%s：DECIMAL/NUMBER 列要归成 NUMBER（值形状按它走）",
                                dialect.displayName())
                        .isEqualTo(ColumnType.NUMBER);
                // ⭐ 布尔这一档<b>四家归 BOOLEAN、Oracle 归 NUMBER</b>，两边都是量出来的事实：
                //    Oracle 没有可用的布尔建表类型，NUMBER(1) 判成数字是它自己的形状 ——
                //    那一格在 Oracle 上用数字编辑，不是我们漏了个勾选框。
                assertThat(columnType(schema, "active"))
                        .as("%s：布尔列的形状（MySQL TINYINT(1) / SQLServer BIT / PG boolean / "
                                + "Oracle NUMBER(1)）里，只有 Oracle 没有布尔档",
                                dialect.displayName())
                        .isEqualTo(target.kind() == DialectKind.ORACLE
                                ? ColumnType.NUMBER
                                : ColumnType.BOOLEAN);

                // ② 新增两行：走 plan/execute，值用<b>界面真正会交出来的那些 Java 形状</b>
                //    （CellValueParser 的产物：整数是 Long、小数是 Double、文本是 String、
                //    布尔是 Boolean、空是 null）—— 不是随手挑的「好看的」类型。
                EditBuffer inserts = new EditBuffer(table);
                inserts.addDraft(row(schema, 1L, "张三", 10.50d, true, "首单"));
                inserts.addDraft(row(schema, 2L, "Al Smith", 20.25d, false, null));
                EditSqlBuilder.Plan insertPlan = EditSqlBuilder.plan(session, schema, inserts);
                assertThat(insertPlan.size())
                        .as("%s：两行草稿要翻成两条 INSERT", dialect.displayName())
                        .isEqualTo(2);
                assertThat(EditSqlBuilder.execute(session, insertPlan))
                        .as("%s：两条 INSERT 都要落到库上", dialect.displayName())
                        .isEqualTo(2);

                Map<String, Object> first = readRow(session, dialect, table, 1L);
                assertThat(asText(first.get("name")))
                        .as("%s：中文要存得进去也读得回来（字符集/代码页不对会变 ?? 或 ???）",
                                dialect.displayName())
                        .isEqualTo("张三");
                assertThat(asDecimal(first.get("amount")))
                        .as("%s：10.50 存进去要还是 10.50（标度被吃掉会变 10 或 11）",
                                dialect.displayName())
                        .isEqualByComparingTo("10.50");
                assertThat(asBool(first.get("active")))
                        .as("%s：true 要读回成真（各家驱动把布尔映射成不同 Java 类型，见 asBool）",
                                dialect.displayName())
                        .isTrue();
                assertThat(readRow(session, dialect, table, 2L).get("note"))
                        .as("%s：交 null 的那一格就必须真是 NULL，而不是被折成空串",
                                dialect.displayName())
                        .isNull();
                assertThat(asText(readRow(session, dialect, table, 2L).get("name")))
                        .as("%s：两行都得落库，第二行不能丢", dialect.displayName())
                        .isEqualTo("Al Smith");

                // ③ 改两格 → UPDATE 只碰这两列，WHERE 按主键命中第一行。
                EditBuffer edits = new EditBuffer(table);
                RowIdentity identity = RowIdentity.from(schema.primaryKeys(), first);
                edits.setCell(identity, "name", first.get("name"), "李四");
                edits.setCell(identity, "amount", first.get("amount"), 99.99d);
                EditSqlBuilder.Plan updatePlan = EditSqlBuilder.plan(session, schema, edits);
                assertThat(updatePlan.statements())
                        .as("%s：同一行两格改动是<b>一条</b> UPDATE，不是两条", dialect.displayName())
                        .hasSize(1);
                assertThat(EditSqlBuilder.execute(session, updatePlan))
                        .as("%s：UPDATE 要命中那一行", dialect.displayName())
                        .isEqualTo(1);
                Map<String, Object> updated = readRow(session, dialect, table, 1L);
                assertThat(asText(updated.get("name")))
                        .as("%s：改过的文本格要落库", dialect.displayName())
                        .isEqualTo("李四");
                assertThat(asDecimal(updated.get("amount")))
                        .as("%s：改过的小数格要落库（99.99 不能被四舍五入成 100）",
                                dialect.displayName())
                        .isEqualByComparingTo("99.99");
                // ⭐ 这一条是「没被顺手改写」的证据：UPDATE 只带改过的两列，
                //    另外两列必须还是原值。若哪天 SET 子句漏了「只碰改动列」，这里会红。
                assertThat(asBool(updated.get("active")))
                        .as("%s：没动的布尔列不能被这次 UPDATE 改掉", dialect.displayName())
                        .isTrue();
                assertThat(asText(updated.get("note")))
                        .as("%s：没动的文本列不能被这次 UPDATE 清成空", dialect.displayName())
                        .isEqualTo("首单");
                assertThat(asText(readRow(session, dialect, table, 2L).get("name")))
                        .as("%s：WHERE 只能命中主键那一行，第二行不能跟着变", dialect.displayName())
                        .isEqualTo("Al Smith");

                // ④ 「撤销这次保存」：界面在 execute 正向计划之后把 inversePlan 存下来，
                //    用户点撤销时再 execute 它。这里按同一条顺序走。
                EditSqlBuilder.Plan inverse = EditSqlBuilder.inversePlan(session, schema, edits);
                assertThat(inverse.complete())
                        .as("%s：纯改格子的撤销计划必须是完整的（不完整界面就不给撤销）",
                                dialect.displayName())
                        .isTrue();
                assertThat(EditSqlBuilder.execute(session, inverse))
                        .as("%s：撤销要真打回库上", dialect.displayName())
                        .isEqualTo(1);
                Map<String, Object> restored = readRow(session, dialect, table, 1L);
                assertThat(asText(restored.get("name")))
                        .as("%s：撤销之后文本格要回到改之前的值", dialect.displayName())
                        .isEqualTo("张三");
                assertThat(asDecimal(restored.get("amount")))
                        .as("%s：撤销之后小数格要回到 10.50", dialect.displayName())
                        .isEqualByComparingTo("10.50");

                // ⑤ 删一行 → DELETE 按主键命中，另一行还在。
                EditBuffer deletes = new EditBuffer(table);
                deletes.deleteRow(RowIdentity.from(schema.primaryKeys(), restored), restored);
                EditSqlBuilder.Plan deletePlan = EditSqlBuilder.plan(session, schema, deletes);
                assertThat(deletePlan.statements())
                        .as("%s：删一行要翻成一条 DELETE", dialect.displayName())
                        .hasSize(1);
                assertThat(EditSqlBuilder.execute(session, deletePlan))
                        .as("%s：DELETE 要命中那一行", dialect.displayName())
                        .isEqualTo(1);
                assertThat(countRows(session, dialect, table))
                        .as("%s：删掉的行真没了", dialect.displayName())
                        .isEqualTo(1L);
                assertThat(asText(readRow(session, dialect, table, 2L).get("name")))
                        .as("%s：另一行不能跟着消失", dialect.displayName())
                        .isEqualTo("Al Smith");
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    // ───────────────────── 契约 11：清空一格到底写的是什么 ─────────────────────

    /**
     * 「双击一格、把内容删干净、点保存」到底在服务器上落成什么。
     *
     * <p>⚠️ 这一条<b>本来是本契约最假的一条</b>，记下来免得再犯：第一版按
     * 「界面交的是空串」写，直接往 {@code DECIMAL} / 布尔列里绑 {@code ""} ——
     * 结果 MySQL / MariaDB / PG / Oracle 四家整批保存失败
     * （{@code Incorrect decimal value: ''}、{@code column "amount" is of type numeric
     * but expression is of type character varying}），SQL Server 的 {@code BIT}
     * 更糟：{@code ''} <b>静默折成 0</b>，不报错。看起来像四五个产品缺陷，
     * 实际上<b>产品根本不交这个形状</b>：{@code DataGridPage.parseCellValue} 把
     * 非文本档的空格折成 {@code null}，界面另有「设为 NULL / 设为空串」两个各自带门禁的动作。
     * 契约猜界面，就量出了界面上不存在的东西。
     *
     * <p>所以规则现在只有一个出处：{@link EditValues#emptiedCell}，界面和这里调同一个方法。
     * 钉住的结论也换成硬的 —— 不再用「{@code null} / 0 / {@code 0.00} / 报错 都算过」那种
     * 四个档位任挑一个的假门禁：
     * <ul>
     *   <li>数字、布尔列清空 → 五家<b>全部</b>落成真 NULL；</li>
     *   <li>文本列清空 → 落成空串（Oracle 把 {@code ''} 当 NULL 存，是它自己的形状）。</li>
     * </ul>
     */
    static void emptyCellShapeIsWhatTheServerSays(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_we_" + System.nanoTime();
            try {
                createEditableTable(session, dialect, target.kind(), table);
                TableSchema schema = new SchemaService().describe(session, table);
                expectCatalogNames(schema, dialect);

                // 先落一行「什么都不是空」的数据，再逐列清空。
                EditBuffer seed = new EditBuffer(table);
                seed.addDraft(row(schema, 7L, "王五", 5.00d, true, "备注"));
                assertThat(EditSqlBuilder.execute(
                        session, EditSqlBuilder.plan(session, schema, seed)))
                        .as("%s：种子行要落到库上（落不上后面全是空转）", dialect.displayName())
                        .isEqualTo(1);

                Map<String, Object> seeded = readRow(session, dialect, table, 7L);
                RowIdentity identity = RowIdentity.from(schema.primaryKeys(), seeded);

                // ① 文本列清空：界面交空串，读回来就得是空串。
                Object textShape = EditValues.emptiedCell(column(schema, "note"));
                assertThat(textShape)
                        .as("%s：文本档的「清空」交的是空串，不是 NULL（两种空在网格里分开显示）",
                                dialect.displayName())
                        .isEqualTo("");
                assertThat(EditSqlBuilder.execute(session, EditSqlBuilder.plan(session, schema,
                        cleared(schema, table, identity, seeded, "note", textShape))))
                        .as("%s：文本列清空要真的打出一条 UPDATE 并命中", dialect.displayName())
                        .isEqualTo(1);
                Object clearedNote = readRow(session, dialect, table, 7L).get("note");
                if (target.kind() == DialectKind.ORACLE) {
                    assertThat(clearedNote)
                            .as("Oracle 把空串当 NULL 存（它自己的形状，不是我们的缺陷）")
                            .isNull();
                } else {
                    assertThat(clearedNote)
                            .as("%s：文本列清空之后读回来是空串（不是被偷偷换成 NULL）",
                                    dialect.displayName())
                            .isEqualTo("");
                }

                // ② 数字列清空：非文本档 → null → 五家都必须是真 NULL。
                Map<String, Object> afterText = readRow(session, dialect, table, 7L);
                RowIdentity afterTextIdentity = RowIdentity.from(schema.primaryKeys(), afterText);
                Object numberShape = EditValues.emptiedCell(column(schema, "amount"));
                assertThat(numberShape)
                        .as("%s：DECIMAL/NUMBER 档没有「空串」这个值，清空要交 null "
                                + "（交 \"\" 的话四家服务端拒、SQLServer 静默折成 0）",
                                dialect.displayName())
                        .isNull();
                assertThat(EditSqlBuilder.execute(session, EditSqlBuilder.plan(session, schema,
                        cleared(schema, table, afterTextIdentity, afterText, "amount",
                                numberShape))))
                        .as("%s：数字列清空要落成一条 UPDATE，不能整批失败", dialect.displayName())
                        .isEqualTo(1);
                assertThat(readRow(session, dialect, table, 7L).get("amount"))
                        .as("%s：数字列清空之后读回来必须真是 NULL —— 折成 0 是改数，不是清空",
                                dialect.displayName())
                        .isNull();

                // ③ 布尔列清空：同上。SQL Server 的 BIT 是这一条最容易被写坏的一档。
                Map<String, Object> afterNumber = readRow(session, dialect, table, 7L);
                RowIdentity afterNumberIdentity = RowIdentity.from(schema.primaryKeys(), afterNumber);
                Object boolShape = EditValues.emptiedCell(column(schema, "active"));
                assertThat(boolShape)
                        .as("%s：布尔档（Oracle 那格是 NUMBER(1)，界面按数字档走）清空交 null",
                                dialect.displayName())
                        .isNull();
                assertThat(EditSqlBuilder.execute(session, EditSqlBuilder.plan(session, schema,
                        cleared(schema, table, afterNumberIdentity, afterNumber, "active",
                                boolShape))))
                        .as("%s：布尔列清空要落成一条 UPDATE（SQLServer 把 \"\" 折成 0 就是这一步走错了形状）",
                                dialect.displayName())
                        .isEqualTo(1);
                assertThat(readRow(session, dialect, table, 7L).get("active"))
                        .as("%s：布尔列清空之后读回来必须真是 NULL，不能是 0 / false",
                                dialect.displayName())
                        .isNull();
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    /** 把某一格清成 {@code next}（{@code next} 由 {@link EditValues#emptiedCell} 量出来，不是手写）。 */
    private static EditBuffer cleared(TableSchema schema, String table, RowIdentity identity,
                                     Map<String, Object> rowValues, String column, Object next) {
        String name = column(schema, column).name();
        EditBuffer buffer = new EditBuffer(table);
        buffer.setCell(identity, name, rowValues.get(name), next);
        return buffer;
    }

    // ─────────── 契约 14：从用户敲的那条 SQL 文本，判到可编辑，再改回表里 ───────────

    /**
     * ⭐ R58-d · 真库契约第 14 条：<b>结果区那条回写路（R58-c）在五家真库上走一遍</b>。
     *
     * <p>前 13 条量的是<b>数据页</b>那条路：那张页天生知道自己在哪张表上，主键名直接来自内省。
     * 结果区不一样 —— 它的输入只有<b>用户敲的那句 SQL 文本</b>和<b>驱动报回来的列标签</b>，
     * 于是多了三道数据页没有的关口：
     * <ol>
     *   <li>{@link SqlQueryShape} 认不认得出这是单表 {@code SELECT}；</li>
     *   <li>{@link ResultEditPolicy#judge} 能不能把<b>别名标签</b>映射回表里的列，
     *       并确认主键在结果里；</li>
     *   <li>{@link ResultEditPolicy#identity} 造出来的行身份，键名必须是<b>表里的</b>那一套
     *       —— 否则 {@link EditSqlBuilder} 按 {@code schema.primaryKeys()} 取 WHERE 值会取到
     *       {@code null}，SQL 照样发出去、返回 0 行命中，界面却报「已保存」。</li>
     * </ol>
     *
     * <p>⚠️ 第 3 关是<b>本契约存在的理由</b>：SQLite 那 45 条门禁全绿也抓不到它，因为那批夹具
     * 写的是 {@code SELECT *}，结果列键恰好等于表列名，两种写法在内存库里行为一模一样。
     * 只有把别名放进来（这里主键和文本列<b>都</b>起别名），错键才会显形 —— 而且它必须打在真库上，
     * 「0 行命中」这件事只有服务器能回答。
     *
     * <p>🔒 全程只走产品的口：判定用 {@link SqlQueryShape#parse} + {@link SchemaService#describe}
     * + {@link ResultEditPolicy#judge}（跟 {@code QueryPage.judgeEditability} 同一个顺序、同一批方法），
     * 取结果用 {@code session.execute(sql, Danger.READ_ONLY)}（界面执行按钮那个带三级门禁的重载），
     * 写回用 {@link EditSqlBuilder}（数据页同一个）。<b>没有一条平行路径</b>（D-38）。
     */
    static void resultGridWriteBackJudgedFromTheSqlText(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_rg_" + System.nanoTime();
            try {
                createEditableTable(session, dialect, target.kind(), table);
                TableSchema schema = new SchemaService().describe(session, table);
                expectCatalogNames(schema, dialect);

                EditBuffer seed = new EditBuffer(table);
                seed.addDraft(row(schema, 1L, "张三", 10.50d, true, "首单"));
                seed.addDraft(row(schema, 2L, "Al Smith", 20.25d, false, null));
                assertThat(EditSqlBuilder.execute(session, EditSqlBuilder.plan(session, schema, seed)))
                        .as("%s：两行种子要落库（结果区没行可查，后面全是空转）", dialect.displayName())
                        .isEqualTo(2);

                // ① 用户敲的那条 SQL：<b>主键与文本列都带别名</b>。引号形状按方言给
                //    （MySQL 系不认双引号标识符，ANSI_QUOTES 又不是默认 —— 写死 "id" 会选成字符串字面量）。
                String sql = "SELECT " + dialect.quote("id") + " AS " + dialect.quote("row_id")
                        + ", " + dialect.quote("name") + " AS " + dialect.quote("who")
                        + " FROM " + dialect.quote(table);
                ExecutionResult result = session.execute(sql, Danger.READ_ONLY);
                assertThat(result.rowCount())
                        .as("%s：这条 SELECT 要读回两行", dialect.displayName())
                        .isEqualTo(2);
                assertThat(result.columnKeys())
                        .as("%s：行 Map 的键就是驱动报回来的<b>别名</b>（键名一旦变，判定与身份全错）",
                                dialect.displayName())
                        .containsExactly("row_id", "who");

                // ② 判定：形状层（纯文本）→ 内省 → judge。labels 与 keys 都取自上面那个真结果集。
                ResultEditPolicy.Decision decision = ResultEditPolicy.judge(
                        SqlQueryShape.parse(sql), schema,
                        result.columns().stream().map(ColumnDef::name).toList(),
                        result.columnKeys());
                assertThat(decision)
                        .as("%s：别名投影要判成可编辑（判成只读 = 结果区这条路在五家全废）。卡点=%s",
                                dialect.displayName(), reason(decision))
                        .isInstanceOf(ResultEditPolicy.Editable.class);
                ResultEditPolicy.Editable editable = (ResultEditPolicy.Editable) decision;
                assertThat(editable.table())
                        .as("%s：写回的表名要来自内省，且就是查询那张表", dialect.displayName())
                        .isEqualTo(table);
                // ⭐ 别名标签 ↔ 表列名：这一对错了，SET 子句会写到另一列上。
                assertThat(editable.cells())
                        .as("%s：结果列键是别名，表侧目标列必须是 id / name", dialect.displayName())
                        .extracting(ResultEditPolicy.Cell::resultKey, ResultEditPolicy.Cell::column)
                        .containsExactly(
                                org.assertj.core.groups.Tuple.tuple("row_id", "id"),
                                org.assertj.core.groups.Tuple.tuple("who", "name"));

                // ③ 行身份：这是本契约要量的那一关 —— 值从别名底下取，<b>键名用表里的</b>。
                Map<String, Object> first = result.rows().stream()
                        .filter(r -> "张三".equals(asText(r.get("who"))))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("结果里没有那一行（读回来的值："
                                + result.rows() + "）—— 中文名在结果集里丢了，先查字符集"));
                RowIdentity identity = ResultEditPolicy.identity(editable, first);
                assertThat(identity)
                        .as("%s：主键在结果里，行身份必须造得出来（造不出来 = 这一屏判成可编辑却一格都改不了）",
                                dialect.displayName())
                        .isNotNull();
                assertThat(identity.values())
                        .as("%s：行身份的键名用<b>表里的</b> id，不是别名 row_id —— 用别名时 "
                                + "EditSqlBuilder 按主键原文取值会取到 null，UPDATE 一条也不命中",
                                dialect.displayName())
                        .containsOnlyKeys("id");

                EditBuffer edits = new EditBuffer(table);
                ResultEditPolicy.Cell nameCell = editable.cell("who");
                assertThat(edits.setCell(identity, nameCell.column(), first.get("who"), "李四"))
                        .as("%s：改一格要进得了缓冲（进不去，后面 plan 出来是空计划）",
                                dialect.displayName())
                        .isTrue();
                EditSqlBuilder.Plan plan = EditSqlBuilder.plan(session, schema, edits);
                assertThat(plan.statements())
                        .as("%s：一屏改一格 = 一条 UPDATE", dialect.displayName())
                        .hasSize(1);
                assertThat(EditSqlBuilder.execute(session, plan))
                        .as("%s：这条 UPDATE 要<b>命中那一行</b>（回 0 = 写出去了但没落，界面那句「已保存」就是假话）",
                                dialect.displayName())
                        .isEqualTo(1);

                long id = ((Number) identity.values().get("id")).longValue();
                Map<String, Object> back = readRow(session, dialect, table, id);
                assertThat(asText(back.get("name")))
                        .as("%s：改过的那一格要落到库上", dialect.displayName())
                        .isEqualTo("李四");
                // 没动的列不能被这次 UPDATE 带走。
                assertThat(asDecimal(back.get("amount")))
                        .as("%s：UPDATE 只带 name 一列，金额不能被顺手改写", dialect.displayName())
                        .isEqualByComparingTo("10.50");
                assertThat(asBool(back.get("active")))
                        .as("%s：没动的布尔列保持原值", dialect.displayName())
                        .isTrue();
                assertThat(asText(readRow(session, dialect, table, 2L).get("name")))
                        .as("%s：WHERE 只命中主键那一行，另一行不能跟着变", dialect.displayName())
                        .isEqualTo("Al Smith");

                // ④ 撤销这次保存（界面的第二个按钮，走的是同一个 inversePlan）。
                EditSqlBuilder.Plan inverse = EditSqlBuilder.inversePlan(session, schema, edits);
                assertThat(inverse.complete())
                        .as("%s：改格子的撤销计划必须完整", dialect.displayName())
                        .isTrue();
                assertThat(EditSqlBuilder.execute(session, inverse))
                        .as("%s：撤销同样要按行身份命中那一行", dialect.displayName())
                        .isEqualTo(1);
                assertThat(asText(readRow(session, dialect, table, id).get("name")))
                        .as("%s：撤销之后要回到改之前的值", dialect.displayName())
                        .isEqualTo("张三");

                // ⑤ 反向：主键没投影出来 → 必须判成只读，且说的是那一句（不许偷偷改写用户的 SQL 去补主键）。
                String keyless = "SELECT " + dialect.quote("name") + " AS " + dialect.quote("who")
                        + " FROM " + dialect.quote(table);
                ExecutionResult keylessResult = session.execute(keyless, Danger.READ_ONLY);
                ResultEditPolicy.Decision blocked = ResultEditPolicy.judge(
                        SqlQueryShape.parse(keyless), schema,
                        keylessResult.columns().stream().map(ColumnDef::name).toList(),
                        keylessResult.columnKeys());
                assertThat(blocked)
                        .as("%s：主键不在结果里要判成只读", dialect.displayName())
                        .isInstanceOf(ResultEditPolicy.Blocked.class);
                assertThat(((ResultEditPolicy.Blocked) blocked).messageKey())
                        .as("%s：这一类原因要说得出是哪一类（界面据此在 Info 带显示人话）",
                                dialect.displayName())
                        .isEqualTo(ResultEditPolicy.Block.PRIMARY_KEY_NOT_SELECTED.messageKey());
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    /** 判成只读时把卡点键捞出来，只用在断言失败消息里（过了不产生噪音）。 */
    private static String reason(ResultEditPolicy.Decision decision) {
        return decision instanceof ResultEditPolicy.Blocked blocked
                ? blocked.messageKey() + " / " + blocked.detail() : "（判成可编辑）";
    }

    // ─────────────────────────────── 夹具与读数 ───────────────────────────────

    /**
     * 建一张<b>有主键、可编辑、列类型覆盖到界面那四档</b>的表：
     * 整数主键 / 文本 / 小数 / 布尔 / 可空文本。
     *
     * <p>⚠️ 列的类型名按方言给（这是<b>夹具</b>，不是被测路径 —— 被测的是 plan/execute）：
     * SQL Server 的文本必须是 {@code NVARCHAR}，否则中文按单字节代码页解释、折成问号，
     * 那是测试自己造出来的假阳性（同 {@code createdTablesAcceptUnicode} 那条口径）。
     */
    private static void createEditableTable(JdbcSession session, Dialect dialect,
                                            DialectKind kind, String table) throws Exception {
        String sql = "CREATE TABLE " + dialect.quote(table) + " ("
                + dialect.quote("id") + " " + idType(kind) + " NOT NULL PRIMARY KEY, "
                + dialect.quote("name") + " " + textType(kind, 50) + " NOT NULL, "
                + dialect.quote("amount") + " " + decimalType(kind) + " NULL, "
                + dialect.quote("active") + " " + boolType(kind) + " NULL, "
                + dialect.quote("note") + " " + textType(kind, 50) + " NULL)";
        String options = dialect.createTableOptions();
        if (options != null && !options.isBlank()) {
            sql += " " + options;
        }
        session.execute(sql);
    }

    /**
     * 列名要按<b>建表时引号里的那个形状</b>原样读回来。
     *
     * <p>这一步不是样板：契约后面全部按 {@code "name"} / {@code "amount"} 这些名字取值，
     * 哪一家把带引号的小写标识符读成大写（或读不到），后面就全是「取到 null」的假象 ——
     * 量不出任何东西。Oracle 在这一格上有过前科（D-22：{@code UPPER(?)} 看不见我们
     * 自己加引号建的对象）。
     */
    private static void expectCatalogNames(TableSchema schema, Dialect dialect) {
        assertThat(schema.columns())
                .as("%s：建表时带引号的小写列名要原样读回来（后面取值全按这个名字）",
                        dialect.displayName())
                .extracting(ColumnDef::name)
                .containsExactly("id", "name", "amount", "active", "note");
    }

    private static String idType(DialectKind kind) {
        return kind == DialectKind.ORACLE ? "NUMBER(19)" : "BIGINT";
    }

    private static String decimalType(DialectKind kind) {
        return kind == DialectKind.ORACLE ? "NUMBER(14,2)" : "DECIMAL(12,2)";
    }

    /**
     * 布尔列：三家各有各的形状，而界面按 {@code ColumnType.BOOLEAN} 统一处理 ——
     * 这正是本契约要验的那个落差，所以这里<b>故意不统一</b>。
     */
    private static String boolType(DialectKind kind) {
        return switch (kind) {
            case MYSQL, MARIADB -> "TINYINT(1)";
            case POSTGRESQL -> "BOOLEAN";
            case SQLSERVER -> "BIT";
            // Oracle 没有可用的 BOOLEAN 建表类型（23ai 有，但驱动/目录那一层的形状另说）。
            case ORACLE, SQLITE -> "NUMBER(1)";
        };
    }

    private static String textType(DialectKind kind, int length) {
        return switch (kind) {
            case MYSQL, MARIADB, POSTGRESQL -> "VARCHAR(" + length + ")";
            case SQLSERVER -> "NVARCHAR(" + length + ")";
            case ORACLE -> "VARCHAR2(" + length + ")";
            case SQLITE -> "TEXT";
        };
    }

    /** 读回一行：走界面渲染格子用的同一个 {@code JdbcSession} 映射口，不自己建 ResultSet。 */
    private static Map<String, Object> readRow(JdbcSession session, Dialect dialect,
                                               String table, long id) throws Exception {
        List<Map<String, Object>> rows = session.execute(
                "SELECT * FROM " + dialect.quote(table) + " WHERE "
                        + dialect.quote("id") + " = " + id).rows();
        assertThat(rows)
                .as("按主键要恰好读到一行（读到 0 行 = 上一步的写根本没落库）")
                .hasSize(1);
        return rows.getFirst();
    }

    private static long countRows(JdbcSession session, Dialect dialect, String table)
            throws Exception {
        Map<String, Object> row = session.execute(
                "SELECT COUNT(*) AS adb_count FROM " + dialect.quote(table)).rows().getFirst();
        return ((Number) row.values().iterator().next()).longValue();
    }

    private static ColumnType columnType(TableSchema schema, String column) {
        return column(schema, column).type();
    }

    /** 按名字取目录里的列定义（大小写不敏感，取到的 {@code name()} 才是目录原形）。 */
    private static ColumnDef column(TableSchema schema, String wanted) {
        for (ColumnDef def : schema.columns()) {
            if (def.name().equalsIgnoreCase(wanted)) {
                return def;
            }
        }
        throw new AssertionError("目录里没有这一列：" + wanted
                + "（读回来的是 " + schema.columns().stream().map(ColumnDef::name).toList() + "）");
    }

    /** 一行草稿：按<b>目录读回来的列顺序</b> positional 交值，键名用目录里的原名。 */
    private static Map<String, Object> row(TableSchema schema, Object... values) {
        List<ColumnDef> columns = schema.columns();
        if (values.length > columns.size()) {
            throw new AssertionError("交的值比列多：" + values.length + " 对 " + columns.size());
        }
        Map<String, Object> byName = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            byName.put(columns.get(i).name(), values[i]);
        }
        return byName;
    }

    // ───────────────────────── 值形状归一（各家驱动不同，不是缺陷） ─────────────────────────

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * 布尔：MySQL 的 {@code TINYINT(1)} 经 Connector/J 可能回 {@code Boolean}、{@code Integer}
     * 或 {@code byte[]}，SQL Server 的 {@code BIT} 回 {@code Boolean}，PG 回 {@code Boolean}，
     * Oracle 的 {@code NUMBER(1)} 回 {@code BigDecimal}。
     *
     * <p>⚠️ 这里做归一是<b>正当</b>的：各家驱动把同一档 SQL 类型映射成不同 Java 类型是事实，
     * 界面渲染时同样要过一遍显示归一；它和 D-38 那种「契约自己补一遍产品的读路径」
     * 不是一回事 —— 值本身仍然来自 {@code JdbcSession} 的映射口，这里只把形状折平。
     */
    private static Boolean asBool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).toLowerCase(java.util.Locale.ROOT);
        return switch (text) {
            case "true", "t", "1", "yes", "y", "on" -> Boolean.TRUE;
            case "false", "f", "0", "no", "n", "off" -> Boolean.FALSE;
            default -> throw new AssertionError("读回来的布尔形状不认识：" + text
                    + "（" + value.getClass().getName() + "）—— 契约要显式认识这一档，别静默折成假");
        };
    }

    private static void dropQuietly(JdbcSession session, Dialect dialect, String table) {
        try {
            session.execute("DROP TABLE " + dialect.quote(table));
        } catch (Exception ignored) {
            // 夹具清理失败不影响结论（真库契约的噪音教训见 D-30：不刷假警报，也不吞断言）。
        }
    }
}
