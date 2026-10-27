package io.agenticdb.ui.shell;

import io.agenticdb.introspect.TableMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MetadataColumns} 门禁 —— 「这一列该不该显示」的判定规则。
 *
 * <p>第十九轮 S-1 的核心：默认的 SQLite 演示库里，Navicat 那 8 列有 6 列恒为「—」
 * （逐像素实测占首选宽度 72%）。
 * 这里锁住「只看数据、不看方言」的判定，避免以后有人塞一张方言白名单进来
 * 把混合场景（MySQL 表有引擎、视图没有）判错。
 */
class MetadataColumnsTest {

    /** SQLite 演示库的典型形态：只有行数，其余全是 null → 渲染成「—」。 */
    private static final TableMeta SQLITE_ROW = new TableMeta(
            "users", 200L, null, null, null, null, null, null);

    /** MySQL 的典型形态：8 列全有值。 */
    private static final TableMeta MYSQL_ROW = new TableMeta(
            "users", 200L, 3072L, "InnoDB",
            "2026-01-01 09:00:00", "2026-02-01 10:00:00",
            "utf8mb4_0900_ai_ci", "用户表");

    @Test
    void isPlaceholderMatchesWhatTheTableMetaRendersForMissingValues() {
        assertThat(MetadataColumns.isPlaceholder("—")).isTrue();
        assertThat(MetadataColumns.isPlaceholder(null)).isTrue();
        assertThat(MetadataColumns.isPlaceholder("")).isTrue();
        assertThat(MetadataColumns.isPlaceholder("   ")).isTrue();
        assertThat(MetadataColumns.isPlaceholder("InnoDB")).isFalse();
        assertThat(MetadataColumns.isPlaceholder("0")).isFalse();
        assertThat(MetadataColumns.isPlaceholder("0 B")).isFalse();
    }

    /** SQLite：引擎 / 排序规则 / 注释 等每一行都是「—」→ 该列没信息量。 */
    @Test
    void columnIsWorthlessWhenEveryRowRendersThePlaceholder() {
        List<TableMeta> sqlite = List.of(SQLITE_ROW, SQLITE_ROW, SQLITE_ROW);

        assertThat(MetadataColumns.hasAnyValue(sqlite, TableMeta::engineText)).isFalse();
        assertThat(MetadataColumns.hasAnyValue(sqlite, TableMeta::createdText)).isFalse();
        assertThat(MetadataColumns.hasAnyValue(sqlite, TableMeta::updatedText)).isFalse();
        assertThat(MetadataColumns.hasAnyValue(sqlite, TableMeta::collationText)).isFalse();
        assertThat(MetadataColumns.hasAnyValue(sqlite, TableMeta::commentText)).isFalse();
        assertThat(MetadataColumns.hasAnyValue(sqlite, TableMeta::sizeText)).isFalse();

        // 行数有真值 → 保留；「0」不是占位符（空表也要显示 0 行）。
        assertThat(MetadataColumns.hasAnyValue(sqlite, TableMeta::rowsText)).isTrue();
    }

    /** MySQL：每一列都有真值 → 全部保留。 */
    @Test
    void columnSurvivesWhenEveryRowHasARealValue() {
        List<TableMeta> mysql = List.of(MYSQL_ROW, MYSQL_ROW);

        assertThat(MetadataColumns.hasAnyValue(mysql, TableMeta::engineText)).isTrue();
        assertThat(MetadataColumns.hasAnyValue(mysql, TableMeta::createdText)).isTrue();
        assertThat(MetadataColumns.hasAnyValue(mysql, TableMeta::updatedText)).isTrue();
        assertThat(MetadataColumns.hasAnyValue(mysql, TableMeta::collationText)).isTrue();
        assertThat(MetadataColumns.hasAnyValue(mysql, TableMeta::commentText)).isTrue();
        assertThat(MetadataColumns.hasAnyValue(mysql, TableMeta::sizeText)).isTrue();
    }

    /**
     * 混合场景：只要**一行**有真值，整列就留着。
     *
     * <p>这是「比方言白名单更稳」的关键 —— MySQL 里一个没填注释的表，
     * 不该连累整个注释列被藏掉。
     */
    @Test
    void oneRealValueAmongPlaceholdersKeepsTheColumn() {
        List<TableMeta> mixed = List.of(
                new TableMeta("a", 1L, null, null, null, null, null, null),
                new TableMeta("b", 1L, null, "InnoDB", null, null, null, null));

        assertThat(MetadataColumns.hasAnyValue(mixed, TableMeta::engineText))
                .as("混合场景：只要有行有引擎，引擎列就该留着")
                .isTrue();
        assertThat(MetadataColumns.hasAnyValue(mixed, TableMeta::commentText))
                .as("另一列仍然全空 → 该列仍要隐藏")
                .isFalse();
    }

    /**
     * ⚠️ 空列表一律返回「没有值」= 该列隐藏 —— 但**必须**由调用方保证
     * 判定用的是整批数据而不是过滤子集；搜索到 0 条时不能把列全藏掉。
     */
    @Test
    void emptyRowListReportsNoValue() {
        assertThat(MetadataColumns.hasAnyValue(List.of(), TableMeta::engineText)).isFalse();
        assertThat(MetadataColumns.hasAnyValue(null, TableMeta::engineText)).isFalse();
    }

    /** 判定依据必须是 `*Text()` 的**渲染结果**，不是原始字段 —— 否则会漂移。 */
    @Test
    void judgementUsesTheSameRenderedTextAsTheCells() {
        // sizeBytes = 0 是「有值」（0 B），不是占位符。
        TableMeta zeroSize = new TableMeta("t", 0L, 0L, null, null, null, null, null);
        assertThat(MetadataColumns.hasAnyValue(List.of(zeroSize), TableMeta::sizeText)).isTrue();
        assertThat(MetadataColumns.hasAnyValue(List.of(zeroSize), TableMeta::rowsText)).isTrue();

        // 只有 null 才是占位符。
        TableMeta noSize = new TableMeta("t", 0L, null, null, null, null, null, null);
        assertThat(MetadataColumns.hasAnyValue(List.of(noSize), TableMeta::sizeText)).isFalse();
    }
}
