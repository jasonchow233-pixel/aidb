package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.transfer.MaskRule;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P-3：列级脱敏的 UI 门禁。
 *
 * <p>{@link DataMaskerTest} / {@link MaskedExportTest} 钉的是领域层「掩码算法对了、
 * 每条写出路径都打码了」；这一份钉的是<b>用户在向导里的勾选确实变成规则并走到编码器</b>，
 * 以及「脱敏功能不能自己骗自己」的三条诚实约束：默认关、能整关掉、
 * 从数据脱敏按钮进来却没认出敏感列时也必须露出可勾选的列。
 */
@ExtendWith(ApplicationExtension.class)
class MaskingWizardTest {

    private static final List<String> COLUMNS =
            List.of("id", "email", "phone", "age");

    @TempDir
    Path temp;

    @Start
    void start(Stage stage) {
        stage.show();
    }

    @Test
    void maskingIsOffByDefaultAndTheSectionDoesNotTakeSpace() {
        ExportWizard wizard = wizard();

        CheckBox toggle = onFx(() -> (CheckBox) wizard.content().lookup("#export-mask"));
        assertThat(toggle.isSelected()).isFalse();
        assertThat(onFx(() -> wizard.activeMaskRules())).isEmpty();
        onFxRun(() -> {
            VBox columns = (VBox) wizard.content().lookup("#export-mask-columns");
            assertThat(columns.getParent().isVisible()).isFalse();
            assertThat(columns.getParent().isManaged()).isFalse();
        });
        assertThat(noteOf(wizard)).isEqualTo(I18n.t("export.mask.note"));
    }

    @Test
    void presetMaskingChecksOnlyTheSuggestedColumnsAndReportsTheCount() {
        ExportWizard wizard = wizard();
        onFxRun(() -> wizard.presetMasking(Set.of("email", "phone")));

        assertThat(selectedMaskColumns(wizard)).containsExactly("email", "phone");
        assertThat(onFx(() -> wizard.activeMaskRules())).containsExactly(
                new MaskRule("email", MaskRule.MaskMode.FULL),
                new MaskRule("phone", MaskRule.MaskMode.FULL));
        assertThat(noteOf(wizard))
                .isEqualTo(I18n.tPlural("export.mask.active", 2, 2));
        assertThat(maskSectionVisible(wizard)).isTrue();
    }

    @Test
    void noSuggestionStillOpensTheSectionInsteadOfPretendingToBeAPlainExport() {
        ExportWizard wizard = wizard();
        onFxRun(() -> wizard.presetMasking(Set.of()));

        assertThat(onFx(() -> ((CheckBox) wizard.content()
                .lookup("#export-mask")).isSelected())).isTrue();
        assertThat(maskSectionVisible(wizard)).isTrue();
        assertThat(selectedMaskColumns(wizard)).isEmpty();
        assertThat(onFx(() -> wizard.activeMaskRules())).isEmpty();
        assertThat(noteOf(wizard)).isEqualTo(I18n.t("export.mask.note"));
    }

    @Test
    void modeAndColumnTogglesAreReflectedInTheRules() {
        ExportWizard wizard = wizard();
        onFxRun(() -> {
            wizard.presetMasking(Set.of("email", "phone"));
            @SuppressWarnings("unchecked")
            ComboBox<MaskRule.MaskMode> mode = (ComboBox<MaskRule.MaskMode>)
                    wizard.content().lookup("#export-mask-mode");
            mode.setValue(MaskRule.MaskMode.EDGES);
            maskCheckBox(wizard, "email").setSelected(false);
        });

        assertThat(onFx(() -> wizard.activeMaskRules())).containsExactly(
                new MaskRule("phone", MaskRule.MaskMode.EDGES));
        assertThat(noteOf(wizard))
                .isEqualTo(I18n.tPlural("export.mask.active", 1, 1));
    }

    @Test
    void turningMaskingOffProducesNoRulesEvenWhenColumnsStayChecked() {
        ExportWizard wizard = wizard();
        onFxRun(() -> {
            wizard.presetMasking(Set.of("email", "phone"));
            ((CheckBox) wizard.content().lookup("#export-mask")).setSelected(false);
        });

        assertThat(onFx(() -> wizard.activeMaskRules())).isEmpty();
        assertThat(maskSectionVisible(wizard)).isFalse();
        assertThat(noteOf(wizard)).isEqualTo(I18n.t("export.mask.note"));
    }

    @Test
    void theCheckedColumnIsMaskedInTheWrittenFileAndUntouchedColumnsAreNot() throws Exception {
        Path target = temp.resolve("masked.csv");
        ExportWizard wizard = onFx(() -> new ExportWizard(
                FakeDbSession.of(DialectKind.SQLITE), "users",
                List.of("id", "email", "phone"), ExportWizard.Scope.SELECTED,
                target, Runnable::run, message -> { },
                (scope, columns) -> List.<List<Object>>of(
                        List.<Object>of(1, "alice@example.com", "13812345678"),
                        List.<Object>of(2, "bob@example.com", "13900000000"))));

        onFxRun(() -> {
            wizard.presetMasking(Set.of("email"));
            ((TextField) wizard.content().lookup("#export-target"))
                    .setText(target.toString());
            ((Button) wizard.content().lookup("#export-start")).fire();
        });

        assertThat(Files.exists(target)).as("导出文件应当被写出来").isTrue();
        String csv = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(csv).contains("id,email,phone");
        assertThat(csv).contains("******");
        assertThat(csv).doesNotContain("alice@example.com", "bob@example.com");
        assertThat(csv).as("没勾的列必须原样，脱敏不该顺手改掉别的数据")
                .contains("13812345678", "13900000000");
    }

    /** ⭐ 盐值控件只在 HASH 模式露出：其它模式下摆一个用不上的开关等于骗人。 */
    @Test
    void saltControlsOnlyAppearForHashMode() {
        ExportWizard wizard = wizard();
        onFxRun(() -> wizard.presetMasking(Set.of("email")));

        assertThat(saltRowVisible(wizard)).isFalse();
        assertThat(onFx(() -> wizard.activeMaskSalt())).isEmpty();

        setMode(wizard, MaskRule.MaskMode.HASH);
        assertThat(saltRowVisible(wizard)).isTrue();
        assertThat(onFx(() -> wizard.activeMaskSalt()))
                .as("默认不加盐，用户必须主动生成或填写").isEmpty();

        setMode(wizard, MaskRule.MaskMode.FULL);
        assertThat(saltRowVisible(wizard)).isFalse();
    }

    /** 点「生成」得到的盐值真的递到导出选项；切回 FULL 后必须立刻失效。 */
    @Test
    void generatedSaltReachesTheOptionsAndStopsWhenHashIsLeft() {
        ExportWizard wizard = wizard();
        onFxRun(() -> {
            wizard.presetMasking(Set.of("email"));
            @SuppressWarnings("unchecked")
            ComboBox<MaskRule.MaskMode> mode = (ComboBox<MaskRule.MaskMode>)
                    wizard.content().lookup("#export-mask-mode");
            mode.setValue(MaskRule.MaskMode.HASH);
            ((Button) wizard.content().lookup("#export-mask-salt-generate")).fire();
        });

        String salt = onFx(() -> ((TextField) wizard.content()
                .lookup("#export-mask-salt")).getText());
        assertThat(salt).matches("[0-9a-f]{32}");
        assertThat(onFx(() -> wizard.activeMaskSalt())).isEqualTo(salt);

        setMode(wizard, MaskRule.MaskMode.FULL);
        assertThat(onFx(() -> wizard.activeMaskSalt())).isEmpty();
    }

    private static void setMode(ExportWizard wizard, MaskRule.MaskMode mode) {
        onFxRun(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<MaskRule.MaskMode> box = (ComboBox<MaskRule.MaskMode>)
                    wizard.content().lookup("#export-mask-mode");
            box.setValue(mode);
        });
    }

    private static boolean saltRowVisible(ExportWizard wizard) {
        return onFx(() -> {
            HBox row = (HBox) wizard.content().lookup("#export-mask-salt-row");
            return row.isVisible() && row.isManaged();
        });
    }

    @Test
    void suggestedMaskColumnsIsAStaticPureHintNotAClassifier() {
        assertThat(DataGridPage.suggestedMaskColumns(List.of(
                "id", "EMAIL", "mobile_no", "id_card", "created_at")))
                .containsExactly("email", "mobile_no", "id_card");
        assertThat(DataGridPage.suggestedMaskColumns(List.of("id", "created_at"))).isEmpty();
        assertThat(DataGridPage.suggestedMaskColumns(null)).isEmpty();
    }

    // ── 脚手架 ──────────────────────────────────────────────

    private ExportWizard wizard() {
        return onFx(() -> new ExportWizard(FakeDbSession.of(DialectKind.SQLITE),
                "users", COLUMNS, ExportWizard.Scope.WHOLE_TABLE,
                temp.resolve("unused.csv"), Runnable::run, message -> { }));
    }

    private static CheckBox maskCheckBox(ExportWizard wizard, String column) {
        VBox box = (VBox) wizard.content().lookup("#export-mask-columns");
        return box.getChildrenUnmodifiable().stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(candidate -> candidate.getText().equals(column))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing mask checkbox: " + column));
    }

    private static List<String> selectedMaskColumns(ExportWizard wizard) {
        VBox box = (VBox) wizard.content().lookup("#export-mask-columns");
        return box.getChildrenUnmodifiable().stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(CheckBox::isSelected)
                .map(CheckBox::getText)
                .toList();
    }

    private static String noteOf(ExportWizard wizard) {
        return onFx(() -> ((Label) wizard.content()
                .lookup("#export-mask-note")).getText());
    }

    private static boolean maskSectionVisible(ExportWizard wizard) {
        return onFx(() -> {
            VBox columns = (VBox) wizard.content().lookup("#export-mask-columns");
            return columns.getParent().isVisible() && columns.getParent().isManaged();
        });
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
