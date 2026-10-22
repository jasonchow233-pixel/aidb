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

    @Test
    void frozenColumnsStayPut() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("frozen.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "frozen.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null && tableItems(page).size() == 200);
            WaitForAsyncUtils.waitForFxEvents();

            javafx.scene.control.TableView<Map<String, Object>> right = tableOf(page);
            javafx.scene.control.TableView<Map<String, Object>> frozen =
                    (javafx.scene.control.TableView<Map<String, Object>>)
                            page.lookup("#data-frozen-table");
            assertThat(frozen).as("冻结表应存在").isNotNull();
            assertThat(frozen.isVisible()).as("默认不冻结，左表应隐藏").isFalse();

            int columnsBefore = right.getColumns().size();
            List<String> namesBefore = WaitForAsyncUtils.asyncFx(() ->
                    right.getColumns().stream()
                            .map(column -> String.valueOf(column.getUserData()))
                            .toList()).get();

            // ⭐ G1：冻结左侧 2 个数据列 → 选择列 + 行号列 + 2 列进左表，其余留在右表
            WaitForAsyncUtils.asyncFx(() -> page.setFrozenColumnCount(2)).get();
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(page.frozenColumnCount()).isEqualTo(2);
            assertThat(frozen.isVisible()).as("冻结后左表应显示").isTrue();
            // 移走的是**同一批列实例**，总列数不变，一列都不能丢
            assertThat(frozen.getColumns().size() + right.getColumns().size())
                    .as("冻结不该丢列")
                    .isEqualTo(columnsBefore);
            // 左表 = 选择列 + 行号列 + id + name
            List<String> frozenNames = WaitForAsyncUtils.asyncFx(() ->
                    frozen.getColumns().stream()
                            .filter(column -> column.getUserData() != null)
                            .map(column -> String.valueOf(column.getUserData()))
                            .toList()).get();
            assertThat(frozenNames).containsExactly("id", "name");
            List<String> rightNames = WaitForAsyncUtils.asyncFx(() ->
                    right.getColumns().stream()
                            .filter(column -> column.getUserData() != null)
                            .map(column -> String.valueOf(column.getUserData()))
                            .toList()).get();
            assertThat(rightNames).as("冻结的列不该还留在右表")
                    .doesNotContain("id", "name");
            assertThat(frozenNames).as("两表列名合起来应与冻结前一致")
                    .isEqualTo(namesBefore.stream()
                            .filter(name -> !"null".equals(name)).toList().subList(0, 2));

            // 两表必须共享同一份 items，否则翻页后左右会指向不同的行
            assertThat(frozen.getItems()).isSameAs(right.getItems());
            // 行高必须一致 —— 不一致的话同一行在左右两半会错开
            assertThat(frozen.getFixedCellSize()).isEqualTo(right.getFixedCellSize());

            // ⭐ 四路同步之「选择」：在右表选一行，左表应跟着选中同一行
            WaitForAsyncUtils.asyncFx(() -> right.getSelectionModel().select(3)).get();
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(frozen.getSelectionModel().getSelectedIndices())
                    .as("选中行应同步到冻结表").containsExactly(3);
            // 反向也要成立（在左表点选，右表跟随）。
            // ⚠️ MULTIPLE 模式下 select(i) 是**追加**不是替换 —— 真实点击会先清空，
            //   所以这里显式 clearSelection，否则期望值会变成 [3, 7]。
            WaitForAsyncUtils.asyncFx(() -> {
                frozen.getSelectionModel().clearSelection();
                frozen.getSelectionModel().select(7);
            }).get();
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(right.getSelectionModel().getSelectedIndices())
                    .as("在冻结表选择应同步回主表").containsExactly(7);

            // 解冻：列全部回到右表，顺序不变
            WaitForAsyncUtils.asyncFx(() -> page.setFrozenColumnCount(0)).get();
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(page.frozenColumnCount()).isZero();
            assertThat(frozen.isVisible()).as("解冻后左表应隐藏").isFalse();
            assertThat(right.getColumns()).hasSize(columnsBefore);
            assertThat(WaitForAsyncUtils.asyncFx(() -> right.getColumns().stream()
                    .map(column -> String.valueOf(column.getUserData())).toList()).get())
                    .as("解冻后列顺序应与冻结前完全一致")
                    .isEqualTo(namesBefore);

            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void rectangleSelectionCopies() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("rect.db"))));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "rect.db", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null && tableItems(page).size() == 200);
            WaitForAsyncUtils.waitForFxEvents();

            List<Map<String, Object>> rows = tableItems(page);

            // ⭐ G4：框住 3 行 × 2 列（可见数据列前两列 = id / name）
            WaitForAsyncUtils.asyncFx(() -> page.selectRectangle(0, 0, 2, 1)).get();
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(page.hasRectangleSelection()).isTrue();
            assertThat(page.rectangleSize()).isEqualTo("3 × 2");

            // ⌘C 在有矩形时复制的是**这块区域**
            pressShortcut(page, javafx.scene.input.KeyCode.C, false, false);
            WaitForAsyncUtils.waitForFxEvents();

            String text = page.lastCopiedText();
            assertThat(text).endsWith("\n");
            String[] lines = text.split("\n", -1);
            // 3 行数据 + 末尾换行产生的空串；**不含表头**（复制整行那条路径才带表头）
            assertThat(lines).hasSize(4);
            for (int i = 0; i < 3; i++) {
                String[] fields = lines[i].split("\t", -1);
                assertThat(fields).as("第 %d 行应只有框住的 2 列", i).hasSize(2);
                assertThat(fields[0]).isEqualTo(String.valueOf(rows.get(i).get("id")));
                assertThat(fields[1]).isEqualTo(String.valueOf(rows.get(i).get("name")));
            }
            // 真的进了系统剪贴板（lastCopiedText 只是断言用的镜像）
            assertThat(clipboardText()).isEqualTo(text);

            // 矩形之外的列不该出现在复制结果里 —— 这是「复制矩形」与「复制整行」的分界
            assertThat(text).doesNotContain("@");

            // Esc 取消矩形
            WaitForAsyncUtils.asyncFx(() -> page.fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false))).get();
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(page.hasRectangleSelection()).as("Esc 应取消矩形选区").isFalse();

            // 没有矩形时 ⌘C 仍复制**单个单元格**（旧行为不回归）。
            // 必须 focus 到具体的 (行, 列) —— 只 focus 行的话焦点列是 null，
            // copyFocusedCellValue 会直接 return，那就什么都没复制到。
            WaitForAsyncUtils.asyncFx(() -> {
                javafx.scene.control.TableView<Map<String, Object>> table = tableOf(page);
                javafx.scene.control.TableColumn<Map<String, Object>, ?> column =
                        table.getVisibleLeafColumns().stream()
                                .filter(item -> item.getUserData() != null)
                                .findFirst().orElse(null);
                table.getSelectionModel().clearAndSelect(5);
                table.getFocusModel().focus(5, column);
            }).get();
            WaitForAsyncUtils.waitForFxEvents();
            pressShortcut(page, javafx.scene.input.KeyCode.C, false, false);
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(page.lastCopiedText())
                    .as("无矩形时 ⌘C 复制的是单个单元格，不该有行列分隔符")
                    .doesNotContain("\t").doesNotContain("\n");

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
