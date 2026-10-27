package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import io.agenticdb.ui.Shortcuts;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U26 的门禁：tooltip 带快捷键，能力才可发现。
 *
 * <p>问题：快捷键只在菜单里由 FX 自己画一列，工具栏 / 功能栏按钮的 tooltip
 * 全靠调用方手打 —— 打了就有没有打就没有，还打出过「连接管理 (Mod+N)」这种
 * <b>把快捷键记到别的动作头上</b>的假提示。现在收口到 {@link Action}：
 * 按钮由注册表生成时，快捷键从动作自己的 accelerator 现取现格式化。
 */
@ExtendWith(ApplicationExtension.class)
class ShortcutTooltipTest {

    @Start
    private void start(Stage stage) {
        stage.show();
    }

    @Test
    void shortcutTextIsPlatformisedFromTheAccelerator() {
        // 断言写语义期望值（Shortcuts.of），不写 Ctrl / ⌘ 字面 —— 同一平台规则，
        // 换到 macOS 跑也不会假红。
        Action save = new Action("save", "保存", "文件",
                KeyCombination.keyCombination("Shortcut+S"), () -> { });
        assertThat(save.shortcutText()).isEqualTo(Shortcuts.of("Mod+S"));

        Action run = new Action("run", "执行", "查询",
                KeyCombination.keyCombination("Shift+Shortcut+Enter"), () -> { });
        assertThat(run.shortcutText()).isEqualTo(Shortcuts.of("Shift+Mod+Enter"));

        Action none = new Action("plain", "普通", "其他", null, () -> { });
        assertThat(none.shortcutText()).isNull();
    }

    @Test
    void hintGetsTheShortcutAppendedOnlyWhenTheActionHasOne() {
        Action save = new Action("save", "保存", "文件",
                KeyCombination.keyCombination("Shortcut+S"), () -> { });
        assertThat(save.hintWithShortcut("预览 SQL 并保存全部修改"))
                .isEqualTo("预览 SQL 并保存全部修改 (" + Shortcuts.of("Mod+S") + ")");

        Action noKey = new Action("theme.toggle", "切换主题", "视图", null, () -> { });
        assertThat(noKey.hintWithShortcut("切换主题")).isEqualTo("切换主题");
    }

    @Test
    void registryIconButtonsCarryTheShortcutAutomatically() {
        ActionRegistry actions = new ActionRegistry();
        actions.register("export", "导出", "数据",
                KeyCombination.keyCombination("Shortcut+E"), () -> { });
        AtomicReference<Button> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(actions.iconButton("export", Icons.Icon.REFRESH,
                "导出", "导出")));
        WaitForAsyncUtils.waitForFxEvents();
        String tooltip = ref.get().getTooltip().getText();
        assertThat(tooltip)
                .as("注册表生成的图标按钮：快捷键是白送的，调用方漏不了")
                .isEqualTo("导出 (" + Shortcuts.of("Mod+E") + ")");
    }

    /**
     * ⛔ 注册表已经自动追加，调用方再手打一份就是「保存 (Ctrl+S) (Ctrl+S)」。
     * 门禁扫源码：同一行里出现 iconButton( 与 Shortcuts.labelled 即红。
     */
    @Test
    void noCallSiteHandPatchesAShortcutIntoARegistryTooltip() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .anyMatch(line -> line.contains("iconButton(")
                                            && line.contains("Shortcuts.labelled"));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders)
                .as("注册表图标按钮不许再手打快捷键（会追加成双份）")
                .isEmpty();
    }
}
