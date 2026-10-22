package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空态门禁（V-10 / E5·E7·E8·E9）。
 *
 * <p>空态属于「**呈现**」：控件确实存在、事件确实绑好了，只是没人给它上样式。
 * 所以只能用**呈现门禁**（{@code lookup(".adb-empty") != null}）来测 ——
 * 行为门禁测不到它。
 *
 * <p>规则（见 {@code docs/UI-FORMS-AND-STATES.md} §7）：
 * 图标 + 标题 + 说明 + **一个明确的下一步按钮**。
 */
@ExtendWith(ApplicationExtension.class)
class EmptyStateTest {

    @Test
    void emptyStateHasIconTitleDescriptionAndAction() {
        EmptyState state = EmptyState.of(Icons.Icon.TABLE, "还没有数据",
                "新建一行或导入数据。", "新建一行", () -> { });

        assertThat(state.lookupAll(".adb-empty")).as("空态根节点").isNotEmpty();
        assertThat(state.lookupAll(".adb-empty__icon")).as("必须有图标").isNotEmpty();
        assertThat(state.lookupAll(".adb-empty__title")).as("必须有标题").isNotEmpty();
        assertThat(text(state, ".adb-empty__title")).isEqualTo("还没有数据");
        assertThat(state.lookupAll(".adb-button--primary"))
                .as("空态必须给一个明确的下一步按钮（primary）").isNotEmpty();
    }

    @Test
    void emptyStateWithoutActionStillExplainsWhy() {
        EmptyState state = EmptyState.of(Icons.Icon.SEARCH, "搜不到", "换个词试试。");

        assertThat(text(state, ".adb-empty__title")).isEqualTo("搜不到");
        assertThat(state.lookupAll(".adb-button--primary"))
                .as("三参版不该有按钮").isEmpty();
        assertThat(state.lookupAll(".adb-muted"))
                .as("说明文字要说明原因").isNotEmpty();
    }

    @Test
    void queryHistoryPageShowsAnEmptyStateWhenThereIsNoHistory() throws Exception {
        AtomicReference<HistoryPage> holder = new AtomicReference<>();
        WaitForAsyncUtils.asyncFx(() -> {
            QueryHistory history = new QueryHistory(new SettingsStore());
            history.clear();
            holder.set(new HistoryPage(history, sql -> { }));
        }).get();
        HistoryPage page = attach(holder.get());

        assertThat(page.lookupAll(".adb-empty"))
                .as("E8：查询历史为空时要有空态，而不是空白列表")
                .isNotEmpty();
    }

    @Test
    void favoritesPageShowsAnEmptyStateWhenNothingIsStarred() throws Exception {
        AtomicReference<FavoritesPage> holder = new AtomicReference<>();
        WaitForAsyncUtils.asyncFx(() ->
                holder.set(new FavoritesPage(List.of(), name -> { }, message -> { }))).get();
        // 收藏夹的列表在**弹窗内容区**里（不是页面本身）——
        // 所以要把弹窗显示出来，再在 DialogPane 里查。
        FavoritesPage page = holder.get();
        WaitForAsyncUtils.asyncFx(() -> page.dialog().show()).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.dialog().getDialogPane().lookupAll(".adb-empty"))
                .as("E9：收藏为空时要有空态 + 引导（怎么收藏）")
                .isNotEmpty();
    }

    /** 把节点挂到场景里 —— 不入场景图，{@code lookup} 找不到 placeholder。 */
    private static <T extends javafx.scene.Parent> T attach(T node) throws Exception {
        // ⛔ Stage 必须在 FX 线程上创建（否则报 Not on FX application thread）。
        WaitForAsyncUtils.asyncFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(node, 900, 600));
            stage.show();
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
        return node;
    }

    private static String text(EmptyState state, String styleClass) {
        for (Node node : state.lookupAll("." + styleClass)) {
            if (node instanceof Label label) {
                return label.getText();
            }
        }
        return "";
    }
}
