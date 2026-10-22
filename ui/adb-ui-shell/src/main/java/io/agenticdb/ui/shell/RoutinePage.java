package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.concurrent.Executor;

/**
 * O2：函数 / 存储过程的**定义体**（只读标签页）。
 *
 * <p>形态依据 {@code docs/UI-FORMS-AND-STATES.md} §4：定义体是「**要长期停留读代码**」
 * 的内容 → <b>标签页</b>（不是弹窗）。用户会来回对比两个函数的定义。
 *
 * <p>以前对象树里只有函数/过程的**名字** —— 点开什么也看不到（O2）。
 * 现在三种方言都能读回定义体原文（来源见 {@code Dialect.readRoutineDefinition}）：
 * SQLite 走 {@code sqlite_master}，PostgreSQL 走 {@code pg_get_functiondef}，
 * MySQL 走 {@code SHOW CREATE FUNCTION}。
 */
final class RoutinePage extends VBox {

    private final Label title = new Label();
    private final Label meta = new Label();
    private final TextArea body = new TextArea();
    private final Label error = new Label();

    /**
     * @param event true = 这是个**调度事件**（MySQL EVENT），不是函数 / 过程
     */
    RoutinePage(String name, boolean procedure, DbSession session, SchemaService schemaService,
                Executor dbExecutor, java.util.function.Consumer<String> status) {
        this(name, procedure, false, session, schemaService, dbExecutor, status);
    }

    RoutinePage(String name, boolean procedure, boolean scheduledEvent, DbSession session,
                SchemaService schemaService, Executor dbExecutor,
                java.util.function.Consumer<String> status) {
        getStyleClass().addAll("adb-page", "adb-routine");

        title.setText(name);
        title.getStyleClass().add("adb-page__title");
        title.setId("routine-title");

        meta.getStyleClass().add("adb-muted");
        meta.setId("routine-meta");
        meta.setText(scheduledEvent ? "调度事件" : (procedure ? "存储过程" : "函数"));

        Button copy = IconButtons.of(Icons.Icon.COPY, "复制定义体");
        copy.setId("routine-copy");
        copy.getStyleClass().add("adb-button");
        copy.setOnAction(event -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(body.getText() == null ? "" : body.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            if (status != null) {
                status.accept("已复制定义体");
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, copy, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        body.setId("routine-body");
        body.setEditable(false);
        body.setWrapText(false);
        body.getStyleClass().add("adb-structure__ddl");
        VBox.setVgrow(body, Priority.ALWAYS);

        error.setId("routine-error");
        error.getStyleClass().add("adb-error");
        error.setVisible(false);

        HBox header = new HBox(10, title, meta, spacer, actions);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, body, error);
        content.getStyleClass().add("adb-page__body");
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().addAll(header, content);

        if (session == null) {
            fail("尚未连接");
            return;
        }
        load(name, procedure, scheduledEvent, session, schemaService, dbExecutor);
    }

    private void load(String name, boolean procedure, boolean scheduledEvent, DbSession session,
                       SchemaService schemaService, Executor dbExecutor) {
        if (dbExecutor == null) {
            fail("没有可用的执行器");
            return;
        }
        dbExecutor.execute(() -> {
            String definition = scheduledEvent
                    ? schemaService.eventDefinition(session, name)
                    : schemaService.routineDefinition(session, name, procedure);
            javafx.application.Platform.runLater(() -> {
                if (definition == null || definition.isBlank()) {
                    fail(session.dialect().displayName() + " 读不到「" + name + "」的定义体");
                    return;
                }
                body.setText(definition);
            });
        });
    }

    private void fail(String reason) {
        error.setText(reason);
        error.setVisible(true);
    }
}
