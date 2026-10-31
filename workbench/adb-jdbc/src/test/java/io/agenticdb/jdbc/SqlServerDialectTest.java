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
}
