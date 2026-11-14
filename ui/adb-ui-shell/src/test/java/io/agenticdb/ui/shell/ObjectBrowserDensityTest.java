package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.asset.ObjectStoreProvider;
import io.agenticdb.objectstore.ObjectEntry;
import io.agenticdb.objectstore.ObjectStoreCredentials;
import io.agenticdb.objectstore.ObjectStoreSessions;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 对象浏览器的密度与可读性门禁（实拍 p10-object-store-real 复盘）。
 *
 * <p>三件事以前全靠肉眼看，谁改回去都不会红：
 * <ol>
 *   <li>表格没有专用样式类 → 吃 Modena 默认的 13px 正文 / 大内边距，
 *       与左边 12px / 21px 的对象树不是一套密度；</li>
 *   <li>11 颗带文字的按钮挤一行，HBox 按 minWidth 把它们压成
 *       "Do…" / "Temporar…" / "Move/re…"，动作名在画面上丢失；</li>
 *   <li>短标签把完整动作名丢了 —— tooltip 与无障碍读数必须补回来。</li>
 * </ol>
 *
 * <p>这里读的是<b>计算样式与布局后的真实宽度</b>（挂了 theme-light + app.css），
 * 不是只看源码里有没有某个字符串。
 */
@ExtendWith(ApplicationExtension.class)
class ObjectBrowserDensityTest {

    private ObjectBrowserPage page;
    private Scene scene;

    @Start
    void start(Stage stage) {
        ConnectionProfile profile = new ConnectionProfile(
                "os-1", "对象存储", AssetKind.OBJECT_STORE,
                "endpoint.example.com", 443, "assets", "ak", false, 0, "",
                Map.of(ObjectStoreCredentials.OPT_PROVIDER,
                        ObjectStoreProvider.S3.id()), SshConfig.NONE);
        TabItem tab = new TabItem("object-browser:os-1:assets", "assets",
                TabKind.OBJECT_BROWSER, "assets", "os-1");
        page = new ObjectBrowserPage(tab, profile,
                () -> ObjectStoreSessions.inMemory(ObjectStoreProvider.S3));

        VBox root = new VBox(page);
        // ⭐ 不挂样式表时 CSS 一条都不生效，字号 / 按钮宽度的断言会全部假绿。
        scene = new Scene(root, 1024, 700);
        scene.getStylesheets().add(
                getClass().getResource("/io/agenticdb/ui/theme-light.css").toExternalForm());
        scene.getStylesheets().add(
                getClass().getResource("/io/agenticdb/ui/app.css").toExternalForm());
        root.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /** 场景图的写入 / 布局必须在 FX 线程（VirtualFlow 会在这里增删单元格）。 */
    private void layout() {
        onFxRun(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    /** 一行按钮的几何快照：拿回主线程再断言，异常才不会被 FX 线程吞掉。 */
    private record ButtonState(String text, double minWidth, double width,
                               double prefWidth, boolean hasTooltip, String accessibleText) {
    }

    /**
     * 文字按钮一律 {@code minWidth = USE_PREF_SIZE}；布局后的实得宽度不许小于
     * 自己的 prefWidth —— 小于就意味着 HBox 压过它，LabeledText 会画省略号。
     */
    @Test
    void toolbarActionsAreNeverSqueezedIntoEllipsis() {
        layout();
        List<ButtonState> states = onFx(() -> page.lookupAll(".adb-objects__action").stream()
                .map(node -> {
                    Button button = (Button) node;
                    return new ButtonState(button.getText(), button.getMinWidth(),
                            button.getWidth(), button.prefWidth(-1),
                            button.getTooltip() != null, button.getAccessibleText());
                }).toList());
        assertThat(states).as("7 个动作键 + 2 个导航键都在场景图里").hasSize(9);
        for (ButtonState state : states) {
            if (state.text().isEmpty()) {
                // 纯图标键由 .adb-button--icon 的 30px 下限守着，不需要 USE_PREF_SIZE。
                assertThat(state.minWidth())
                        .as("纯图标导航键不该被压到 30px 以下").isGreaterThanOrEqualTo(30.0);
            } else {
                assertThat(state.minWidth())
                        .as("%s 不许被 HBox 压缩", state.text())
                        .isEqualTo(Region.USE_PREF_SIZE);
            }
            assertThat(state.width() + 1.0)
                    .as("%s 实得宽度小于 prefWidth → 文字会被省略号截断",
                            state.text().isEmpty() ? "icon" : state.text())
                    .isGreaterThanOrEqualTo(state.prefWidth());
            assertThat(state.hasTooltip())
                    .as("%s 短标签必须配完整动作名提示", state.text())
                    .isTrue();
            assertThat(state.accessibleText())
                    .as("%s 读屏也要拿到完整动作名", state.text())
                    .isNotBlank();
        }
    }

    /** 表格正文与侧栏对象树同为 12px；列头也在同一档。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tableBodyMatchesTheTreeFontSize() throws IOException {
        layout();
        onFxRun(() -> {
            TableView table = (TableView) page.lookup(".adb-objects__table");
            table.getItems().add(ObjectEntry.folder("seed/"));
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
        WaitForAsyncUtils.waitForFxEvents();

        List<Double> fonts = onFx(() -> {
            TableView table = (TableView) page.lookup(".adb-objects__table");
            var sizes = new java.util.ArrayList<Double>();
            for (Node node : table.lookupAll(".table-cell")) {
                if (node instanceof Labeled labeled) {
                    sizes.add(labeled.getFont().getSize());
                }
            }
            for (Node node : table.lookupAll(".column-header .label")) {
                if (node instanceof Labeled labeled) {
                    sizes.add(labeled.getFont().getSize());
                }
            }
            return sizes;
        });
        assertThat(fonts).as("播种一行后应当有真实单元格与列头").isNotEmpty();
        assertThat(fonts).allSatisfy(size ->
                assertThat(size)
                        .as("对象表正文 / 列头必须与树同为 12px（不是 Modena 的 13px）")
                        .isEqualTo(12.0));

        // 静态口径：CSS 里两条规则写的是同一个字号（防止上面只量到继承值）。
        assertThat(fontSizeOf(".adb-objects__table .table-cell")).isEqualTo("12px");
        assertThat(fontSizeOf(".adb-tree__name")).isEqualTo("12px");
        assertThat(fontSizeOf(".adb-button.adb-objects__action")).isEqualTo("12px");
    }

    /** 三档行高与树同量级，默认 standard 从 32 收到 24。 */
    @Test
    void densityRowsAreCompactAndFollowTheSetting() {
        TableView<?> table = onFx(() -> (TableView<?>) page.lookup(".adb-objects__table"));
        assertThat(onFx(table::getFixedCellSize)).isEqualTo(24.0);
        onFxRun(() -> page.setDensity("compact"));
        assertThat(onFx(table::getFixedCellSize)).isEqualTo(21.0);
        onFxRun(() -> page.setDensity("loose"));
        assertThat(onFx(table::getFixedCellSize)).isEqualTo(32.0);
        onFxRun(() -> page.setDensity("standard"));
        assertThat(onFx(table::getFixedCellSize)).isEqualTo(24.0);
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static String fontSizeOf(String selector) throws IOException {
        String css;
        try (var in = ObjectBrowserDensityTest.class
                .getResourceAsStream("/io/agenticdb/ui/app.css")) {
            assertThat(in).as("app.css 必须在测试类路径上").isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher rule = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)\\}")
                .matcher(css);
        assertThat(rule.find()).as("找不到 CSS 规则 %s", selector).isTrue();
        Matcher font = Pattern.compile("-fx-font-size:\\s*([0-9.]+px)").matcher(rule.group(1));
        assertThat(font.find()).as("%s 必须显式写字号", selector).isTrue();
        return font.group(1);
    }
}
