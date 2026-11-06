package io.agenticdb.ui.shell;

import io.agenticdb.ui.Shortcuts;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R66 余账（H5-g）：F1 快捷键总表必须以 {@link ActionRegistry} 为唯一真相。
 *
 * <p>立案事实（2026-11-05 读码）：注册表里 21 个动作带加速器（{@code key("Shortcut+N")} 那批），
 * 而 F1 表把同一批键**又手抄了一遍**（{@code "Mod+N"}）—— 这一轮之前那张表零门禁。
 * 抄的那份不会自己跟着动：注册表改了键，菜单跟着改、表留在原地，
 * 症状是「表上写着 Ctrl+J，按下去跑的是 Ctrl+R」，且不报错。
 *
 * <p>⚠️ 两条实测口径（都不是显然的）：
 * <ol>
 *   <li><b>{@code Mod} 只有 {@link Shortcuts} 认，JavaFX 不认；反过来 {@code Shortcut}
 *       也只有 JavaFX 认。</b>
 *       {@code KeyCombination.keyCombination("Mod+N")} 直接抛
 *       {@code IllegalArgumentException: Cannot parse key binding Mod+N} ——
 *       所以注册表侧只能写 {@code Shortcut}，而表的渲染走 {@link Shortcuts}。
 *       改动前 {@code Shortcuts.of("Shortcut+N")} 给的是裸键 <b>{@code "N"}</b>：
 *       不认识的修饰键不报错、也不留痕，直接被后面的按键覆盖。
 *       两条通道要合流，{@link Shortcuts} 必须认 {@code Shortcut} 这个词
 *       （门禁第 2 条钉的就是这个，它在本轮改动前是真的红）。</li>
 *   <li><b>{@code KeyCombination.toString()} 回吐的是规范写法</b>
 *       （{@code key("Shift+Shortcut+R").toString() = "Shift+Shortcut+R"}），
 *       所以按键列能直接从加速器对象取，不需要再造一份文本。</li>
 * </ol>
 *
 * <p>读的是<b>注册表现值 ↔ 表内容</b>这一对关系，不是把 21 个键名抄进测试当期望值 ——
 * 那只会造出第三份真相。
 */
@ExtendWith({ApplicationExtension.class, IsolatedPreferencesExtension.class})
class ShortcutSheetTest {

    private static Path demoDirectory;

    @BeforeAll
    static void isolateDemoDatabase() throws IOException {
        demoDirectory = Files.createTempDirectory("adb-shortcut-sheet-");
        System.setProperty("adb.demo.db", demoDirectory.resolve("demo.db").toString());
        System.setProperty("adb.connections.file",
                demoDirectory.resolve("connections.json").toString());
    }

    @AfterAll
    static void cleanDemoDatabase() throws IOException {
        System.clearProperty("adb.demo.db");
        System.clearProperty("adb.connections.file");
        if (demoDirectory != null) {
            try (var paths = Files.walk(demoDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private AppShell shell;

    @Start
    void start(Stage stage) {
        shell = new AppShell(new SettingsStore(), new ThemeManager(new SettingsStore()));
        stage.setScene(new javafx.scene.Scene(shell, 1280, 800));
        stage.show();
    }

    /** 表里指向注册表的那些行（按声明顺序）。 */
    private List<String> actionIdsInSheet() {
        List<String> ids = new ArrayList<>();
        for (AppShell.ShortcutEntry entry : shell.shortcutEntries()) {
            if (entry.actionId() != null) {
                ids.add(entry.actionId());
            }
        }
        return ids;
    }

    /** 注册表里带加速器的动作 —— 它们必须全部出现在表里。 */
    private List<Action> acceleratedActions() {
        List<Action> list = new ArrayList<>();
        for (Action action : shell.actionRegistry().all()) {
            if (action.accelerator() != null) {
                list.add(action);
            }
        }
        return list;
    }

    @Test
    void everyAcceleratedActionHasItsOwnRow() {
        List<String> ids = actionIdsInSheet();
        assertThat(new LinkedHashSet<>(ids))
                .as("同一个动作在表里摆两行（一行读注册表、一行还留着抄的字符串）")
                .hasSameSizeAs(ids);

        List<String> missing = new ArrayList<>();
        for (Action action : acceleratedActions()) {
            if (!ids.contains(action.id())) {
                missing.add(action.id() + " → " + action.accelerator());
            }
        }
        assertThat(missing)
                .as("注册表里有加速器、F1 表却没这一行：菜单改了键而表留在原地")
                .isEmpty();
    }

    @Test
    void registryKeyNamesRenderToAPlatformReading() {
        // ⭐ 合流的那一步：表的按键列现在读的是注册表的加速器，
        //    加速器写的是 "Shortcut+…"（JavaFX 只认这个），所以 Shortcuts 必须认它。
        //    ⚠️ 实测这条为什么必须双向查：{@code Shortcuts.of("Shortcut+N")} 在改动前给出的是
        //    <b>"N"</b> —— 不认识的修饰键不是原样印出，而是<b>被静默丢掉</b>
        //    （token 落到 default 分支，后面的按键把 key 覆盖掉）。
        //    所以界面上拿到的是「N」这种没有修饰键的裸键，既不报错也不留内部名。
        List<String> wrong = new ArrayList<>();
        for (Action action : acceleratedActions()) {
            String spec = action.accelerator().toString();
            String text = Shortcuts.of(spec);
            if (text.contains("Shortcut") || text.contains("Mod")) {
                wrong.add(action.id() + "：" + spec + " → 内部名上屏「" + text + "」");
                continue;
            }
            String bare = bareKey(spec);
            if (bare != null && !spec.equalsIgnoreCase(bare) && text.equals(Shortcuts.of(bare))) {
                wrong.add(action.id() + "：" + spec + " → 修饰键被丢掉，读成「" + text + "」");
            }
        }
        assertThat(wrong)
                .as("这些注册表的按键写法没被 Shortcuts 认下来，F1 表会印错按键")
                .isEmpty();
    }

    /** 去掉修饰键之后剩下的那部分（{@code "Shift+Shortcut+R"} → {@code "R"}）。 */
    private static String bareKey(String spec) {
        List<String> parts = new ArrayList<>();
        for (String raw : spec.split("\\+")) {
            switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "MOD", "SHORTCUT", "SHIFT", "ALT", "CTRL", "CONTROL", "META", "CMD",
                     "COMMAND", "OPTION" -> {
                    // 修饰键：不进裸键
                }
                default -> {
                    if (!raw.isBlank()) {
                        parts.add(raw.trim());
                    }
                }
            }
        }
        return parts.isEmpty() ? null : String.join("+", parts);
    }

    @Test
    void codeLevelRowsUseKeyNamesTheFormatterKnows() {
        // 网格 / 编辑器自己的按键（Mod+Z、Mod+ENTER、Mod+SPACE…）不进注册表，
        // 只能留在表里当字面量；但它们同样必须能被读成平台按键 ——
        // 写错一个词（"Mod+Controll"）以前是静默原样上屏。
        List<String> unknown = new ArrayList<>();
        for (AppShell.ShortcutEntry entry : shell.shortcutEntries()) {
            if (entry.keySpec() == null) {
                continue;
            }
            String text = Shortcuts.of(entry.keySpec());
            if (text.contains("Mod") || text.contains("Shortcut")) {
                unknown.add(entry.keySpec() + " → " + text);
            }
        }
        assertThat(unknown)
                .as("字面量行里有 Shortcuts 不认的按键名")
                .isEmpty();
    }

    @Test
    void noTwoRowsClaimTheSameKey() {
        Map<String, String> seen = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        for (AppShell.ShortcutEntry entry : shell.shortcutEntries()) {
            if (entry.isSection()) {
                continue;
            }
            String key = shell.keyText(entry);
            String previous = seen.putIfAbsent(key, entry.label());
            if (previous != null) {
                clashes.add(key + "：「" + previous + "」与「" + entry.label() + "」");
            }
        }
        assertThat(clashes)
                .as("同一颗键在表里对应两个动作，用户照表按键只会得到其中一个")
                .isEmpty();
    }

    /** 分节标题：表里有四节，测试按标题文案认它们。 */
    private static final List<String> SECTION_KEYS = List.of(
            "shortcut.section.session", "shortcut.section.editing", "menu.query", "menu.view");

    /** 分节标题的实际文案（走 I18n，不把英文串写死进测试）。 */
    private static List<String> sectionTitles() {
        List<String> titles = new ArrayList<>();
        for (String key : SECTION_KEYS) {
            titles.add(io.agenticdb.ui.I18n.t(key));
        }
        return titles;
    }

    @Test
    void rowsAreGroupedUnderSections() {
        List<String> titles = sectionTitles();
        Set<String> seen = new LinkedHashSet<>();
        List<AppShell.ShortcutEntry> rows = shell.shortcutEntries();
        assertThat(titles)
                .as("资源键必须每条都解析得出：解析不出来的键会原样返回键名，"
                        + "那时「四节」会被同一串冒充，断言就空转了")
                .allMatch(title -> !title.isBlank() && !title.startsWith("shortcut.")
                        && !title.startsWith("menu."));
        assertThat(rows.get(0).isSection())
                .as("第一行必须是分节标题：表不能有一节不明的开头")
                .isTrue();
        List<String> orphan = new ArrayList<>();
        String current = null;
        for (AppShell.ShortcutEntry entry : rows) {
            if (entry.isSection()) {
                current = entry.label();
                assertThat(titles).as("表里冒出一个不认识的节标题：" + current).contains(current);
                seen.add(current);
                continue;
            }
            if (current == null) {
                orphan.add(entry.label());
            }
            assertThat(entry.label()).as("每一行都得有文案").isNotBlank();
        }
        assertThat(orphan).as("有行落在任何分节之前").isEmpty();
        assertThat(new ArrayList<>(seen))
                .as("分节清单与表里的节标题一一对应（少一节 = 有行动了家）")
                .containsExactlyInAnyOrderElementsOf(titles);
    }
}
