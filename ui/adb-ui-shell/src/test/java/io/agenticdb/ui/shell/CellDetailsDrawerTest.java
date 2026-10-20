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
}
