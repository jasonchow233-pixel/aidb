package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

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

    /**
     * ⭐ 第二十二轮 P2-1：大小要有 GB 档，且**每档都只留一位小数**。
     *
     * <p>以前 KB 档是直接拼 double 的 —— 1365 字节会显示成
     * {@code 1.3330078125 KB}；而三档到顶，1.5 GB 的表显示成
     * {@code 1536.0 MB}。
     */
    @Test
    void sizeTextsCoverFourTiers() {
        assertThat(sizeOf(512)).isEqualTo("512 B");
        assertThat(sizeOf(1024)).isEqualTo("1.0 KB");
        assertThat(sizeOf(5 * 1024 * 1024)).isEqualTo("5.0 MB");
        assertThat(sizeOf(2L * 1024 * 1024 * 1024))
                .as("大表就是 GB 级，不能显示成 2048.0 MB")
                .isEqualTo("2.0 GB");
        assertThat(sizeOf(1_610_612_736L))
                .as("1.5 GB 得说成 1.5 GB，不是 1536.0 MB")
                .isEqualTo("1.5 GB");
    }

    @Test
    void sizesAreRoundedToOneDecimalNotSpilledOutAsADouble() {
        assertThat(sizeOf(1365))
                .as("1365 / 1024 = 1.3330078125 —— 拼出来的原始 double 没法扫读")
                .isEqualTo("1.3 KB");
        assertThat(sizeOf(100_000)).isEqualTo("97.7 KB");
    }

    /**
     * 1048575 B = 1023.999 KB，保留一位小数就顶成 1024.0 KB ——
     * 而 1024 不是 KB 里该出现的数字，必须进一档。
     */
    @Test
    void aSizeRoundingUpToTheNextTierIsPromoted() {
        assertThat(sizeOf(1024 * 1024 - 1))
                .as("1048575 B 要讲 1.0 MB，不是 1024.0 KB")
                .isEqualTo("1.0 MB");
        assertThat(sizeOf(1024L * 1024 * 1024 - 1)).isEqualTo("1.0 GB");
    }

    /** 负的字节数不是「负的大小」，是这来源不可信 —— 说「—」，别说「-1 B」。 */
    @Test
    void aNegativeSizeIsUnknownNotAMinusOne() {
        assertThat(sizeOf(-1)).isEqualTo("—");
    }

    private static String sizeOf(long bytes) {
        return new TableMeta("t", 0L, bytes, null, null).sizeText();
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
