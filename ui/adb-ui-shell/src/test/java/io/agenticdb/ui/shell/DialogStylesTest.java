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

    /**
     * ⭐ R33：向导正文的 16px 留白必须真的落到屏幕上。
     *
     * <p>{@code .adb-dialog > .content { padding: 0 }} 是 (0,2,0)，裸的
     * {@code .adb-wizard__content} 只有 (0,1,0) —— 特异性输了，五个向导的正文
     * 一直贴着弹窗边缘（p8-import / p5-connect-* 实拍：标签几乎落在 x=0）。
     * JavaFX 里代码里 {@code setPadding(16)} 也压不住样式表（代码是 USER 来源，
     * 优先级最低），所以只能靠 selector 提权，也必须用**上屏后读到的 padding** 来钉。
     */
    @Test
    void wizardContentKeepsItsInsetInsideTheDialogPane(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        javafx.geometry.Insets padding = WaitForAsyncUtils.asyncFx((Callable<javafx.geometry.Insets>) () -> {
            javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(new Label("正文"));
            content.getStyleClass().add("adb-wizard__content");
            Dialog<Void> dialog = new Dialog<>();
            DialogStyles.apply(dialog, owner);
            dialog.getDialogPane().setContent(content);
            dialog.show();
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            javafx.geometry.Insets result = content.getPadding();
            dialog.hide();
            return result;
        }).get();
        assertThat(padding.getLeft())
                .as("向导正文左留白被 .adb-dialog > .content 的 padding:0 压掉了")
                .isEqualTo(16);
        assertThat(padding.getTop()).isEqualTo(16);
        assertThat(padding.getRight()).isEqualTo(16);
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

    /** U20：弹窗一律可缩放 —— 固定尺寸的弹窗放不下内容时只会裁掉一半。 */
    @Test
    void everyDialogIsResizable(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(styled(owner).isResizable())
                .as("apply 必须把弹窗设为可缩放（新建表 / 新建索引在 1024 高的屏上要能拉长）")
                .isTrue();
    }

    /**
     * U20：带 sizeKey 的弹窗，用户拉多大下次就多大。
     *
     * <p>⭐ U28（2026-10-26）：记的必须是**面板**尺寸。旧实现记窗口尺寸
     * （含 16px 标题栏/边框）却恢复成面板 prefSize —— 每开一次大 16px，
     * 新建索引从 720 棘轮到 912 才被实拍帧抓到。这里用「窗口 ≠ 面板」的
     * 两个不同值把方向钉死：只有面板值允许落进偏好。
     */
    @Test
    void aRememberedSizeRoundTrips(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        String key = "gate-" + System.nanoTime();
        Dialog<Void> first = WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> d = new Dialog<>();
            DialogStyles.apply(d, owner, key);
            // 模拟布局后的真实面板尺寸（用户拖完 JavaFX 就是这么改 pane 宽高的）；
            // 窗口属性故意给一个**不同**的值 —— 它不该进偏好。
            d.setWidth(900);
            d.setHeight(700);
            d.getDialogPane().resize(777, 511);
            return d;
        }).get();
        assertThat(first.getDialogPane().getWidth()).as("面板尺寸得真的变了").isEqualTo(777);

        Dialog<Void> second = WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> d = new Dialog<>();
            DialogStyles.apply(d, owner, key);
            return d;
        }).get();
        assertThat(second.getDialogPane().getPrefWidth())
                .as("第二次打开必须恢复**面板**尺寸（记窗口尺寸 = 每次 +16px 棘轮）").isEqualTo(777);
        assertThat(second.getDialogPane().getPrefHeight())
                .as("第二次打开必须恢复**面板**尺寸").isEqualTo(511);
    }

    /** 没传 sizeKey 的弹窗（提示框、确认框）不许被上一次的尺寸绑架。 */
    @Test
    void aDialogWithoutAKeyKeepsTheTierSize(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(owner);
        assertThat(dialog.getDialogPane().getPrefWidth())
                .as("未记忆的弹窗保持 -1（由内容 / 档位决定）")
                .isLessThanOrEqualTo(0);
    }

    /**
     * ⭐ U30（2026-10-26）：骨架 padding 必须**真的落到内容节点上**。
     *
     * <p>{@code DialogPane.setContent()} 会给内容节点补挂 {@code content} 类，
     * 于是 {@code .adb-dialog > .content { padding:0 }}（0,2,0）压过裸
     * {@code .adb-dialog__body}（0,1,0）—— 24px 内边距**从来没生效过**，
     * 实拍弹窗正文全部贴边，而全量门禁 967 条一条都没红。
     * 这条断言读的是**挂进 DialogPane 之后**的计算值，不是 CSS 文本里有没有那行。
     */
    @Test
    void theDialogBodyKeepsItsPaddingInsideThePane(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        javafx.geometry.Insets padding = WaitForAsyncUtils.asyncFx(() -> {
            Dialog<Void> d = new Dialog<>();
            javafx.scene.layout.VBox body =
                    new javafx.scene.layout.VBox(new Label("正文"));
            body.getStyleClass().add("adb-dialog__body");
            d.getDialogPane().setContent(body);
            DialogStyles.apply(d, owner);
            // Dialog 的 DialogPane 已挂在隐式弹窗场景上，不能再 new Scene 抢根节点；
            // 直接在面板上做 CSS + 布局，读的就是用户看到的那份计算值。
            d.getDialogPane().applyCss();
            d.getDialogPane().layout();
            return body.getPadding();
        }).get();
        assertThat(padding.getLeft())
                .as("骨架内边距被 .content 特异性压零 = 弹窗正文贴边（实拍抓到的）")
                .isEqualTo(24.0);
        assertThat(padding.getTop()).isEqualTo(24.0);
        assertThat(padding.getBottom()).as("底部收一档（16）是骨架口径").isEqualTo(16.0);
    }

    /** U20：标题栏的绝对路径按中部截断，完整值留在 tooltip。 */
    @Test
    void theDialogTitleTruncatesThePathInTheMiddle() throws Exception {
        String path = "C:\\Users\\Administrator\\.agenticdb\\agenticdb-demo.db";
        String text = "表 users · SQLite · " + path;
        String shortened = DialogStyles.shortenContext(text, 28);

        assertThat(shortened)
                .as("路径必须被压短，但文件名必须留着（尾部省略号会把文件名截掉）")
                .doesNotContain("Administrator")
                .endsWith("agenticdb-demo.db")
                .contains("…");
        assertThat(shortened)
                .as("非路径段（表名 / 方言）一个字都不许动")
                .startsWith("表 users · SQLite · ");
        assertThat(shortened.length()).isLessThan(text.length());

        // 不带路径的上下文原样返回，也不该长出 tooltip。
        Label plain = WaitForAsyncUtils.asyncFx(
                () -> DialogStyles.contextLabel("表 users · SQLite")).get();
        assertThat(plain.getText()).isEqualTo("表 users · SQLite");
        assertThat(plain.getTooltip()).as("没截断就不要挂 tooltip").isNull();

        Label withPath = WaitForAsyncUtils.asyncFx(() -> DialogStyles.contextLabel(text)).get();
        assertThat(withPath.getStyleClass()).contains("adb-dialog__context");
        assertThat(withPath.getTooltip()).as("截断过的必须能查到全路径").isNotNull();
        assertThat(withPath.getTooltip().getText()).isEqualTo(text);
    }

    /** U22：弹窗里的表头左对齐 —— Modena 默认居中，内容左对齐时看着像整列错位。 */
    @Test
    void dialogTableHeadersAreLeftAligned() {
        String css = readAppCss();
        assertThat(css)
                .as("弹窗表头必须有显式的左对齐规则（否则退回 Modena 的居中）")
                .contains(".adb-dialog .table-view .column-header .label");
        int at = css.indexOf(".adb-dialog .table-view .column-header .label");
        assertThat(css.substring(at, at + 200))
                .as("表头规则里必须真的写了 center-left")
                .contains("-fx-alignment: center-left");
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
