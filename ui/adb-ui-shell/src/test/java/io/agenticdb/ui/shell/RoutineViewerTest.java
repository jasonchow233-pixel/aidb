package io.agenticdb.ui.shell;

import io.agenticdb.introspect.RoutineSql;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 {@code RoutineViewerPane.compose} 这个纯静态渲染函数验证三块文本，
 * <b>不起 JavaFX 场景图</b>；读数口由入口接线后在 FX 环境下另测。
 */
class RoutineViewerTest {

    private static final String DEFINITION =
            "CREATE OR REPLACE FUNCTION public.add(a integer, b integer)\n"
            + " RETURNS integer\n"
            + " LANGUAGE plpgsql\n"
            + "AS $function$\n"
            + "BEGIN\n"
            + "    RETURN a + b;\n"
            + "END;\n"
            + "$function$";

    @AfterEach
    void clearMissingKeyLedger() {
        // 键由主代理统一追加到两份资源包；这里清掉本测试可能留下的缺键记账，
        // 避免污染全仓 I18n 门禁的全局账本。
        I18n.clearMissingKeys();
    }

    @Test
    void unsupportedDialectLeavesPreviewEmptyButStillShowsDefinitionAndNote() {
        var content = RoutineViewerPane.compose(DEFINITION, DialectKind.MYSQL, "add", true);

        assertThat(content.preview()).isEmpty();
        assertThat(content.note()).isNotEmpty();
        assertThat(content.definition()).isEqualTo(DEFINITION);
    }

    @Test
    void everyUnsupportedDialectLeavesThePreviewEmpty() {
        for (DialectKind kind : DialectKind.values()) {
            if (RoutineSql.supported(kind)) {
                continue;
            }
            var content = RoutineViewerPane.compose(DEFINITION, kind, "add", true);
            assertThat(content.preview()).as("no preview for %s", kind).isEmpty();
            assertThat(content.note()).isNotEmpty();
        }
    }

    @Test
    void supportedDialectProducesACreateOrReplacePreview() {
        var content = RoutineViewerPane.compose(
                DEFINITION, DialectKind.POSTGRESQL, "add", true);

        assertThat(content.preview()).contains("CREATE OR REPLACE FUNCTION");
        assertThat(content.definition()).isEqualTo(DEFINITION);
    }

    @Test
    void emptyDefinitionIsReportedHonestly() {
        var content = RoutineViewerPane.compose("", DialectKind.POSTGRESQL, "add", true);

        assertThat(content.preview()).isEmpty();
        assertThat(content.note()).isNotEmpty();
    }
}
