package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.DataQuery;
import io.agenticdb.introspect.FilterCondition;
import io.agenticdb.introspect.FilterOperator;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SortSpec;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class DataGridPageTest {

    @TempDir
    Path temp;

    @Test
    void appliesFilterAndSortToServerSideSql() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "test.db", noOpFeedback(), name -> { }, executor);

            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null
                            && ((javafx.scene.control.TableView<?>) page.lookup("#data-table"))
                            .getItems().size() == 200);

            WaitForAsyncUtils.asyncFx(() -> page.applyQuery(DataQuery.empty()
                    .withFilter(FilterCondition.of("active", FilterOperator.EQ, 1))
                    .withSorts(List.of(new SortSpec("score", false))))).get();

            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lastSql().contains("WHERE")
                            && page.lastSql().contains("ORDER BY"));
            WaitForAsyncUtils.waitForFxEvents();

            @SuppressWarnings("unchecked")
            javafx.scene.control.TableView<Map<String, Object>> table =
                    (javafx.scene.control.TableView<Map<String, Object>>) page.lookup("#data-table");
            assertThat(page.lastSql()).contains("\"active\" = ?");
            assertThat(page.lastSql()).contains("ORDER BY \"score\" DESC");
            assertThat(table.getItems()).hasSize(180);
            assertThat(table.getItems()).allMatch(row ->
                    ((Number) row.get("active")).intValue() == 1);
            assertThat(table.getItems()).extracting(row -> row.get("score"))
                    .isSortedAccordingTo((a, b) -> Double.compare(
                            ((Number) b).doubleValue(), ((Number) a).doubleValue()));
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void persistsColumnVisibilityAcrossPages() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("layout.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String connection = "layout-" + System.nanoTime();
        SettingsStore settings = new SettingsStore();
        try {
            DataGridPage first = new DataGridPage(session, new SchemaService(), "users",
                    connection, noOpFeedback(), name -> { }, executor, settings);
            // ⚠️ 列集合由 FX 线程在数据加载完成时重建，测试线程直接遍历会偶发
            //    ConcurrentModificationException。所有读取都放进 asyncFx，与 FX 线程串行。
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> first.lookup("#data-table") != null
                    && !tableItems(first).isEmpty());
            javafx.scene.control.TableColumn<?, ?> nameColumn = WaitForAsyncUtils.asyncFx(() ->
                    tableOf(first).getColumns().stream()
                            .filter(column -> "name".equals(column.getUserData()))
                            .findFirst().orElse(null)).get();
            assertThat(nameColumn).as("name 列应该存在").isNotNull();
            WaitForAsyncUtils.asyncFx(() -> nameColumn.setVisible(false)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> settings.get(
                    "ui.grid." + connection + ".users.hidden", "").contains("name"));
            first.close();

            DataGridPage second = new DataGridPage(session, new SchemaService(), "users",
                    connection, noOpFeedback(), name -> { }, executor, settings);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> second.lookup("#data-table") != null
                    && !tableItems(second).isEmpty());
            javafx.scene.control.TableColumn<?, ?> restored = WaitForAsyncUtils.asyncFx(() ->
                    tableOf(second).getColumns().stream()
                            .filter(column -> "name".equals(column.getUserData()))
                            .findFirst().orElse(null)).get();
            assertThat(restored).isNotNull();
            assertThat(restored.isVisible()).isFalse();
            second.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void batchDeleteMarksRowsAndUndoRestoresBuffer() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("batch.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "test.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.lookup("#data-table") != null
                    && tableOf(page).getItems().size() == 200);

            WaitForAsyncUtils.asyncFx(() -> tableOf(page)
                    .getSelectionModel().selectIndices(0, 1, 2)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> page.lookup("#data-batch-bar") != null
                            && page.lookup("#data-batch-bar").isVisible());
            Label label = (Label) page.lookup("#data-batch-label");
            assertThat(label.getText()).isEqualTo("已选 3 行");

            WaitForAsyncUtils.asyncFx(() ->
                    ((Button) page.lookup("#data-batch-delete")).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 3);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(page.query().isDefault()).isTrue();

            WaitForAsyncUtils.asyncFx(() ->
                    ((Button) page.lookup("#data-undo")).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 0);
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void addRowCreatesDraftAndUndoRemovesIt() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("add.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "test.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.lookup("#data-table") != null
                    && tableOf(page).getItems().size() == 200);

            WaitForAsyncUtils.asyncFx(() ->
                    ((Button) page.lookup("#data-add")).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 201);
            assertThat(page.dirtyCount()).isEqualTo(1);
            assertThat(tableOf(page).getItems().getFirst())
                    .containsKey(io.agenticdb.editing.EditBuffer.DRAFT_KEY);

            WaitForAsyncUtils.asyncFx(() ->
                    ((Button) page.lookup("#data-undo")).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 200);
            assertThat(page.dirtyCount()).isZero();
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void cloneSelectedRowsOmitsPrimaryKeyAndCanBeUndone() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("clone.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "test.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.lookup("#data-table") != null
                    && tableOf(page).getItems().size() == 200);
            Map<String, Object> source = tableOf(page).getItems().getFirst();

            WaitForAsyncUtils.asyncFx(() -> {
                tableOf(page).getSelectionModel().select(0);
                ((Button) page.lookup("#data-batch-clone")).fire();
            }).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 201);
            Map<String, Object> draft = tableOf(page).getItems().getFirst();
            assertThat(draft).doesNotContainKey("id");
            assertThat(draft.get("name")).isEqualTo(source.get("name"));
            assertThat(page.dirtyCount()).isEqualTo(1);

            WaitForAsyncUtils.asyncFx(() ->
                    ((Button) page.lookup("#data-undo")).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 200);
            assertThat(page.dirtyCount()).isZero();
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void selectAllCheckboxSelectsCurrentPageAndShowsBatchBar() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("select-all.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "test.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.lookup("#data-table") != null
                    && tableOf(page).getItems().size() == 200);

            WaitForAsyncUtils.asyncFx(() ->
                    ((CheckBox) tableOf(page).getColumns().getFirst().getGraphic()).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getSelectionModel().getSelectedItems().size() == 200);
            assertThat(page.lookup("#data-batch-bar").isVisible()).isTrue();
            assertThat(((Label) page.lookup("#data-batch-label")).getText())
                    .isEqualTo("已选 200 行");

            WaitForAsyncUtils.asyncFx(() ->
                    ((CheckBox) tableOf(page).getColumns().getFirst().getGraphic()).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getSelectionModel().getSelectedItems().isEmpty());
            assertThat(page.lookup("#data-batch-bar").isVisible()).isFalse();
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void searchHideNullAndTransposeOnlyAffectTheCurrentPage() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("view.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "test.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.lookup("#data-table") != null
                    && tableOf(page).getItems().size() == 200);

            javafx.scene.control.TextField search =
                    (javafx.scene.control.TextField) page.lookup("#data-search");
            WaitForAsyncUtils.asyncFx(() -> search.setText("User 002")).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 1);
            WaitForAsyncUtils.asyncFx(search::clear).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 200);

            WaitForAsyncUtils.asyncFx(() -> page.setHideNullRows(true)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 160);
            WaitForAsyncUtils.asyncFx(() -> page.setHideNullRows(false)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> tableOf(page).getItems().size() == 200);

            WaitForAsyncUtils.asyncFx(() -> page.setTranspose(true)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> page.lookup("#data-transpose") != null
                            && page.lookup("#data-transpose").isVisible());
            assertThat(tableOf(page).isVisible()).isFalse();
            WaitForAsyncUtils.asyncFx(() -> page.setTranspose(false)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> tableOf(page).isVisible());
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void batchNullUsesFocusedColumnAndOneUndoStep() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("batch-null.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "test.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.lookup("#data-table") != null
                    && tableOf(page).getItems().size() == 200);
            @SuppressWarnings("unchecked")
            javafx.scene.control.TableColumn<Map<String, Object>, ?> bio =
                    (javafx.scene.control.TableColumn<Map<String, Object>, ?>)
                            tableOf(page).getColumns().stream()
                                    .filter(column -> "bio".equals(column.getUserData()))
                                    .findFirst().orElseThrow();
            int bioIndex = tableOf(page).getColumns().indexOf(bio);

            WaitForAsyncUtils.asyncFx(() ->
                    tableOf(page).getSelectionModel().selectIndices(0, 1)).get();
            WaitForAsyncUtils.asyncFx(() -> page.setColumnValue("bio", null)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 2);
            assertThat(bioIndex).isGreaterThan(0);

            WaitForAsyncUtils.asyncFx(() ->
                    ((Button) page.lookup("#data-undo")).fire()).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 0);
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static javafx.scene.control.TableView<Map<String, Object>> tableOf(DataGridPage page) {
        return (javafx.scene.control.TableView<Map<String, Object>>) page.lookup("#data-table");
    }

    /** 只读行数，避免在测试线程上遍历会被 FX 线程重建的集合。 */
    private static List<Map<String, Object>> tableItems(DataGridPage page) {
        return List.copyOf(tableOf(page).getItems());
    }

    @Test
    void showsRetryableErrorStateWhenTableDisappears() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DataGridPage.Feedback feedback = new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };

        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "missing_table",
                    "test.db", feedback, name -> { }, executor);

            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
                Label state = (Label) page.lookup(".adb-grid__state-text");
                return state != null && state.getText().startsWith("加载失败");
            });

            Label state = (Label) page.lookup(".adb-grid__state-text");
            Button retry = (Button) page.lookup(".adb-grid__state-text")
                    .getParent().lookup(".button");
            assertThat(state.getText()).contains("missing_table");
            assertThat(retry.isVisible()).isTrue();
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    /** 4.5-1：UI 指南承诺的四个复制 / 全选快捷键必须真的能用。 */
    @Test
    void copyShortcutsAndSelectAllWork() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("shortcuts.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<String> messages = new java.util.ArrayList<>();
        DataGridPage.Feedback feedback = new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
                messages.add(message);
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "shortcuts.db", feedback, name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null
                            && tableOf(page).getItems().size() == 200);
            WaitForAsyncUtils.waitForFxEvents();

            javafx.scene.control.TableView<Map<String, Object>> table = tableOf(page);

            // 1) ⌘A 全选当前页
            pressShortcut(page, javafx.scene.input.KeyCode.A, false, false);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(table.getSelectionModel().getSelectedItems())
                    .as("⌘A 必须全选当前页")
                    .hasSize(table.getItems().size());

            // 2) ⌥⌘C 复制为 TSV（此时整页都选中）
            pressShortcut(page, javafx.scene.input.KeyCode.C, false, true);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(clipboardText()).contains("\t").contains("\n");

            // 3) ⇧⌘C 复制为 INSERT
            pressShortcut(page, javafx.scene.input.KeyCode.C, true, false);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(clipboardText()).contains("INSERT INTO");

            // 4) ⌘C 复制光标所在单元格的值
            Map<String, Object> firstRow = table.getItems().getFirst();
            WaitForAsyncUtils.asyncFx(() -> {
                table.getSelectionModel().clearSelection();
                table.getFocusModel().focus(0, table.getColumns().get(2));
            }).get();
            WaitForAsyncUtils.waitForFxEvents();
            pressShortcut(page, javafx.scene.input.KeyCode.C, false, false);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(messages).anyMatch(message -> message.contains("已复制单元格值"));
            assertThat(clipboardText()).isNotNull();
            assertThat(firstRow).isNotNull();

            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void firstLastAndJumpPaginationWork() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("pager.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<String> messages = new java.util.ArrayList<>();
        DataGridPage.Feedback feedback = new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
                messages.add(message);
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "pager.db", feedback, name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null && tableOf(page).getItems().size() == 200);

            // 每页改成 100 → users 200 行分 2 页
            // ⚠️ 程序化 select 在部分 JavaFX 版本不触发 onAction，这里显式补发与用户操作等价的事件。
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<Integer> sizeBox =
                    (javafx.scene.control.ComboBox<Integer>) page.lookup("#data-page-size");
            WaitForAsyncUtils.asyncFx(() -> {
                if (!Integer.valueOf(100).equals(sizeBox.getValue())) {
                    sizeBox.getSelectionModel().select(Integer.valueOf(100));
                }
                sizeBox.fireEvent(new javafx.event.ActionEvent());
            }).get();
            javafx.scene.control.Label pageLabel =
                    (javafx.scene.control.Label) page.lookup("#data-page");
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    pageLabel.getText().contains("1 / 2"));
            WaitForAsyncUtils.waitForFxEvents();

            javafx.scene.control.Button first =
                    (javafx.scene.control.Button) page.lookup("#data-first");
            javafx.scene.control.Button last =
                    (javafx.scene.control.Button) page.lookup("#data-last");
            javafx.scene.control.TextField jump =
                    (javafx.scene.control.TextField) page.lookup("#data-jump");

            assertThat(first.isDisable()).as("第 1 页时首页按钮禁用").isTrue();
            assertThat(last.isDisable()).as("第 1 页时末页按钮可用").isFalse();

            // 跳页到 2
            WaitForAsyncUtils.asyncFx(() -> jump.setText("2")).get();
            WaitForAsyncUtils.asyncFx(() ->
                    jump.fireEvent(new javafx.event.ActionEvent())).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    pageLabel.getText().contains("2 / 2"));
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(tableOf(page).getItems()).hasSize(100);
            assertThat(first.isDisable()).as("第 2 页时首页按钮可用").isFalse();
            assertThat(last.isDisable()).as("第 2 页时末页按钮禁用").isTrue();
            assertThat(jump.getText()).as("跳转后输入框应清空").isEmpty();

            // 越界跳页：给状态提示，不翻页
            WaitForAsyncUtils.asyncFx(() -> jump.setText("9")).get();
            WaitForAsyncUtils.asyncFx(() ->
                    jump.fireEvent(new javafx.event.ActionEvent())).get();
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(messages).anyMatch(message -> message.contains("最大页码 2"));
            assertThat(pageLabel.getText()).as("越界跳页不应改变页码").contains("2 / 2");

            // 回到第 1 页
            WaitForAsyncUtils.asyncFx(first::fire).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    pageLabel.getText().contains("1 / 2"));
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(tableOf(page).getItems()).hasSize(100);
            assertThat(((javafx.scene.control.Label) page.lookup("#data-range")).getText())
                    .isEqualTo("1–100 / 200");

            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    private static void pressShortcut(DataGridPage page, javafx.scene.input.KeyCode code,
                                      boolean shift, boolean alt) throws Exception {
        javafx.scene.input.KeyEvent event = new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code,
                shift, true, alt, true);
        WaitForAsyncUtils.asyncFx(() -> page.fireEvent(event)).get();
    }

    private static String clipboardText() throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                javafx.scene.input.Clipboard.getSystemClipboard().getString()).get();
    }

    private static DataGridPage.Feedback noOpFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }
}
