package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R13 实际查询构造器的 UI 接线门禁。
 *
 * <p>纯逻辑（结构化条件 → SELECT 文本）由 adb-introspect 的 SelectBuilderTest /
 * QueryCriteriaTest 钉住；这里只钉「UI 真的接上了那条逻辑」：选表后预览出生成的
 * SELECT、插入按钮只回填编辑器不执行、DialogPane 仍只有 CLOSE。
 */
@ExtendWith(ApplicationExtension.class)
class QueryBuilderUiTest {

    private JdbcSession session;
    private ExecutorService executor;
    private QueryPage page;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
        session.execute("INSERT INTO users VALUES (1, 'Ada', 30)");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "qb-test");
            t.setDaemon(true);
            return t;
        });
        page = new QueryPage(session, "test", "", executor,
                new io.agenticdb.introspect.SchemaService(), null, new DataGridPage.Feedback() {
            @Override public void status(String message) { }
            @Override public void range(String text) { }
            @Override public void duration(long millis) { }
        });
        stage.setScene(new Scene(page, 900, 700));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            page.close();
        }
        executor.shutdownNow();
        session.close();
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Node content(Dialog<?> dialog) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> dialog.getDialogPane().getContent()).get();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<String> tableChoice(Node content) throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                (ComboBox<String>) content.lookup("#query-builder-table")).get();
    }

    private TextArea preview(Node content) throws Exception {
        return WaitForAsyncUtils.asyncFx(() ->
                (TextArea) content.lookup("#query-builder-preview")).get();
    }

    @Test
    void selectingTableGeneratesSelectAndInsertOnlyFillsEditor() throws Exception {
        interact(() -> page.setSql("SELECT 1"));
        int tabsBefore = page.resultTabCount();

        Dialog<?> dialog = WaitForAsyncUtils.asyncFx(page::builderDialog).get();
        Node content = content(dialog);
        assertThat(content).isNotNull();

        // 预览区保留旧契约：只读、初始回退编辑器 SQL
        TextArea preview = preview(content);
        assertThat(preview).isNotNull();
        assertThat(WaitForAsyncUtils.asyncFx(preview::isEditable).get()).isFalse();
        assertThat(WaitForAsyncUtils.asyncFx(() -> preview.getText()).get()).contains("SELECT 1");

        // 保留：DialogPane 只挂 CLOSE，插入按钮在 content 里而非动作按钮
        assertThat(WaitForAsyncUtils.asyncFx(() -> dialog.getDialogPane().getButtonTypes()).get())
                .containsExactly(javafx.scene.control.ButtonType.CLOSE);
        assertThat(WaitForAsyncUtils.asyncFx(() -> content.lookup("#query-builder-insert")).get())
                .isNotNull();

        ComboBox<String> table = tableChoice(content);
        assertThat(table).as("必须提供表选择控件").isNotNull();

        // 等后台把表清单加载出来（真 SQLite 里有 users）——轮询，不是单次 FX 执行
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS,
                () -> WaitForAsyncUtils.asyncFx(() -> table.getItems().contains("users")).get());

        // 选表 → 触发生成；等列加载完 + 预览变成生成的 SELECT
        interact(() -> table.getSelectionModel().select("users"));
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> {
            String text = WaitForAsyncUtils.asyncFx(() -> preview.getText()).get();
            return text != null && text.startsWith("SELECT") && text.contains("FROM \"users\"");
        });

        String generated = WaitForAsyncUtils.asyncFx(() -> preview.getText()).get();
        assertThat(generated).startsWith("SELECT * FROM \"users\"");

        // 插入编辑器：只回填文本，不执行（结果页签数不变）
        javafx.scene.control.Button insert = WaitForAsyncUtils.asyncFx(() ->
                (javafx.scene.control.Button) content.lookup("#query-builder-insert")).get();
        WaitForAsyncUtils.asyncFx(insert::fire).get();
        assertThat(page.sql()).isEqualTo(generated);
        assertThat(page.resultTabCount()).as("插入构造器 SQL 绝不执行").isEqualTo(tabsBefore);
    }

    @Test
    void builderAlwaysExposesNewControls() throws Exception {
        Dialog<?> dialog = WaitForAsyncUtils.asyncFx(page::builderDialog).get();
        Node content = content(dialog);
        assertThat(WaitForAsyncUtils.asyncFx(() -> content.lookup("#query-builder-table")).get()).isNotNull();
        assertThat(WaitForAsyncUtils.asyncFx(() -> content.lookup("#query-builder-insert")).get()).isNotNull();
        assertThat(WaitForAsyncUtils.asyncFx(() -> content.lookup("#query-builder-add-row")).get()).isNotNull();
        assertThat(WaitForAsyncUtils.asyncFx(() -> content.lookup("#query-builder-copy")).get()).isNotNull();
    }
}
