package io.agenticdb.ui.shell;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class CellDetailsDrawerTest {

    @Test
    void showsTextAndClosesWithoutLeakingContent() throws Exception {
        CellDetailsDrawer drawer = new CellDetailsDrawer();
        ColumnDef column = new ColumnDef("bio", ColumnType.TEXT, true, false,
                null, null, null, null, null, null, false);

        WaitForAsyncUtils.asyncFx(() ->
                drawer.showDetails(column, "hello\nworld", Map.of("id", 1), "users")).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(drawer.isOpen()).isTrue();
        assertThat(((TextArea) drawer.lookup("#data-details-text")).getText())
                .isEqualTo("hello\nworld");

        WaitForAsyncUtils.asyncFx(drawer::close).get();
        assertThat(drawer.isOpen()).isFalse();
    }

    @Test
    void hexAndMetadataTabsAreAvailableForBinaryValues() throws Exception {
        CellDetailsDrawer drawer = new CellDetailsDrawer();
        ColumnDef column = new ColumnDef("avatar", ColumnType.BINARY, true, false,
                null, "头像", null, null, null, null, false);

        WaitForAsyncUtils.asyncFx(() ->
                drawer.showDetails(column, new byte[]{1, 2, 65}, Map.of(), "users")).get();
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.control.TabPane tabs =
                (javafx.scene.control.TabPane) drawer.lookup("#data-details-tabs");
        WaitForAsyncUtils.asyncFx(() -> tabs.getSelectionModel().select(2)).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(((TextArea) drawer.lookup("#data-details-hex")).getText())
                .contains("01 02 41");
        WaitForAsyncUtils.asyncFx(() -> tabs.getSelectionModel().select(4)).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(tabs.getTabs().get(4).getContent()).isNotNull();
        WaitForAsyncUtils.asyncFx(drawer::close).get();
    }

    /** §2.5：编辑态里「取消」和右上「×」是同一句话 —— 同时出现就是重复。 */
    @Test
    void editingModeKeepsOneDismissAffordance() throws Exception {
        CellDetailsDrawer drawer = new CellDetailsDrawer();
        drawer.setEditGate(column -> true);
        ColumnDef column = new ColumnDef("bio", ColumnType.TEXT, true, false,
                null, null, null, null, null, null, false);

        WaitForAsyncUtils.asyncFx(() ->
                drawer.showDetails(column, "hello", Map.of("id", 1), "users")).get();
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.control.Button close =
                (javafx.scene.control.Button) drawer.lookup("#data-details-close");
        assertThat(close.isVisible())
                .as("查看态：右上角 × 是唯一的退出入口")
                .isTrue();

        javafx.scene.control.Button edit =
                (javafx.scene.control.Button) drawer.lookup("#data-details-edit");
        WaitForAsyncUtils.asyncFx(() -> edit.fire()).get();
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.control.Button cancel =
                (javafx.scene.control.Button) drawer.lookup("#data-details-cancel");
        assertThat(cancel.isVisible()).as("编辑态：取消出现").isTrue();
        assertThat(close.isVisible())
                .as("编辑态：× 让位给「取消」—— 一个意思只留一个按钮")
                .isFalse();

        WaitForAsyncUtils.asyncFx(() -> cancel.fire()).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(close.isVisible())
                .as("退出编辑后 × 要回来（隐藏不能变成永久消失）")
                .isTrue();
    }

    /** §2.5：宽度可拖 + 记忆 —— 偏好里有值就吃值，左缘要真的挂了拖拽手势。 */
    @Test
    void widthIsRememberedAndDraggable() throws Exception {
        SettingsStore store = new SettingsStore();
        int previous = store.getInt("details.width", -1);
        try {
            store.setInt("details.width", 560);
            CellDetailsDrawer drawer = WaitForAsyncUtils.asyncFx(CellDetailsDrawer::new).get();
            assertThat(drawer.getPrefWidth())
                    .as("构造时必须从偏好恢复上次拖出来的宽度")
                    .isEqualTo(560);
            assertThat(drawer.getOnMouseDragged())
                    .as("左缘拖宽的手势必须挂上")
                    .isNotNull();
            assertThat(drawer.getMinWidth()).isEqualTo(360);
            assertThat(drawer.getMaxWidth()).isEqualTo(620);
        } finally {
            if (previous < 0) {
                store.remove("details.width");
            } else {
                store.setInt("details.width", previous);
            }
        }
    }
}
