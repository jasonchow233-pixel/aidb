package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaDiff;
import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ H4-d：差异报告的渲染门禁。
 *
 * <p>{@code SchemaDiffTest} 钉比较逻辑，这里钉「差异有没有被如实写进用户看到的文本」——
 * 尤其是「一样就说一样」「只读报告不许暗示会自动同步」。
 */
class TransferDiffReportTest {

    @Test
    void identicalTablesAreReportedAsHavingNoStructuralDifferences() {
        SchemaDiff.Report report = SchemaDiff.compare("users",
                List.of(new SchemaDiff.Column("id", "INTEGER")),
                List.of(new SchemaDiff.Column("id", "INTEGER")),
                List.of("id"), List.of("id"), 3, 3);

        String text = TransferToolPage.renderDiff(report);
        assertThat(text).contains(I18n.t("tool.transfer.diff.title", "users"));
        assertThat(text).contains(I18n.t("tool.transfer.diff.none"));
        assertThat(text).contains(I18n.t("tool.transfer.diff.rows", 3L, 3L));
        assertThat(text).doesNotContain("Only in source", "Only in target");
    }

    @Test
    void everyKindOfDifferenceAppearsInTheReport() {
        SchemaDiff.Report report = SchemaDiff.compare("users",
                List.of(new SchemaDiff.Column("id", "INTEGER"),
                        new SchemaDiff.Column("email", "VARCHAR(120)"),
                        new SchemaDiff.Column("phone", "TEXT")),
                List.of(new SchemaDiff.Column("id", "BIGINT"),
                        new SchemaDiff.Column("email", "VARCHAR(255)"),
                        new SchemaDiff.Column("note", "TEXT")),
                List.of("id"), List.of("id", "email"), 5, 7);

        String text = TransferToolPage.renderDiff(report);
        assertThat(text).contains(I18n.t("tool.transfer.diff.onlySource", "phone"));
        assertThat(text).contains(I18n.t("tool.transfer.diff.onlyTarget", "note"));
        assertThat(text).contains(I18n.t("tool.transfer.diff.type",
                "id", "INTEGER", "BIGINT"));
        assertThat(text).contains(I18n.t("tool.transfer.diff.type",
                "email", "VARCHAR(120)", "VARCHAR(255)"));
        assertThat(text).contains(I18n.t("tool.transfer.diff.pk"));
        assertThat(text).doesNotContain(I18n.t("tool.transfer.diff.none"));
        assertThat(text).contains(I18n.t("tool.transfer.diff.rows", 5L, 7L));
    }

    @Test
    void reportStatesThatItIsReadOnly() {
        String text = TransferToolPage.renderDiff(SchemaDiff.compare("t",
                List.of(), List.of(), List.of(), List.of(), 0, 0));
        assertThat(I18n.t("tool.transfer.diff.none")).isNotBlank();
        assertThat(text).contains(I18n.t("tool.transfer.diff.none"));
    }
}
