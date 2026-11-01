package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.stage.Stage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 弹窗按钮排布的门禁（任务书 §6.7「主按钮放底部右侧」，用户裁定全应用按 Navicat 口径）。
 *
 * <h2>为什么钉「视觉位置」而不是「按钮存在」</h2>
 * JavaFX 的 Windows 预设把主动作（OK_DONE）排在取消（CANCEL_CLOSE）<b>左边</b> ——
 * 「确定 | 取消」；Navicat 是「取消 | 确定」，主动作贴右下。
 * 按钮一个不缺、事件全都绑着、行为测试全绿，<b>只有截图和手感能看出排反了</b>。
 * 所以这里断言皮肤真正摆完之后每颗按钮的坐标。
 *
 * <h2>两条排布路子都要守</h2>
 * <ol>
 *   <li>{@link DialogPane} 弹窗：位置由 JavaFX 的 button order 串决定，
 *       统一在 {@link DialogStyles#apply} 里换槽；</li>
 *   <li>清空 buttonTypes、自己拼底部 HBox 的弹窗（导入 / 导出向导、收藏夹、已保存查询）：
 *       位置由 HBox 的 children 顺序决定，跟 JavaFX 无关。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class DialogButtonOrderTest {

    /** JavaFX 25 的 Windows 预设原文 —— 钉死它：官方哪天改了这里先红，别让生产代码静默失效。 */
    private static final String JAVA_FX_WINDOWS_PRESET = "L_E+U+FBXI_YNOCAH_R";

    private static final double FOOTER_WIDTH = 620;

    @TempDir
    static Path temp;

    private static Stage stage;

    @Start
    void start(Stage primaryStage) {
        stage = primaryStage;
        StackPane root = new StackPane();
        root.getStyleClass().add("theme-light");
        Scene scene = new Scene(root, 980, 680);
        scene.getStylesheets().addAll(
                resource("/io/agenticdb/ui/theme-light.css"),
                resource("/io/agenticdb/ui/app.css"));
        stage.setScene(scene);
        stage.show();
    }

    private static String resource(String path) {
        java.net.URL url = DialogButtonOrderTest.class.getResource(path);
        assertThat(url).as("样式资源 %s", path).isNotNull();
        return url.toExternalForm();
    }

    // ───────────────────── ① DialogPane：取消真的摆在确定左边 ─────────────────────

    @Test
    @DisplayName("套过样式的弹窗：取消在左、主动作贴右下")
    void cancelButtonSitsLeftOfThePrimaryButton() {
        Dialog<String> dialog = onFx(() -> {
            Dialog<String> d = new Dialog<>();
            d.getDialogPane().getButtonTypes().setAll(
                    new ButtonType("确定", ButtonBar.ButtonData.OK_DONE),
                    new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE));
            DialogStyles.apply(d, stage);
            return d;
        });

        Map<String, Double> xs = laidOutButtonX(dialog.getDialogPane());

        assertThat(xs.keySet())
                .as("皮肤没摆出两颗按钮 —— 断言会空转（假绿）")
                .containsExactlyInAnyOrder("确定", "取消");
        assertThat(xs.get("取消"))
                .as("Navicat 口径：取消在左、主动作在右")
                .isLessThan(xs.get("确定"));
    }

    @Test
    @DisplayName("主动作贴着按钮区右边缘，不是排在左边或中间")
    void thePrimaryButtonHugsTheRightEdge() {
        Dialog<String> dialog = onFx(() -> {
            Dialog<String> d = new Dialog<>();
            d.getDialogPane().getButtonTypes().setAll(
                    new ButtonType("确定", ButtonBar.ButtonData.OK_DONE),
                    new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE));
            DialogStyles.apply(d, stage);
            return d;
        });

        laidOutButtonX(dialog.getDialogPane());
        double[] okRightInBarAndBarWidth = onFx(() -> {
            ButtonBar bar = buttonBarOf(dialog.getDialogPane());
            Button ok = buttonByText(bar, "确定");
            double okRight = ok.localToScene(0, 0).getX() + ok.getWidth();
            return new double[] {okRight - bar.localToScene(0, 0).getX(), bar.getWidth()};
        });
        assertThat(okRightInBarAndBarWidth[0])
                .as("主动作右边缘要贴着按钮区（§6.7「底部右侧」）")
                .isGreaterThanOrEqualTo(okRightInBarAndBarWidth[1] - 24);
    }

    /**
     * ⭐ R34 收官：{@code Alert} / {@code TextInputDialog} 那几颗<b>JavaFX 自带</b>的
     * 标准按钮，文字必须来自我们的资源包，而不是 JVM 默认 Locale。
     *
     * <p>「确定 / 取消」从来不在源码里 —— 它们是 {@link ButtonType#OK} 这类单例由
     * JavaFX 自己的 bundle 按 <b>JVM Locale</b> 解析出来的，所以 25 批 i18n 全绿也拦不住：
     * 界面默认英文、弹窗按钮却跟着系统语言走（本机 zh_CN 实拍 p2-error / p6-rename-table
     * 两张帧抓到）。这条断言连 JVM 语言都不设，只比「按钮文字 == 资源包」，
     * 机器是中文还是日文都该绿。
     *
     * <p>另一半同样要守：业务弹窗里 {@code new ButtonType("添加", OK_DONE)} 那种自定义，
     * 文字本来就在我们手里，按 ButtonData 一律覆写会把它改成「OK」。
     */
    @Test
    @DisplayName("标准按钮（OK / CANCEL / CLOSE）走资源包，自定义按钮一个字不动")
    void standardButtonsComeFromOurBundleNotTheJvmLocale() {
        Map<String, String> texts = onFx(() -> {
            Dialog<String> d = new Dialog<>();
            d.getDialogPane().getButtonTypes().setAll(
                    ButtonType.OK, ButtonType.CANCEL, ButtonType.CLOSE);
            DialogStyles.apply(d, stage);
            DialogPane pane = d.getDialogPane();
            Map<String, String> out = new LinkedHashMap<>();
            out.put("ok", ((Button) pane.lookupButton(ButtonType.OK)).getText());
            out.put("cancel", ((Button) pane.lookupButton(ButtonType.CANCEL)).getText());
            out.put("close", ((Button) pane.lookupButton(ButtonType.CLOSE)).getText());
            return out;
        });

        assertThat(texts.get("ok")).isEqualTo(io.agenticdb.ui.I18n.t("common.ok"));
        assertThat(texts.get("cancel")).isEqualTo(io.agenticdb.ui.I18n.t("common.cancel"));
        assertThat(texts.get("close")).isEqualTo(io.agenticdb.ui.I18n.t("common.close"));

        String custom = onFx(() -> {
            Dialog<String> d = new Dialog<>();
            ButtonType add = new ButtonType("添加", ButtonBar.ButtonData.OK_DONE);
            d.getDialogPane().getButtonTypes().setAll(add);
            DialogStyles.apply(d, stage);
            return ((Button) d.getDialogPane().lookupButton(add)).getText();
        });
        assertThat(custom)
                .as("自定义的 OK_DONE 那颗（条件弹窗的「添加」就是它）不能被顶成「OK」")
                .isEqualTo("添加");
    }

    @Test
    @DisplayName("换槽只动 O / C 两个槽，其余槽一个不动")
    void theOrderStringOnlySwapsThoseTwoSlots() {
        assertThat(ButtonBar.BUTTON_ORDER_WINDOWS)
                .as("JavaFX 版本变了 —— DialogStyles 里的预设常量要跟着核对")
                .isEqualTo(JAVA_FX_WINDOWS_PRESET);

        Dialog<String> dialog = onFx(() -> {
            Dialog<String> d = new Dialog<>();
            d.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            // ⚠️ 显式喂 Windows 预设，而不是「把宿主平台的预设当成 Windows 预设」。
            //   ButtonBar 的初始 order 串是**按平台**选的：macOS 上是
            //   "L_HE+U+FBIX_NYA_R"（里面既没有 'O' 也没有 'C'，长度也短 3），
            //   而 DialogStyles 对不认识的串**故意不动**（那是 anUnrecognisedPlatformOrderIsLeftAlone
            //   钉住的规则）。于是这条门禁在 macOS 上必然拿「没被改过的 macOS 串」去比 Windows 串
            //   —— 假红，且红得跟产品没关系（2026-10-30 实测：Windows 全绿、macOS 单条红）。
            //   这条要钉的是「给定 Windows 预设 → 只换 O / C 两槽」，与跑在哪个平台上无关；
            //   macOS 上按钮的真实位置由本类里断言坐标的那几条守（它们直接量 layoutX，
            //   与 order 串按平台选成什么无关）。
            buttonBarOf(d.getDialogPane()).setButtonOrder(JAVA_FX_WINDOWS_PRESET);
            DialogStyles.apply(d, stage);
            return d;
        });

        String applied = onFx(() -> buttonBarOf(dialog.getDialogPane()).getButtonOrder());
        assertThat(applied).hasSameSizeAs(JAVA_FX_WINDOWS_PRESET);
        assertThat(strip(applied))
                .as("除 O / C 之外的槽（H 帮助、A 应用、Y N 是否、间隔符）必须原样")
                .isEqualTo(strip(JAVA_FX_WINDOWS_PRESET));
        assertThat(applied.indexOf('C'))
                .as("取消 C 要在主动作 O 左边")
                .isLessThan(applied.indexOf('O'));
        assertThat(JAVA_FX_WINDOWS_PRESET.indexOf('C'))
                .as("前提是 JavaFX 默认确实把 C 排在 O 右边（不然这条门禁没意义）")
                .isGreaterThan(JAVA_FX_WINDOWS_PRESET.indexOf('O'));
    }

    @Test
    @DisplayName("认不出的平台预设保持原样，不猜一个把按钮排乱")
    void anUnrecognisedPlatformOrderIsLeftAlone() {
        Dialog<String> dialog = onFx(() -> {
            Dialog<String> d = new Dialog<>();
            d.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            buttonBarOf(d.getDialogPane()).setButtonOrder(ButtonBar.BUTTON_ORDER_MAC_OS);
            DialogStyles.apply(d, stage);
            return d;
        });

        assertThat(onFx(() -> buttonBarOf(dialog.getDialogPane()).getButtonOrder()))
                .as("不是我们认识的 Windows 预设就不要动它")
                .isEqualTo(ButtonBar.BUTTON_ORDER_MAC_OS);
    }

    @Test
    @DisplayName("换槽助手：两个字符都在才换，缺一个就整串原样返回")
    void swapCharHandlesMissingSlots() {
        assertThat(DialogStyles.swapChar("ABC", 'A', 'C')).isEqualTo("CBA");
        assertThat(DialogStyles.swapChar("AB", 'A', 'C'))
                .as("找不到第二个槽就原样返回，不能只换一半")
                .isEqualTo("AB");
        assertThat(DialogStyles.swapChar("", 'O', 'C')).isEmpty();
    }

    // ─────────── ② 自建底部按钮区的弹窗：同一口径（关闭在左、主动作在最右） ───────────

    @Test
    @DisplayName("导出 / 导入向导：关闭在左，开始导出 / 开始导入贴右下")
    void wizardFootersPutDismissLeftAndPrimaryRight() {
        assertFooter("导出", onFx(() -> new ExportWizard(FakeDbSession.of(DialectKind.SQLITE),
                "users", List.of("id"), ExportWizard.Scope.WHOLE_TABLE,
                temp.resolve("out.csv"), Runnable::run, message -> { }).content()),
                "export-close", "export-start");
        assertFooter("导入", onFx(() -> new ImportWizard(FakeDbSession.of(DialectKind.SQLITE),
                "users", List.of(column("id")), List.of("users"),
                temp.resolve("in.csv"), Runnable::run, message -> { }).content()),
                "import-close", "import-start");
    }

    @Test
    @DisplayName("收藏夹：关闭在左，主动作（打开）贴右下")
    void managerFootersPutDismissLeftAndPrimaryRight() {
        assertFooter("收藏夹", onFx(() -> new FavoritesPage(List.of("users"),
                name -> { }, message -> { }).content()), "favorites-close", "favorites-open");
    }

    @Test
    @DisplayName("导入导出入口 / 变量面板：同一口径，不留两颗特立独行的弹窗")
    void hubAndVariableFootersFollowTheSameRule() {
        assertFooter("导入导出入口", onFx(() -> new TransferHub(
                FakeDbSession.of(DialectKind.SQLITE), List.of("users"),
                Runnable::run, message -> { }).content()), "transfer-close", "transfer-export");
        assertFooter("变量面板", onFx(() -> new VariableDialog(
                io.agenticdb.sql.SqlParameters.detect("SELECT * FROM users WHERE id = :id"))
                .content()), "variable-cancel", "variable-ok");
    }

    @Test
    @DisplayName("连接向导：取消在左，下一步 / 确定贴右下")
    void connectionWizardFooterPutsDismissLeft() {
        ConnectionWizard wizard = onFx(() -> new ConnectionWizard(stage, null,
                io.agenticdb.session.CredentialStore.inMemory(), spec -> null));
        assertFooter("连接向导第 1 步", (Pane) onFx(wizard::stepContent),
                "wizard-cancel", "wizard-next");
    }

    // ─────────────── ③ 静态：不许有弹窗自己改序串 / 悄悄多出一套底部区 ───────────────

    @Test
    @DisplayName("只有 DialogStyles 能改按钮序串，别处一律不许")
    void noDialogForksTheButtonOrderItself() {
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        for (Path path : productionSources()) {
            Matcher callsInFile = Pattern.compile("setButtonOrder\\(")
                    .matcher(stripLineComments(read(path)));
            while (callsInFile.find()) {
                calls++;
                if (!path.getFileName().toString().equals("DialogStyles.java")) {
                    offenders.add(rootRelativized(path));
                }
            }
        }
        assertThat(calls)
                .as("一个 setButtonOrder 都没扫到 —— 扫描路径失效，门禁是假绿")
                .isPositive();
        assertThat(offenders)
                .as("按钮序只在 DialogStyles 一处定义，个别弹窗自己改就会全应用两套手感：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("自建底部按钮区的弹窗就这六处，多一处必须一起来这里挂号")
    void theCustomFooterSetIsStillTheKnownSix() {
        List<String> files = new ArrayList<>();
        for (Path path : productionSources()) {
            if (stripLineComments(read(path)).contains("getButtonTypes().clear()")) {
                files.add(path.getFileName().toString());
            }
        }
        assertThat(files)
                .as("清空 JavaFX 按钮栏、自己排按钮的弹窗清单变了 —— 上面的视觉门禁要跟着加")
                .containsExactlyInAnyOrder("ConnectionWizard.java", "ExportWizard.java",
                        "ImportWizard.java", "FavoritesPage.java",
                        "TransferHub.java", "VariableDialog.java");
    }

    // ───────────────────────────────── 助手 ─────────────────────────────────

    /** 把弹窗挂进真场景、强制排一遍，返回「按钮文字 -> 场景内 x」。 */
    private static Map<String, Double> laidOutButtonX(DialogPane pane) {
        onFxRun(() -> ((StackPane) stage.getScene().getRoot()).getChildren().setAll(pane));
        WaitForAsyncUtils.waitForFxEvents();
        Map<String, Double> positions = onFx(() -> {
            ButtonBar bar = buttonBarOf(pane);
            pane.applyCss();
            pane.resizeRelocate(0, 0, 560, 240);
            pane.layout();
            bar.applyCss();
            bar.resizeRelocate(0, 0, Math.max(bar.prefWidth(-1), 520), 36);
            bar.layout();
            Map<String, Double> map = new LinkedHashMap<>();
            for (Node child : bar.getButtons()) {
                if (child instanceof Button button && button.isVisible()) {
                    map.put(button.getText(), button.localToScene(0, 0).getX());
                }
            }
            return map;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return positions;
    }

    /** DialogPane 构造时把 ButtonBar 直接挂成子节点 —— 测试自己遍历找，不复用生产里的查找。 */
    private static ButtonBar buttonBarOf(DialogPane pane) {
        for (Node child : pane.getChildren()) {
            if (child instanceof ButtonBar bar) {
                return bar;
            }
        }
        throw new IllegalStateException("DialogPane 上没有 ButtonBar，JavaFX 结构变了");
    }

    /** 强制排完自建底部区，断言「关闭在左、主动作在最右且贴右边缘」。 */
    private static void assertFooter(String label, Pane content,
                                     String dismissId, String primaryId) {
        HBox actions = onFx(() -> {
            content.applyCss();
            return footerOf(content, primaryId);
        });
        onFxRun(() -> {
            actions.applyCss();
            actions.resizeRelocate(0, 0, FOOTER_WIDTH, 36);
            actions.layout();
        });
        Map<String, Button> buttons = onFx(() -> {
            Map<String, Button> found = new LinkedHashMap<>();
            found.put(dismissId, required(actions, dismissId));
            found.put(primaryId, required(actions, primaryId));
            return found;
        });
        Button dismiss = buttons.get(dismissId);
        Button primary = buttons.get(primaryId);
        assertThat(primary.getLayoutX())
                .as(label + "：主动作不能在关闭左边")
                .isGreaterThan(dismiss.getLayoutX());
        assertThat(primary.getLayoutX() + primary.getWidth())
                .as(label + "：主动作要贴着底部区右边缘（§6.7「底部右侧」）")
                .isGreaterThan(FOOTER_WIDTH - 24);
    }

    /** 从按钮往上找到那颗装底部按钮的 HBox。 */
    private static HBox footerOf(Pane content, String buttonId) {
        Button button = required(content, buttonId);
        Parent parent = button.getParent();
        assertThat(parent).as("%s 的直接父节点就是底部按钮区", buttonId).isInstanceOf(HBox.class);
        return (HBox) parent;
    }

    /** 按 id 递归找按钮 —— 不用 lookup，免得依赖场景与 CSS 状态。 */
    private static Button required(Parent parent, String id) {
        Button found = findById(parent, id);
        assertThat(found).as("%s 里找不到 #%s", parent.getClass().getSimpleName(), id).isNotNull();
        return found;
    }

    private static Button buttonByText(ButtonBar bar, String text) {
        for (Node child : bar.getButtons()) {
            if (child instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new IllegalStateException("按钮区里没有「" + text + "」，JavaFX 没摆出来");
    }

    private static Button findById(Parent parent, String id) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (id.equals(child.getId()) && child instanceof Button button) {
                return button;
            }
            if (child instanceof Parent nested) {
                Button found = findById(nested, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String strip(String order) {
        return order.replace("C", "").replace("O", "");
    }

    private static ColumnDef column(String name) {
        return new ColumnDef(name, ColumnType.TEXT, true, false, null, null,
                null, null, null, null, false);
    }

    private static List<Path> productionSources() {
        Path root = repositoryRoot();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        return relative.endsWith(".java")
                                && relative.contains("ui/adb-ui-shell/src/main/java");
                    })
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootRelativized(Path path) {
        return repositoryRoot().relativize(path).toString().replace('\\', '/');
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripLineComments(String source) {
        StringBuilder kept = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            int comment = line.indexOf("//");
            kept.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return kept.toString();
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

    private static <T> T onFx(Callable<T> action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<T> value = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                value.set(action.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        if (failure.get() != null) {
            rethrow(failure.get());
        }
        return value.get();
    }

    private static void onFxRun(Runnable action) {
        onFx(() -> {
            action.run();
            return null;
        });
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(throwable);
    }
}
