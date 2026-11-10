package io.agenticdb.ui.shell;

import io.agenticdb.introspect.RoutineSql;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * O2：函数 / 存储过程的**定义体**标签页。
 *
 * <p>形态依据 {@code docs/UI-FORMS-AND-STATES.md} §4：定义体是「**要长期停留读代码**」
 * 的内容 → <b>标签页</b>（不是弹窗）。用户会来回对比两个函数的定义。
 *
 * <p>三种方言都能读回定义体原文（来源见 {@code Dialect.readRoutineDefinition}）：
 * SQLite 走 {@code sqlite_master}，PostgreSQL 走 {@code pg_get_functiondef}，
 * MySQL 走 {@code SHOW CREATE FUNCTION}。
 *
 * <p><b>编辑 / 执行</b>：只有确实支持单条 {@code CREATE OR REPLACE} 的方言
 * （当前仅 PostgreSQL）才开放 —— 定义体可编辑，点 Execute 即按编辑后的文本重建例程。
 * 其余方言（MySQL / MariaDB / SQL Server / Oracle / SQLite）没有 JDBC 安全的重建路径，
 * 定义体保持只读，也<b>不摆执行按钮</b>（绝不制造点了没反应或可能写坏库的死交互）。
 */
final class RoutinePage extends VBox {

    private final Label title = new Label();
    private final Label meta = new Label();
    private final TextArea body = new TextArea();
    private final Label error = new Label();
    /** 重建预览（只读）：由编辑后的定义体实时算出 CREATE OR REPLACE。 */
    private final Label previewCaption = new Label();
    private final TextArea preview = new TextArea();
    private final Label note = new Label();

    private final Button copy = IconButtons.of(Icons.Icon.COPY, I18n.t("routine.copy"));
    private final Button executeButton = IconButtons.of(Icons.Icon.RUN, I18n.t("routine.execute"));

    private final String routineName;
    private final boolean procedure;
    private final boolean scheduledEvent;
    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final Consumer<String> status;
    private final DialectKind dialectKind;
    /** 仅 PostgreSQL 这一家本版本开放编辑与执行；其余方言只读。 */
    private final boolean editable;

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
        this.routineName = name;
        this.procedure = procedure;
        this.scheduledEvent = scheduledEvent;
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.status = status;
        this.dialectKind = session != null ? session.dialectKind() : null;
        this.editable = !scheduledEvent && RoutineSql.supported(dialectKind);

        getStyleClass().addAll("adb-page", "adb-routine");

        title.setText(name);
        title.getStyleClass().add("adb-page__title");
        title.setId("routine-title");

        meta.getStyleClass().add("adb-muted");
        meta.setId("routine-meta");
        meta.setText(scheduledEvent ? I18n.t("routine.kind.event")
                : (procedure ? I18n.t("object.kind.procedure") : I18n.t("object.kind.function")));

        copy.setId("routine-copy");
        copy.getStyleClass().add("adb-button");
        copy.setOnAction(event -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(body.getText() == null ? "" : body.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            if (status != null) {
                status.accept(I18n.t("routine.copied"));
            }
        });

        executeButton.setId("routine-execute");
        executeButton.getStyleClass().add("adb-button");
        executeButton.setDisable(!editable);
        executeButton.setOnAction(event -> executeRoutine());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, copy, executeButton, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        body.setId("routine-body");
        body.setEditable(editable);
        body.setWrapText(false);
        body.getStyleClass().add("adb-structure__ddl");
        VBox.setVgrow(body, Priority.ALWAYS);
        // 编辑定义体时实时重算重建预览（只读方言下编辑被禁，监听不会触发实质变化）。
        body.textProperty().addListener((obs, oldValue, newValue) -> refreshDerived());

        previewCaption.setId("routine-preview-caption");
        previewCaption.setText(I18n.t("routine.preview.title"));
        previewCaption.getStyleClass().add("adb-muted");
        preview.setId("routine-preview");
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setPrefRowCount(4);
        preview.getStyleClass().add("adb-structure__ddl");
        note.setId("routine-note");
        note.getStyleClass().add("adb-muted");
        note.setWrapText(true);

        error.setId("routine-error");
        error.getStyleClass().add("adb-error");
        error.setVisible(false);

        HBox header = new HBox(10, title, meta, spacer, actions);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, body, previewCaption, preview, note, error);
        content.getStyleClass().add("adb-page__body");
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().addAll(header, content);

        if (session == null) {
            fail(I18n.t("routine.noSession"));
            return;
        }
        load();
    }

    private void load() {
        if (dbExecutor == null) {
            fail(I18n.t("routine.noExecutor"));
            return;
        }
        dbExecutor.execute(() -> {
            String definition = scheduledEvent
                    ? schemaService.eventDefinition(session, routineName)
                    : schemaService.routineDefinition(session, routineName, procedure);
            javafx.application.Platform.runLater(() -> {
                if (definition == null || definition.isBlank()) {
                    fail(I18n.t("routine.readFailed",
                            dialectKind == null ? "?" : dialectKind.displayName(), routineName));
                    return;
                }
                body.setText(definition);
                refreshDerived();
            });
        });
    }

    /**
     * 依据当前 body 文本重算重建预览区与说明文字。编辑时由文本监听实时调用；
     * 只读方言下 body 不可编辑，预览只反映库返回的定义体。
     */
    private void refreshDerived() {
        if (session == null || dialectKind == null || scheduledEvent) {
            preview.setText("");
            preview.setVisible(false);
            preview.setManaged(false);
            previewCaption.setVisible(false);
            previewCaption.setManaged(false);
            note.setText(I18n.t("routine.note.unsupported"));
            return;
        }
        RoutineViewerPane.Content composed = RoutineViewerPane.compose(
                body.getText(), dialectKind, routineName, !procedure);
        boolean hasPreview = !composed.preview().isBlank();
        preview.setText(composed.preview());
        preview.setVisible(hasPreview);
        preview.setManaged(hasPreview);
        previewCaption.setVisible(hasPreview);
        previewCaption.setManaged(hasPreview);
        note.setText(composed.note());
    }

    /** 按编辑后的定义体重建例程：先算 CREATE OR REPLACE，再在后台执行，最后回读刷新。 */
    private void executeRoutine() {
        if (dbExecutor == null) {
            fail(I18n.t("routine.noExecutor"));
            return;
        }
        if (status != null) {
            status.accept(I18n.t("routine.executing", routineName));
        }
        dbExecutor.execute(() -> {
            try {
                String definition = body.getText();
                String sql = RoutineSql.preview(routineName, !procedure, definition, dialectKind);
                if (sql == null || sql.isBlank()) {
                    javafx.application.Platform.runLater(() ->
                            fail(I18n.t("routine.cannotRebuild", dialectKind.displayName())));
                    return;
                }
                // ⭐ CREATE OR REPLACE 被危险分级判为 DESTRUCTIVE，execute 只校验「声明=真实危险」，
                // 不拦截执行；重建例程是用户点 Execute 的显式意图，无需额外确认框。
                ExecutionResult result = session.execute(sql);
                String reloaded = scheduledEvent
                        ? schemaService.eventDefinition(session, routineName)
                        : schemaService.routineDefinition(session, routineName, procedure);
                javafx.application.Platform.runLater(() -> {
                    if (reloaded != null && !reloaded.isBlank()) {
                        body.setText(reloaded);
                    }
                    error.setVisible(false);
                    if (status != null) {
                        status.accept(I18n.t("routine.executed", routineName));
                    }
                });
            } catch (SQLException ex) {
                String detail = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName() : ex.getMessage();
                javafx.application.Platform.runLater(() ->
                        fail(I18n.t("routine.executeFailed", detail)));
            }
        });
    }

    private void fail(String reason) {
        error.setText(reason);
        error.setVisible(true);
    }
}
