package io.agenticdb.ui.shell;

import io.agenticdb.introspect.RoutineSql;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * H2-a 例程设计器最小切片：<b>只读</b>查看定义体 + 重建语句预览。
 *
 * <p>本版本<b>不编辑、不执行、不重建</b>例程：只有方言确实支持
 * {@code CREATE OR REPLACE} 时，预览区才有一条可重放语句；否则预览区留空，
 * 说明 Label 明确写出本版本不提供编辑 / 重建 —— 不摆点了没反应的按钮。
 *
 * <p>渲染逻辑抽成静态纯函数 {@link #compose(String, DialectKind, String, boolean)}：
 * 测试可以直接调它验证三块文本，<b>不必起 JavaFX 场景图</b>。
 */
final class RoutineViewerPane extends VBox {

    private final TextArea definitionArea = new TextArea();
    private final TextArea previewArea = new TextArea();
    private final Label noteLabel = new Label();

    private final String definitionText;
    private final String previewText;
    private final String noteText;

    /** 纯静态渲染结果，供构造与测试共用。 */
    record Content(String definition, String preview, String note) {
    }

    /**
     * @param session        数据库会话
     * @param routineName    例程名
     * @param function       true = 函数，false = 存储过程
     * @param schemaService  例程定义体的读取口
     */
    RoutineViewerPane(DbSession session, String routineName, boolean function,
                      SchemaService schemaService) {
        super(8);

        definitionArea.setId("routine-definition");
        definitionArea.setEditable(false);
        definitionArea.setWrapText(false);

        previewArea.setId("routine-preview");
        previewArea.setEditable(false);
        previewArea.setWrapText(false);

        noteLabel.setId("routine-note");
        noteLabel.setWrapText(true);
        noteLabel.getStyleClass().add("adb-muted");

        Label definitionTitle = new Label(I18n.t("routine.definition.title"));
        definitionTitle.getStyleClass().add("adb-muted");
        Label previewTitle = new Label(I18n.t("routine.preview.title"));
        previewTitle.getStyleClass().add("adb-muted");

        Content content = render(session, routineName, function, schemaService);
        this.definitionText = content.definition();
        this.previewText = content.preview();
        this.noteText = content.note();
        definitionArea.setText(content.definition());
        previewArea.setText(content.preview());
        noteLabel.setText(content.note());

        getChildren().addAll(definitionTitle, definitionArea,
                previewTitle, previewArea, noteLabel);
    }

    /**
     * 从会话读取并渲染。{@code SchemaService.routineDefinition} 自身已吞掉 SQLException，
     * 这里再兜底运行期异常（会话为空、方言反查失败、资源加载失败等），
     * 在 note 里如实说明，<b>绝不让异常冒到 FX 线程</b>。
     */
    static Content render(DbSession session, String routineName, boolean function,
                          SchemaService schemaService) {
        try {
            String definition = schemaService.routineDefinition(session, routineName, !function);
            DialectKind kind = session.dialectKind();
            return compose(definition, kind, routineName, function);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            return new Content("", "", I18n.t("routine.note.error", detail));
        }
    }

    /** 纯逻辑：给定定义体与方言算出三块文本；不碰会话、不碰场景图。 */
    static Content compose(String definition, DialectKind kind,
                           String routineName, boolean function) {
        String safeDefinition = definition == null ? "" : definition;
        if (!RoutineSql.supported(kind)) {
            return new Content(safeDefinition, "", I18n.t("routine.note.unsupported"));
        }
        if (safeDefinition.isBlank()) {
            return new Content(safeDefinition, "", I18n.t("routine.note.empty"));
        }
        String preview = RoutineSql.preview(routineName, function, safeDefinition, kind);
        return new Content(safeDefinition, preview, I18n.t("routine.note.supported"));
    }

    String definitionTextForTest() {
        return definitionText;
    }

    String previewTextForTest() {
        return previewText;
    }

    String noteTextForTest() {
        return noteText;
    }
}
