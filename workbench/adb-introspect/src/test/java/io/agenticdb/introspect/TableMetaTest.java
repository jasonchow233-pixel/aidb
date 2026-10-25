package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表清单元数据门禁。
 *
 * <p>Navicat 的表清单是 8 列：名称 / 行 / 数据长度 / 引擎 / 创建日期 /
 * 修改日期 / 排序规则 / 注释。这里锁住两件事：
 * <ol>
 *   <li>方言 SQL 必须真的把后三列查出来（并且别名与映射一致）；</li>
 *   <li>拿不到值时 UI 拿到的是「—」，不是 {@code null} 或空白。</li>
 * </ol>
 */
class TableMetaTest {

    @Test
    void carriesAllEightNavicatColumns() {
        TableMeta meta = new TableMeta("users", 42L, 1536L, "InnoDB",
                "2026-10-01 10:00:00", "2026-10-02 11:30:00",
                "utf8mb4_0900_ai_ci", "用户表");

        assertThat(meta.rowsText()).isEqualTo("42");
        assertThat(meta.sizeText()).isEqualTo("1.5 KB");
        assertThat(meta.engineText()).isEqualTo("InnoDB");
        assertThat(meta.createdText()).isEqualTo("2026-10-01 10:00:00");
        assertThat(meta.updatedText()).isEqualTo("2026-10-02 11:30:00");
        assertThat(meta.collationText()).isEqualTo("utf8mb4_0900_ai_ci");
        assertThat(meta.commentText()).isEqualTo("用户表");
    }

    @Test
    void missingValuesShowADashInsteadOfBlank() {
        TableMeta meta = new TableMeta("logs", null, null, null, null);

        assertThat(meta.rowsText()).isEqualTo("—");
        assertThat(meta.sizeText()).isEqualTo("—");
        assertThat(meta.engineText()).isEqualTo("—");
        assertThat(meta.createdText()).isEqualTo("—");
        assertThat(meta.updatedText()).isEqualTo("—");
        assertThat(meta.collationText()).isEqualTo("—");
        assertThat(meta.commentText()).isEqualTo("—");
    }

    @Test
    void blankTextCountsAsMissing() {
        TableMeta meta = new TableMeta("logs", 0L, 0L, " ", "", null, "\t", "  ");

        assertThat(meta.engineText()).isEqualTo("—");
        assertThat(meta.createdText()).isEqualTo("—");
        assertThat(meta.collationText()).isEqualTo("—");
        assertThat(meta.commentText()).isEqualTo("—");
        // 0 行 / 0 字节是真实值，不是「没有」。
        assertThat(meta.rowsText()).isEqualTo("0");
        assertThat(meta.sizeText()).isEqualTo("0 B");
    }

    @Test
    void bytesBelowOneKStayIntegral() {
        assertThat(sizeText(0)).isEqualTo("0 B");
        assertThat(sizeText(1)).isEqualTo("1 B");
        assertThat(sizeText(1023)).isEqualTo("1023 B");
    }

    /**
     * 1 位小数：旧实现直接拼 double，1126 字节会显示成
     * 「1.099609375 KB」—— 表清单那一列根本读不出来。
     */
    @Test
    void kilobytesAndMegabytesRoundToOneDecimal() {
        assertThat(sizeText(1126)).isEqualTo("1.1 KB");
        assertThat(sizeText(1536)).isEqualTo("1.5 KB");
        assertThat(sizeText(1024L * 1024 + 512L * 1024)).isEqualTo("1.5 MB");
    }

    @Test
    void gigabytesDoNotShowUpAsThousandsOfMegabytes() {
        assertThat(sizeText(2L * 1024 * 1024 * 1024)).isEqualTo("2.0 GB");
        assertThat(sizeText(1024L * 1024 * 1024)).isEqualTo("1.0 GB");
    }

    @Test
    void formattingDoesNotFollowTheSystemLocale() {
        Locale previous = Locale.getDefault();
        try {
            // 德语区域的小数分隔符是逗号 —— 表清单要的是「1.5 KB」。
            Locale.setDefault(Locale.GERMANY);
            assertThat(sizeText(1536)).isEqualTo("1.5 KB");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static String sizeText(long bytes) {
        return new TableMeta("t", 1L, bytes, "InnoDB", null).sizeText();
    }

    @Test
    void mysqlSqlSelectsTheThreeNewColumns() {
        String sql = FakeDialectSession.of(DialectKind.MYSQL).dialect().tableMetaSql();

        assertThat(sql)
                .as("MySQL 的 information_schema 一次就能取全 8 列")
                .contains("update_time AS updated_at")
                .contains("table_collation AS collation_name")
                .contains("table_comment AS comment_text");
    }

    @Test
    void postgresSqlKeepsUpdatedAtHonest() {
        String sql = FakeDialectSession.of(DialectKind.POSTGRESQL).dialect().tableMetaSql();

        assertThat(sql)
                .as("PostgreSQL 没有可靠的「最后修改时间」—— 给 NULL，不编造")
                .contains("NULL AS updated_at")
                .contains("AS collation_name")
                .contains("obj_description(c.oid, 'pg_class') AS comment_text");
    }

    @Test
    void sqliteHasNoSingleQueryAndFallsBackToCounting() {
        assertThat(FakeDialectSession.of(DialectKind.SQLITE).dialect().tableMetaSql())
                .as("SQLite 没有表大小 / 创建时间 / 排序规则 —— 由 SchemaService 退化为逐表 COUNT")
                .isNull();
    }
}
