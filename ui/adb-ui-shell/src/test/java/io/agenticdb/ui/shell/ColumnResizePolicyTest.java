package io.agenticdb.ui.shell;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-6 的**策略门禁**：为什么是 {@code CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN}
 * 而不是 {@code UNCONSTRAINED}。
 *
 * <p>第二十轮实拍发现：{@code UNCONSTRAINED} 在挤窄时确实保住了列宽下限，
 * 但在**宽屏**下不再填满 —— 对象首页右侧留一大片空白（`p0-shell.png`）。
 * 这个探针把两种策略的实际行为钉下来，免得下次再凭印象改：
 *
 * <ul>
 *   <li>宽屏：各列按 pref 比例分配，总和 = 表格宽（**填满**）；</li>
 *   <li>窄屏：宽度**卡在 minWidth 不再往下走**，总和超出表格宽 → 出现横向滚动条。</li>
 * </ul>
 *
 * <p>⚠️ 表格宽度在这里用 {@code maxWidth} 钉死，不依赖窗口尺寸 ——
 * 全量跑时 primary stage 会沿用上一个测试类的尺寸（实测 1278 而不是 1200），
 * 靠窗口宽度断言会随机红。
 */
@ExtendWith(ApplicationExtension.class)
class ColumnResizePolicyTest {

    private static final double MIN_WIDTH = 96;
    private static final double PREF_WIDTH = 200;
    private static final int COLUMNS = 8;

    private TableView<String> table;
    /** 表格不能直接当场景根：根节点会被场景撑满，maxWidth 不生效。 */
    private StackPane root;

    @Start
    void start(Stage stage) {
        table = new TableView<>();
        for (int i = 0; i < COLUMNS; i++) {
            TableColumn<String, String> column = new TableColumn<>("c" + i);
            column.setPrefWidth(PREF_WIDTH);
            column.setMinWidth(MIN_WIDTH);
            table.getColumns().add(column);
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        root = new StackPane(table);
        root.setAlignment(Pos.TOP_LEFT);
        resizeTo(1200);
        stage.setScene(new Scene(root, 1400, 400));
        stage.show();
    }

    @Test
    void wideViewportFillsTheFullWidthInsteadOfLeavingABlankStrip() {
        resizeTo(1200);

        assertThat(totalWidth())
                .as("宽屏要填满表格 —— UNCONSTRAINED 会停在 pref 宽度，右侧留空白")
                .isCloseTo(table.getWidth(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(table.getColumns()).allSatisfy(column -> assertThat(column.getWidth())
                .as("%s 在宽屏下也不能低于可读下限", column.getText())
                .isGreaterThanOrEqualTo(MIN_WIDTH));
    }

    @Test
    void narrowViewportStopsAtTheMinimumAndScrollsHorizontally() {
        resizeTo(500);

        assertThat(table.getColumns()).allSatisfy(column -> assertThat(column.getWidth())
                .as("%s 挤窄时要卡在 minWidth，而不是继续压成省略号", column.getText())
                .isEqualTo(MIN_WIDTH));
        assertThat(totalWidth())
                .as("总和超出表格宽才需要横向滚动")
                .isGreaterThan(table.getWidth());
        assertThat(table.lookup(".scroll-bar:horizontal"))
                .as("列被卡在 minWidth 后必须能横向滚动，否则右边的列永远看不到")
                .isNotNull();
    }

    /** 把表格宽度钉到 {@code width}，并等一次布局。 */
    private void resizeTo(double width) {
        WaitForAsyncUtils.asyncFx(() -> {
            table.setMinWidth(0);
            table.setPrefWidth(width);
            table.setMaxWidth(width);
            table.applyCss();
            if (root != null) {
                root.applyCss();
                root.layout();
            }
            table.layout();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private double totalWidth() {
        return table.getColumns().stream().mapToDouble(TableColumn::getWidth).sum();
    }
}
