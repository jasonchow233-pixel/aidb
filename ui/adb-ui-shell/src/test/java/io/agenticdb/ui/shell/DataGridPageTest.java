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
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> first.lookup("#data-table") != null
                    && tableOf(first).getColumns().stream()
                    .anyMatch(column -> "name".equals(column.getUserData())));
            javafx.scene.control.TableColumn<?, ?> nameColumn = tableOf(first).getColumns()
                    .stream().filter(column -> "name".equals(column.getUserData()))
                    .findFirst().orElseThrow();
            WaitForAsyncUtils.asyncFx(() -> nameColumn.setVisible(false)).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> settings.get(
                    "ui.grid." + connection + ".users.hidden", "").contains("name"));
            first.close();

            DataGridPage second = new DataGridPage(session, new SchemaService(), "users",
                    connection, noOpFeedback(), name -> { }, executor, settings);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> second.lookup("#data-table") != null
                    && tableOf(second).getColumns().stream()
                    .anyMatch(column -> "name".equals(column.getUserData())));
            javafx.scene.control.TableColumn<?, ?> restored = tableOf(second).getColumns()
                    .stream().filter(column -> "name".equals(column.getUserData()))
                    .findFirst().orElseThrow();
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
