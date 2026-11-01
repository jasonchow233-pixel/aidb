package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N24：已保存查询是<b>对象清单页</b>（四列 + 设计 / 新建 / 删除查询），不是弹窗。
 *
 * <p>门禁钉三件事：列与数据来自真实存储、双击 / 设计打开的就是选中那条、
 * 删除确认的默认键必须是「取消」。
 */
@ExtendWith(ApplicationExtension.class)
class SavedQueriesPageTest {

    private Stage stage;
    private SavedQueries saved;
    private final AtomicReference<SavedQueries.Entry> designed = new AtomicReference<>();
    private final AtomicInteger created = new AtomicInteger();

    @Start
    void start(Stage stage) {
        this.stage = stage;
        saved = new SavedQueries(new SettingsStore(), "saved.page." + System.nanoTime());
        saved.save("users-top-100", "SELECT * FROM users LIMIT 100");
        saved.save("orders-recent", "SELECT * FROM orders ORDER BY id DESC");
        saved.saveSnippet("count-rows", "SELECT COUNT(*) FROM users");
    }

    @Test
    void listsFourColumnsFromTheRealStore() throws Exception {
        SavedQueriesPage page = openPage();

        TableView<SavedQueries.Entry> table = table(page);
        assertThat(table.getColumns())
                .extracting(column -> column.getId())
                .containsExactly("saved-column-name", "saved-column-type",
                        "saved-column-updated", "saved-column-sql");
        assertThat(table.getItems())
                .as("清单读的是已保存查询库（查询 + 片段都列出来，类型列区分）")
                .hasSize(3);
        assertThat(table.getItems())
                .extracting(SavedQueries.Entry::name)
                .contains("users-top-100", "orders-recent", "count-rows");
        assertThat(onFx(() -> page.lookup("#saved-summary")))
                .as("摘要说清一共有几条")
                .isNotNull();
    }

    @Test
    void designOpensTheSelectedEntryAndNewStartsAQuery() throws Exception {
        SavedQueriesPage page = openPage();

        assertThat(onFx(() -> button(page, "saved-design").isDisabled()))
                .as("没选中就没有可设计的对象")
                .isTrue();
        assertThat(onFx(() -> button(page, "saved-delete").isDisabled()))
                .as("没选中就没有可删除的对象")
                .isTrue();

        SavedQueries.Entry target = onFx(() -> table(page).getItems().stream()
                .filter(entry -> entry.name().equals("users-top-100"))
                .findFirst().orElseThrow());
        onFx(() -> table(page).getSelectionModel().select(target));
        assertThat(onFx(() -> button(page, "saved-design").isDisabled())).isFalse();

        onFx(() -> button(page, "saved-design").fire());
        assertThat(designed.get())
                .as("设计 = 打开选中那条查询（带原文，不是列表里的预览）")
                .isEqualTo(target);

        onFx(() -> button(page, "saved-new").fire());
        assertThat(created.get()).as("新建查询回调真的被调用").isEqualTo(1);
    }

    @Test
    void deleteDialogDefaultsToCancel() throws Exception {
        SavedQueriesPage page = openPage();
        SavedQueries.Entry target = onFx(() -> table(page).getItems().getFirst());

        Dialog<ButtonType> dialog = onFx(() -> page.buildDeleteDialog(target));
        assertThat(dialog.getHeaderText()).contains(target.name());
        ButtonType cancel = dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                .findFirst().orElseThrow();
        // onShown 在真实弹窗里由 JavaFX 触发；门禁直接跑一遍同一个回调。
        onFx(() -> dialog.getOnShown().handle(null));
        assertThat(((Button) dialog.getDialogPane().lookupButton(cancel)).isDefaultButton())
                .as("误按回车不能删掉一条查询")
                .isTrue();
    }

    @Test
    void refreshPicksUpNewlySavedQueries() throws Exception {
        SavedQueriesPage page = openPage();

        onFx(() -> {
            saved.save("brand-new", "SELECT 1");
            page.refresh();
        });

        assertThat(onFx(() -> table(page).getItems()))
                .extracting(SavedQueries.Entry::name)
                .contains("brand-new")
                .hasSize(4);
    }

    private SavedQueriesPage openPage() throws Exception {
        return onFx(() -> {
            SavedQueriesPage page = new SavedQueriesPage(saved, designed::set,
                    created::incrementAndGet, () -> { }, message -> { });
            Scene scene = new Scene(page, 1000, 500);
            stage.setScene(scene);
            stage.show();
            page.applyCss();
            page.layout();
            return page;
        });
    }

    @SuppressWarnings("unchecked")
    private static TableView<SavedQueries.Entry> table(SavedQueriesPage page) {
        return (TableView<SavedQueries.Entry>) page.lookup("#saved-queries-list");
    }

    private static Button button(SavedQueriesPage page, String id) {
        return (Button) page.lookup("#" + id);
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        javafx.application.Platform.runLater(() -> result.set(action.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return result.get();
    }

    private static void onFx(Runnable action) throws Exception {
        javafx.application.Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
