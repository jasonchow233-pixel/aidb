package io.agenticdb.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ H4-d：结构差异的纯函数门禁。
 *
 * <p>这一版<b>只读比较、不自动同步</b>，所以最要紧的是「别把一样的表说成有差异、
 * 也别把不一样的表说成一样」—— 尤其是大小写、类型长度、主键与行数。
 */
class SchemaDiffTest {

    private static final List<SchemaDiff.Column> BASE = List.of(
            new SchemaDiff.Column("id", "INTEGER"),
            new SchemaDiff.Column("email", "VARCHAR(120)"));

    @Test
    void identicalStructuresAndRowCountsProduceAnEmptyReport() {
        SchemaDiff.Report report = SchemaDiff.compare("users", BASE, BASE,
                List.of("id"), List.of("id"), 10, 10);
        assertThat(report.identical()).isTrue();
        assertThat(report.differenceCount()).isZero();
        assertThat(report.onlyInSource()).isEmpty();
        assertThat(report.onlyInTarget()).isEmpty();
        assertThat(report.typeMismatches()).isEmpty();
    }

    @Test
    void extraColumnsAreReportedOnTheCorrectSide() {
        SchemaDiff.Report report = SchemaDiff.compare("users",
                List.of(new SchemaDiff.Column("id", "INTEGER"),
                        new SchemaDiff.Column("phone", "TEXT")),
                List.of(new SchemaDiff.Column("id", "INTEGER"),
                        new SchemaDiff.Column("note", "TEXT")),
                List.of("id"), List.of("id"), 3, 3);

        assertThat(report.onlyInSource()).containsExactly("phone");
        assertThat(report.onlyInTarget()).containsExactly("note");
        assertThat(report.identical()).isFalse();
        assertThat(report.differenceCount()).isEqualTo(2);
    }

    @Test
    void columnNamesAreCaseInsensitiveButTypesKeepTheirDisplayText() {
        SchemaDiff.Report report = SchemaDiff.compare("users",
                List.of(new SchemaDiff.Column("Email", "VARCHAR(120)")),
                List.of(new SchemaDiff.Column("email", "varchar(120)")),
                List.of(), List.of(), 1, 1);
        assertThat(report.identical()).isTrue();

        SchemaDiff.Report changed = SchemaDiff.compare("users",
                List.of(new SchemaDiff.Column("Email", "VARCHAR(120)")),
                List.of(new SchemaDiff.Column("email", "VARCHAR(255)")),
                List.of(), List.of(), 1, 1);
        assertThat(changed.typeMismatches()).containsExactly(
                new SchemaDiff.TypeMismatch("Email", "VARCHAR(120)", "VARCHAR(255)"));
        assertThat(changed.identical()).isFalse();
    }

    @Test
    void primaryKeyAndRowCountDifferencesAreDistinctSignals() {
        SchemaDiff.Report pk = SchemaDiff.compare("users", BASE, BASE,
                List.of("id"), List.of("ID", "email"), 5, 5);
        assertThat(pk.primaryKeySame()).isFalse();
        assertThat(pk.differenceCount()).isEqualTo(1);

        SchemaDiff.Report rows = SchemaDiff.compare("users", BASE, BASE,
                List.of("id"), List.of("id"), 5, 7);
        assertThat(rows.primaryKeySame()).isTrue();
        assertThat(rows.differenceCount()).isZero()
                .as("行数不同不是结构差异，但 identical() 必须为 false");
        assertThat(rows.identical()).isFalse();
        assertThat(rows.sourceRows()).isEqualTo(5);
        assertThat(rows.targetRows()).isEqualTo(7);
    }

    @Test
    void nullInputsAreTreatedAsEmptyRatherThanThrowing() {
        SchemaDiff.Report report = SchemaDiff.compare(null, null, null, null, null, 0, 0);
        assertThat(report.identical()).isTrue();
        assertThat(report.table()).isEmpty();
        assertThat(SchemaDiff.columnsOf(null)).isEmpty();
    }
}
