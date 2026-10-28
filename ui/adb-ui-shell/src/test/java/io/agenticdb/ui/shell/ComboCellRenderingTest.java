package io.agenticdb.ui.shell;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R33（2026-10-28）：下拉框显示格的可用高度门禁。
 *
 * <h2>为什么需要</h2>
 * 输入控件统一 28px 高、内边距 4px 8px（见 app.css「输入控件」段），显示格
 * 如果**再**吃一层 4px 8px，内容区只剩 12px —— 12px 字号的行框约 15px，
 * 字形被裁掉上下，只剩一条约 8px 的带子。行为门禁全绿（值在、选中对、
 * 事件都通），只有实拍帧能看出来：p5-connect-ssl 的「校验 CA」、
 * p8-export 的「整表」都被压成一条。
 *
 * <p>判据：显示格内容高度（格高减上下内边距）必须 ≥ 同字体下一串
 * 「中文字 + 上下行笔」的墨迹高度。裁剪是 Labeled 按内容区裁的，
 * 内容区装不下行框就一定会裁 —— 这条不依赖像素阈值的松紧。
 */
@ExtendWith(ApplicationExtension.class)
class ComboCellRenderingTest {

    /** 同时含 CJK 上半区与拉丁下行笔（g）的样本，量到的墨迹高度就是整行框。 */
    private static final String SAMPLE = "校验 CAg";

    @Test
    void comboDisplayCellFitsAFullLineBox() throws Exception {
        String[] measurement = WaitForAsyncUtils.asyncFx((Callable<String[]>) () -> {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().addAll("关闭", "必须加密", "校验 CA");
            combo.getSelectionModel().select("校验 CA");
            combo.setPrefWidth(240);
            VBox box = new VBox(8, combo);
            box.setPadding(new Insets(10));
            box.setStyle("-fx-background-color: white;");
            new Scene(box, 320, 160).getStylesheets().add(
                    "io/agenticdb/ui/app.css");
            box.getStyleClass().add("theme-light");
            box.applyCss();
            box.layout();

            ListCell<?> cell = combo.getChildrenUnmodifiable().stream()
                    .filter(ListCell.class::isInstance)
                    .map(ListCell.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("下拉框没有显示格"));
            Text sample = new Text(SAMPLE);
            sample.setFont(cell.getFont());
            double contentHeight = cell.getHeight()
                    - cell.getPadding().getTop() - cell.getPadding().getBottom();
            return new String[] {combo.getHeight() + "",
                    contentHeight + "", sample.getLayoutBounds().getHeight() + ""};
        }).get();

        double comboHeight = Double.parseDouble(measurement[0]);
        double contentHeight = Double.parseDouble(measurement[1]);
        double lineBox = Double.parseDouble(measurement[2]);
        assertThat(comboHeight).as("下拉框应先长到输入控件统一的 28px 档").isEqualTo(28);
        assertThat(contentHeight)
                .as("显示格内容区 %.1fpx 装不下 %.1fpx 的行框 —— 字形会被裁掉上下",
                        contentHeight, lineBox)
                .isGreaterThanOrEqualTo(lineBox);
    }
}
