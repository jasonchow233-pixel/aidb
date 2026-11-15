package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.asset.ObjectStoreProvider;
import io.agenticdb.objectstore.ObjectEntry;
import io.agenticdb.objectstore.ObjectMeta;
import io.agenticdb.objectstore.ObjectStoreCredentials;
import io.agenticdb.objectstore.ObjectStoreSessions;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 对象列表行首字形门禁：文件夹、常见文件类型、<b>认不出类型</b>都必须有图标。
 *
 * <p>实拍 p10-object-store-real 里 Key 列只有纯文字 —— 文件夹行靠 "Folder  " 前缀区分，
 * 无扩展名的对象则完全没有任何类型线索。这里同时钉三件事：
 * <ol>
 *   <li>六档分类（folder / image / text / code / unknown / file）映射不退化；</li>
 *   <li>场景里的每一行真的挂了 SVGPath 图标，且有类型色样式类、tooltip 与读屏文本；</li>
 *   <li>CSS 与中英文案都覆盖全部六档，不留「定义了却没样式 / 没文案」的半套。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class ObjectBrowserIconsTest {

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
        scene = new Scene(root, 1024, 700);
        scene.getStylesheets().add(
                getClass().getResource("/io/agenticdb/ui/theme-light.css").toExternalForm());
        scene.getStylesheets().add(
                getClass().getResource("/io/agenticdb/ui/app.css").toExternalForm());
        root.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void everyKindIncludingUnknownGetsItsOwnVisual() {
        Map<String, ObjectBrowserPage.ObjectVisual> cases = new LinkedHashMap<>();
        cases.put("logo.PNG", ObjectBrowserPage.ObjectVisual.IMAGE);
        cases.put("README.md", ObjectBrowserPage.ObjectVisual.TEXT);
        cases.put("data/config.json", ObjectBrowserPage.ObjectVisual.CODE);
        cases.put("LICENSE", ObjectBrowserPage.ObjectVisual.UNKNOWN);
        cases.put("trailing.", ObjectBrowserPage.ObjectVisual.UNKNOWN);
        cases.put(".gitignore", ObjectBrowserPage.ObjectVisual.UNKNOWN);
        cases.put("archive.zip", ObjectBrowserPage.ObjectVisual.FILE);

        for (Map.Entry<String, ObjectBrowserPage.ObjectVisual> c : cases.entrySet()) {
            assertThat(ObjectBrowserPage.visualFor(object(c.getKey())))
                    .as("key=%s 的类型字形", c.getKey())
                    .isEqualTo(c.getValue());
        }
        assertThat(ObjectBrowserPage.visualFor(ObjectEntry.folder("reports/2026/")))
                .isEqualTo(ObjectBrowserPage.ObjectVisual.FOLDER);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void everyRenderedRowCarriesAnIconAndATypeHint() {
        List<ObjectEntry> rows = List.of(
                ObjectEntry.folder("brand/"),
                object("logo.png"), object("README.md"), object("data/config.json"),
                object("LICENSE"), object("archive.zip"));
        onFxRun(() -> {
            TableView table = (TableView) page.lookup(".adb-objects__table");
            table.getItems().setAll(rows);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // 只取 Key 列的单元格：它们带行首字形，其它三列不该画图标。
        List<CellState> states = onFx(() -> {
            TableView table = (TableView) page.lookup(".adb-objects__table");
            var out = new java.util.ArrayList<CellState>();
            for (Node node : table.lookupAll(".table-cell")) {
                if (!(node instanceof TableCell<?, ?> cell)) {
                    continue;
                }
                String key = cell.getText();
                if (key == null || !rows.stream().anyMatch(r -> r.key().equals(key))) {
                    continue;
                }
                Node graphic = cell.getGraphic();
                out.add(new CellState(key,
                        graphic instanceof SVGPath,
                        graphic == null ? List.of() : List.copyOf(graphic.getStyleClass()),
                        cell.getTooltip() == null ? null : cell.getTooltip().getText(),
                        cell.getAccessibleText()));
            }
            return out;
        });
        assertThat(states).as("播种 6 行后每一行 Key 单元格都该被渲染").hasSize(6);
        for (CellState state : states) {
            assertThat(state.hasSvgIcon())
                    .as("%s 行首必须是矢量图标，不能是文字前缀", state.key()).isTrue();
            assertThat(state.styleClasses())
                    .as("%s 图标要挂类型色样式类", state.key())
                    .contains("adb-objects__icon")
                    .anySatisfy(c -> assertThat(c).startsWith("adb-objects__icon--"));
            assertThat(state.tooltip())
                    .as("%s 要有可读的类型提示", state.key()).isNotBlank();
            assertThat(state.accessibleText())
                    .as("%s 读屏也要拿到类型", state.key()).isNotBlank();
        }
        // 「认不出类型」这一档必须和通用文件用不同图标，否则等于没告诉用户。
        CellState unknown = states.stream().filter(s -> s.key().equals("LICENSE")).findFirst().orElseThrow();
        assertThat(unknown.styleClasses()).contains("adb-objects__icon--unknown");
        assertThat(unknown.tooltip())
                .as("未知类型提示要真的说清「未知 / Unknown」，不能只写个文件")
                .matches(".*(未知|Unknown).*");
    }

    @Test
    void allSixVisualsHaveCssAndBothLocales() throws IOException {
        String css;
        try (var in = getClass().getResourceAsStream("/io/agenticdb/ui/app.css")) {
            assertThat(in).isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (ObjectBrowserPage.ObjectVisual visual : ObjectBrowserPage.ObjectVisual.values()) {
            assertThat(css).as("%s 缺少 CSS 规则", visual)
                    .contains("." + visual.styleClass);
            assertThat(I18n.keys(Locale.ENGLISH))
                    .as("%s 缺英文类型文案", visual).contains(visual.labelKey);
            assertThat(I18n.keys(Locale.CHINESE))
                    .as("%s 缺中文类型文案", visual).contains(visual.labelKey);
        }
        // 五枚新字形必须有真实 Lucide 资源，且尺寸走命名档（不写死数字）。
        for (Icons.Icon icon : List.of(Icons.Icon.FILE, Icons.Icon.FILE_IMAGE,
                Icons.Icon.FILE_TEXT, Icons.Icon.FILE_CODE, Icons.Icon.FILE_UNKNOWN)) {
            assertThat(icon.path()).as("%s 路径", icon).isNotBlank();
        }
        assertThat(Icons.SIZE_LABEL).isEqualTo(14);
    }

    private record CellState(String key, boolean hasSvgIcon, List<String> styleClasses,
                             String tooltip, String accessibleText) {
    }

    private static ObjectEntry object(String key) {
        return ObjectEntry.object(new ObjectMeta(key, 42L, Instant.now(), "etag", "", ""));
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
}
