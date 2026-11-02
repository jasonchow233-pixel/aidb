package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-3：SQL Server 方言的差异点门禁。
 *
 * <p>重点钉住<b>照抄 LIMIT 族就会错</b>的几处：方括号引用、{@code ;} 参数串、
 * {@code OFFSET … FETCH} 必须跟着 {@code ORDER BY}、派生表里不许出现裸 {@code ORDER BY}。
 *
 * <p>⚠️ 元数据 SQL（sys.* 查询）本轮没有真库（环境里没有 Docker），
 * 这里只钉「拼出来的句子长什么样」，不钉服务端是否接受 —— 见类注释的诚实条款。
 */
class SqlServerDialectTest {

    private final SqlServerDialect dialect = new SqlServerDialect();

    @Test
    void itIsItsOwnKindWithItsOwnDriverAndPort() {
        assertThat(dialect.kind()).isEqualTo(DialectKind.SQLSERVER);
        assertThat(dialect.displayName()).isEqualTo("SQL Server");
        assertThat(dialect.driverClassName()).isEqualTo("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        assertThat(dialect.defaultPort()).isEqualTo(1433);
        assertThat(DialectKind.of("sqlserver")).isEqualTo(DialectKind.SQLSERVER);
        assertThat(dialect.uriAliases()).containsExactly("sqlserver", "mssql");
    }

    @Test
    void identifiersUseBracketsAndEscapeTheClosingBracket() {
        assertThat(dialect.quote("users")).isEqualTo("[users]");
        assertThat(dialect.quote("a]b")).isEqualTo("[a]]b]");
    }

    @Test
    void jdbcUrlCarriesTimeoutsOnTheSemicolonParameterList() {
        String url = dialect.jdbcUrl("db.local", 1433, "shop");
        assertThat(url).startsWith("jdbc:sqlserver://db.local:1433;");
        assertThat(url).contains("loginTimeout=10");
        // ⚠️ loginTimeout 是秒、socketTimeout 是毫秒 —— 驱动参数表实测，两个单位不一样。
        assertThat(url).contains("socketTimeout=30000");
        assertThat(url).contains("encrypt=false");
        assertThat(url).contains("databaseName=shop");
        assertThat(dialect.usesSemicolonParameters()).isTrue();
        assertThat(dialect.databaseOptionKey()).isEqualTo("databaseName");
    }

    @Test
    void pagingAlwaysFollowsAnOrderByBecauseOffsetFetchRequiresOne() {
        assertThat(dialect.pagingClause(20, 40, false))
                .isEqualTo(" ORDER BY (SELECT NULL) OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY");
        assertThat(dialect.pagingClause(20, 40, true))
                .as("原句已经有排序，不再补常量排序")
                .isEqualTo(" OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY");
    }

    @Test
    void pagedSelectAppendsInsteadOfWrappingAndBindsOffsetFirst() {
        Dialect.PagedSql page = dialect.pagedSelect(
                "SELECT * FROM [users] WHERE age > ? ORDER BY id", List.of(18), 20, 40);
        assertThat(page.sql())
                .as("派生表里不允许带 TOP 的 ORDER BY —— 只能追加在原句尾部")
                .isEqualTo("SELECT * FROM [users] WHERE age > ? ORDER BY id"
                        + " OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY");
        assertThat(page.params()).as("OFFSET 在前、行数在后，与 LIMIT n OFFSET m 相反")
                .containsExactly(18, 40, 20);
    }

    @Test
    void pagedSelectSeesOrderByOnlyWhenItIsNotInsideASubquery() {
        Dialect.PagedSql page = dialect.pagedSelect(
                "SELECT * FROM (SELECT * FROM t ORDER BY id) x", List.of(), 10, 0);
        assertThat(page.sql()).contains("ORDER BY (SELECT NULL) OFFSET 0 ROWS");
    }

    @Test
    void countedSelectStripsTheTrailingOrderByBeforeWrapping() {
        Dialect.PagedSql count = dialect.countedSelect(
                "SELECT * FROM [users] ORDER BY name DESC", List.of());
        assertThat(count.sql())
                .isEqualTo("SELECT COUNT(*) AS adb_count FROM (SELECT * FROM [users]) AS _p");
        assertThat(count.params()).isEmpty();
    }

    @Test
    void emptyPageProbeUsesTopZeroBecauseThereIsNoLimit() {
        assertThat(dialect.probeColumnsSql("users")).isEqualTo("SELECT TOP 0 * FROM [users]");
        assertThat(dialect.plainPageSql("users", 200, 0))
                .isEqualTo("SELECT * FROM [users] ORDER BY (SELECT NULL)"
                        + " OFFSET 0 ROWS FETCH NEXT 200 ROWS ONLY");
    }

    @Test
    void identityAndNotNullArePartOfTheColumnDefinition() {
        ColumnSpec id = new ColumnSpec("id", "INT", false, null, null, false, true,
                true, null, null);
        assertThat(dialect.columnDefinition(id, true)).isEqualTo("[id] INT IDENTITY(1,1) NOT NULL");
    }

    @Test
    void renameGoesThroughSpRenameBecauseThereIsNoAlterRenameSyntax() {
        assertThat(dialect.renameColumnSql("users", "old", "new", null))
                .isEqualTo("EXEC sp_rename N'users.old', N'new', 'COLUMN'");
        assertThat(dialect.renameTableSql("users", "people"))
                .isEqualTo("EXEC sp_rename N'users', N'people'");
    }

    @Test
    void capabilitiesThatTheUiMustHideAreDeclaredNotFaked() {
        assertThat(dialect.supportsExplainPlan()).isFalse();
        assertThatThrownBy(() -> dialect.explainSql("SELECT 1"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(dialect.supportsSslOptionsUi())
                .as("TLS 要 encrypt + trustServerCertificate 两个参数配对，单个档位表达不了")
                .isFalse();
        assertThat(dialect.sslOptionsNoticeKey())
                .as("说明文案在资源包里；正文由界面的向导测试对账")
                .isEqualTo("wizard.ssl.notice.sqlserver");
        assertThat(dialect.supportsColumnComment())
                .as("列注释是扩展属性，不是列定义的一部分")
                .isFalse();
        assertThat(dialect.supportsForeignKeyOnUpdate()).isTrue();
    }

    /**
     * ⭐ D-20 的门禁：表清单元信息查询不许引用 {@code sys.tables} 里不存在的列。
     *
     * <p>R46 在 SQL Server 2022 真容器上跑出来的：上一版写了 {@code t.collation_name}，
     * 而 SQL Server 的排序规则是<b>列</b>属性（{@code sys.columns} 才有），
     * 表级根本没有 —— 整条查询直接 {@code Invalid column name 'collation_name'}，
     * 于是 SQL Server 的表清单/结构页全空。真库实测过两件事：
     * {@code sys.all_columns WHERE object_id=OBJECT_ID('sys.tables') AND name LIKE '%coll%'}
     * 返回 <b>0 行</b>；{@code DATABASEPROPERTYEX(DB_NAME(),'Collation')} 返回
     * {@code SQL_Latin1_General_CP1_CI_AS}。
     *
     * <p>⚠️ 这条只是「文本层」的门禁（防止回归成大写开头的错列名）；
     * 真正验「跑得通」的是 {@link RealDatabaseContract#metadataQueriesRun}。
     */
    @Test
    void tableMetaAsksTheDatabaseForItsCollationBecauseTablesDoNotHaveOne() {
        String sql = dialect.tableMetaSql();

        assertThat(sql)
                .as("sys.tables 没有 collation_name 列 —— 引用它整条查询就废了")
                .doesNotContain("t.collation_name");
        assertThat(sql)
                .as("排序规则退一步取库级默认值（不是每表一个值，界面要按这个显示）")
                .contains("DATABASEPROPERTYEX(DB_NAME(), 'Collation')");
    }

    /**
     * ⭐ D-24 的门禁：触发器查询不许引用 {@code sys.triggers} 里不存在的列。
     *
     * <p>R46 在 SQL Server 2022 真容器上跑出来的：上一版读了
     * {@code t.is_insert_trigger / is_update_trigger / is_delete_trigger}，
     * 而这三列<b>不在</b> {@code sys.triggers} 上（实测该视图只有
     * {@code is_instead_of_trigger} 一个布尔列；DML 事件在
     * {@code sys.trigger_events.type_desc}）。整条查询报
     * {@code Invalid column name 'is_insert_trigger'}，生产代码把异常吞成
     * 「这张表没有触发器」—— SQL Server 的触发器页因此<b>永远是空的</b>。
     */
    @Test
    void triggerEventsComeFromTriggerEventsBecauseSysTriggersHasNoDmlFlags() {
        String sql = dialect.triggersSql("users");

        assertThat(sql)
                .as("sys.triggers 没有 is_insert_trigger / is_update_trigger / is_delete_trigger")
                .doesNotContain("is_insert_trigger")
                .doesNotContain("is_update_trigger")
                .doesNotContain("is_delete_trigger");
        assertThat(sql)
                .as("DML 事件在 sys.trigger_events（一个触发器可以挂多个事件，聚成一行）")
                .contains("sys.trigger_events");
    }
}
