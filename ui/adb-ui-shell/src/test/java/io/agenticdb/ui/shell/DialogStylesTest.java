package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐⭐ 弹窗主题门禁。
 *
 * <h2>为什么这道门禁必须在</h2>
 * JavaFX 的 looked-up color（{@code -adb-*}）定义在 {@code .theme-light} /
 * {@code .theme-dark} **类选择器**上，而主题类挂在**主场景根节点**上。
 * 弹窗有自己的 Scene 与根节点（DialogPane）—— 那个根节点上没有主题类，
 * 于是弹窗里所有 {@code -adb-*} 查找全部落空，声明被丢弃，
 * 弹窗退回 JavaFX 默认皮肤。
 *
 * <p><b>症状</b>：新建表弹窗没有边框、没有标题分隔、按钮全是默认灰钮，
 * 和主窗口完全不是一套东西 —— 而且**截图能看出来，行为门禁测不出来**
 * （控件都在、事件都绑着，只是没上样式）。
 *
 * <p>所以门禁断言四件事：样式表挂上了、主题类抄过去了、重复挂不重复、弹窗不比屏幕高。
 */
@ExtendWith(ApplicationExtension.class)
class DialogStylesTest {

    private Stage owner;

    @Start
    void start(Stage stage) {
        this.owner = stage;
        Scene scene = new Scene(new javafx.scene.layout.VBox(), 1280, 800);
        // 模拟真实外壳：根节点带主题类，场景挂三张样式表。
        scene.getStylesheets().addAll(
                "io/agenticdb/ui/theme-light.css",
                "io/agenticdb/ui/theme-dark.css",
                "io/agenticdb/ui/app.css");
        scene.getRoot().getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /** 在 FX 线程上建弹窗并套样式（Dialog 只能在 FX 线程上 new）。 */
    private Dialog<Void> styled(Stage ownerWindow) throws Exception {
        return WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> dialog = new Dialog<>();
            DialogStyles.apply(dialog, ownerWindow);
            return dialog;
        }).get();
    }

    @Test
    void theThemeClassIsCopiedOntoTheDialogPane(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(owner);
        assertThat(dialog.getDialogPane().getStyleClass())
                .as("主题类必须抄到 DialogPane —— 否则弹窗里所有 -adb-* 查找落空")
                .contains("theme-light", "adb-dialog");
    }

    @Test
    void theApplicationStylesheetsAreAttached(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(owner);
        assertThat(dialog.getDialogPane().getStylesheets())
                .as("弹窗必须继承应用样式表（主题表 + 业务表）")
                .isNotEmpty();
        assertThat(dialog.getDialogPane().getStylesheets())
                .anyMatch(sheet -> sheet.endsWith("app.css"));
    }

    @Test
    void applyingTwiceDoesNotDuplicateTheStylesheets(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> d = new Dialog<>();
            DialogStyles.apply(d, owner);
            DialogStyles.apply(d, owner);
            return d;
        }).get();
        long appSheets = dialog.getDialogPane().getStylesheets().stream()
                .filter(sheet -> sheet.endsWith("app.css")).count();
        assertThat(appSheets)
                .as("重复 apply 不许把样式表挂两遍（每次弹窗都翻倍）")
                .isEqualTo(1);
    }

    @Test
    void aDialogNeverGrowsTallerThanTheScreen(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(owner);
        double screenHeight = javafx.stage.Screen.getScreens().stream()
                .mapToDouble(screen -> screen.getBounds().getHeight()).max().orElse(1080);
        assertThat(dialog.getDialogPane().getMaxHeight())
                .as("弹窗比屏幕还高 → 底部按钮跑到屏幕外，用户点不到")
                .isLessThan(screenHeight);
    }

    @Test
    void theDialogKeepsItsOwnTitleAndContent(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> d = new Dialog<>();
            d.setTitle("标题");
            d.setHeaderText("题头");
            d.getDialogPane().setContent(new Label("正文"));
            DialogStyles.apply(d, owner);
            return d;
        }).get();
        assertThat(dialog.getTitle()).isEqualTo("标题");
        assertThat(dialog.getDialogPane().getContent())
                .as("套样式不许动内容")
                .isNotNull();
    }

    @Test
    void aNullOwnerDoesNotBreakTheDialog(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(null);
        assertThat(dialog.getDialogPane().getStyleClass()).contains("adb-dialog");
    }

    @Test
    void theDialogButtonStyleExistsInTheBusinessCss() {
        // 弹窗里的按钮必须走 .adb-dialog .button 规则（圆角、描边、主按钮实心）。
        String css = readAppCss();
        assertThat(css)
                .as("弹窗按钮要有自己的规则 —— 否则是 JavaFX 默认灰钮")
                .contains(".adb-dialog .button");
        assertThat(css)
                .as("弹窗主按钮必须是实心强调色")
                .contains(".adb-dialog .button:default");
    }

    /**
     * ⭐⭐ §七「统一所有弹窗」的结构性门禁：**没有哪颗弹窗可以忘了套主题**。
     *
     * <h2>为什么按文件计数</h2>
     * 上面每一条断言都只守「apply 做了什么」，守不住「有没有人调用它」。
     * 实测就是栽在这里：{@code TableDdl} 的重命名框、{@code StructurePage} 的改列名、
     * {@code ExportWizard} 的大表确认、{@code DataGridPage} 的高风险确认、
     * {@code AppShell.showInfo}（手写了一半：挂样式表 + 加 adb-dialog，**漏抄主题类**）
     * —— 六处各自长出一个 Modena 灰皮肤，于是「同一个软件里的弹窗像来自不同软件」。
     *
     * <h2>口径</h2>
     * 一个文件里「建弹窗」的次数（{@code new Alert} / {@code new TextInputDialog} /
     * {@code new Dialog<>} / {@code new ChoiceDialog} / {@code DestructiveConfirm.of}）
     * 必须 ≤ 「套主题」的次数（{@code DialogStyles.apply} 或走 {@code styleDialog} 助手）。
     * 这是**按文件**的粗算，不是按调用点：定义一行 {@code styleDialog(} 也会被计一次。
     * 它挡的是「新增一颗弹窗没人给它套皮肤」，不挡「套错了对象」——
     * 后者由上面那批运行时断言守。
     */
    @Test
    void everyDialogConstructionSiteGetsTheSharedTheme() throws Exception {
        Path root = repositoryRoot();
        Pattern construction = Pattern.compile(
                "new Alert\\(|new TextInputDialog\\(|new Dialog<|new ChoiceDialog|DestructiveConfirm\\.of\\(");
        Pattern styling = Pattern.compile("DialogStyles\\.apply\\(|styleDialog\\(");
        // DestructiveConfirm 自己只造 Alert，套样式是 5 个调用方的事（本门禁逐个查过它们）。
        Set<String> styledByCallers = Set.of(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/DestructiveConfirm.java");

        List<String> violations = new ArrayList<>();
        int constructions = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                // Windows 的 Path.toString() 用 `\`：不归一化就一个文件都扫不到（门禁假绿）。
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.endsWith(".java")
                        || !relative.contains("ui/adb-ui-shell/src/main/java")) {
                    continue;
                }
                String source = stripLineComments(Files.readString(path, StandardCharsets.UTF_8));
                int built = count(construction, source);
                if (built == 0 || styledByCallers.contains(relative)) {
                    continue;
                }
                constructions += built;
                int styled = count(styling, source);
                if (styled < built) {
                    violations.add(relative + "（建 " + built + " 个弹窗，只套了 " + styled + " 次）");
                }
            }
        }

        assertThat(constructions)
                .as("一个弹窗构造点都没扫到 —— 路径过滤失效，门禁是假绿")
                .isGreaterThan(20);
        assertThat(violations)
                .as("这些文件里有弹窗没套应用主题（会弹成 Modena 灰皮肤，§七）：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    /**
     * 去掉行注释再计数。
     *
     * <p>不剥的话，{@code // DialogStyles.apply(...)} 这种「注释掉的修复」会让门禁假绿 ——
     * 实测第一版就是这么漏掉变异的。
     */
    private static String stripLineComments(String source) {
        StringBuilder kept = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            int comment = line.indexOf("//");
            kept.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return kept.toString();
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int hits = 0;
        while (matcher.find()) {
            hits++;
        }
        return hits;
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("core"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }

    private static String readAppCss() {
        try (var stream = javafx.scene.control.Dialog.class
                .getResourceAsStream("/io/agenticdb/ui/app.css")) {
            assertThat(stream).as("app.css 必须在 classpath 上").isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
