package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
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
 * <p>门禁钉四件事：列与数据来自真实存储、双击 / 设计打开的就是选中那条、
 * 删除确认的默认键必须是「取消」、页脚按种类计数（与左树 Queries 分组同一口径）。
 *
 * <p>⭐ D-85 第三族：存储节点必须隔离 —— 不挂 {@link IsolatedPreferencesExtension}
 * 时它跑在进程级 {@code adb.prefs.node} 上，满负载下暴露给别的类的节点生命周期
 * （「条目比期望少」那族）。
 */
@ExtendWith({ApplicationExtension.class, IsolatedPreferencesExtension.class})
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

    /**
     * ⭐ D-82：页脚按<b>种类</b>计数，和左树 Queries 分组同一口径。
     *
     * <p>这一条钉的是帧对账查出的错：左树只列完整查询，页脚数的是
     * {@code saved.all()}（查询 + 片段混在一起），于是「3 saved queries」对着树上 2 条 ——
     * 用户以为树漏了一条。Navicat 官方帧（{@code navicat17-saved-queries-2026-10-28.png}）
     * 状态栏写的就是「4 Queries」，按种类数。
     *
     * <p>期望写的是<b>英文字面量</b>，不是 {@code I18n.t("saved.summary")} ——
     * 拿同一把键去断言同一行文字，两边一起改坏也不会红（见 {@code TerminologyGuardTest} 的反循环说明）。
     */
    @Test
    void countsQueriesAndSnippetsSeparately() throws Exception {
        SavedQueriesPage page = openPage();

        assertThat(summaryText(page))
                .as("2 条查询 + 1 个片段：页脚说 2 条查询，片段单列在后面")
                .isEqualTo("2 saved queries · 1 snippet");
    }

    /** 没有片段时不能拖一条空尾巴（也是「按种类」的另一半：0 不等于 1）。 */
    @Test
    void omitsTheSnippetCountWhenThereAreNoSnippets() throws Exception {
        SavedQueries queriesOnly = new SavedQueries(
                new SettingsStore(), "saved.page." + System.nanoTime());
        queriesOnly.save("solo", "SELECT 1");
        SavedQueriesPage page = openPage(queriesOnly);

        assertThat(summaryText(page))
                .as("只有一个片段库为空时，摘要就是单句 + 单数形")
                .isEqualTo("1 saved query");
    }

    private SavedQueriesPage openPage() throws Exception {
        return openPage(saved);
    }

    private String summaryText(SavedQueriesPage page) throws Exception {
        return onFx(() -> ((javafx.scene.control.Label) page.lookup("#saved-summary")).getText());
    }

    private SavedQueriesPage openPage(SavedQueries store) throws Exception {
        return onFx(() -> {
            SavedQueriesPage page = new SavedQueriesPage(store, designed::set,
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
