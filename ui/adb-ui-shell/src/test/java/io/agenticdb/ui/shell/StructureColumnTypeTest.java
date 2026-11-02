package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ D-23 门禁：「列」对话框的类型候选与默认值都跟着库走。
 *
 * <p>这里原来是硬编码的十二个 SQLite 味类型名（{@code TEXT / VARCHAR(255) / INTEGER /
 * REAL / NUMERIC / BOOLEAN / DATE / TIME / TIMESTAMP / BLOB / JSON / UUID}），
 * 同一份发给六种库。后果分两类：
 * <ul>
 *   <li><b>误导</b>：Oracle 没有 {@code TEXT}，SQL Server 没有 {@code UUID}
 *       （那是 {@code UNIQUEIDENTIFIER}），选了就是必失败的 DDL；</li>
 *   <li><b>够用不上</b>：Oracle 的 {@code NUMBER / VARCHAR2}、
 *       SQL Server 的 {@code NVARCHAR} 在清单里一个都没有，
 *       而 {@code NVARCHAR} 恰好是唯一不会把中文悄悄折成问号的那个。</li>
 * </ul>
 *
 * <p>默认值那一半更要紧：接口的 {@code VARCHAR} 默认在 SQL Server 上是单字节代码页，
 * 中文写进去不报错、读回来是脏数据，所以 {@code SqlServerDialect} 必须覆盖成
 * {@code NVARCHAR} —— 这条由 {@code defaultColumnTypeIsTheOneThatCannotSilentlyMangle} 钉住。
 */
class StructureColumnTypeTest {

    private static Dialect dialect(DialectKind kind) {
        return kind.dialect();
    }

    @Test
    void choicesAreTheDialectsOwnListNotAShardcodedOne() {
        for (DialectKind kind : DialectKind.values()) {
            assertThat(StructurePage.typeChoices(dialect(kind)))
                    .as("%s 的类型候选必须等于方言自己那份清单", kind.dialect().displayName())
                    .isEqualTo(kind.dialect().columnTypes().stream()
                            .map(io.agenticdb.jdbc.ColumnTypeOption::name)
                            .toList());
        }
    }

    @Test
    void oracleNeverSeesTheSqliteFlavouredNames() {
        List<String> oracle = StructurePage.typeChoices(dialect(DialectKind.ORACLE));

        assertThat(oracle).contains("VARCHAR2", "NUMBER");
        assertThat(oracle)
                .as("旧清单里的 SQLite 味道名字：Oracle 认不出 TEXT，也没有 UUID / BLOB 那种写法")
                .doesNotContain("TEXT", "UUID", "REAL");
    }

    @Test
    void sqlServerSeesUnicodeTypesAndNoUuidAlias() {
        List<String> sqlServer = StructurePage.typeChoices(dialect(DialectKind.SQLSERVER));

        assertThat(sqlServer).contains("NVARCHAR", "UNIQUEIDENTIFIER");
        assertThat(sqlServer)
                .as("SQL Server 的主键类型叫 UNIQUEIDENTIFIER，不叫 UUID")
                .doesNotContain("UUID");
    }

    @Test
    void defaultColumnTypeIsTheOneThatCannotSilentlyMangle() {
        assertThat(dialect(DialectKind.SQLSERVER).defaultColumnType())
                .as("⭐ VARCHAR 在 SQL Server 上是库默认代码页，中文进去静默变问号 —— "
                        + "默认值必须是 NVARCHAR")
                .isEqualTo("NVARCHAR");
        assertThat(dialect(DialectKind.ORACLE).defaultColumnType())
                .as("Oracle 的变长字符是 VARCHAR2（VARCHAR 是留给未来的别名）")
                .isEqualTo("VARCHAR2");
        assertThat(dialect(DialectKind.MYSQL).defaultColumnType()).isEqualTo("VARCHAR");
        assertThat(dialect(DialectKind.POSTGRESQL).defaultColumnType()).isEqualTo("VARCHAR");
    }

    @Test
    void defaultIsNeverAFixedLengthType() {
        for (DialectKind kind : DialectKind.values()) {
            Dialect dialect = kind.dialect();
            assertThat(dialect.defaultColumnType())
                    .as("%s 的默认类型不能是 CHAR —— 不写长度就是长度 1，等于每列偷偷截断",
                            dialect.displayName())
                    .isNotEqualTo("CHAR")
                    .isNotEqualTo("NCHAR");
        }
    }
}
