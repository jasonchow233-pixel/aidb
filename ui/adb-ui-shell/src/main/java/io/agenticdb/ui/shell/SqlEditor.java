package io.agenticdb.ui.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.util.function.Consumer;

/**
 * SQL 编辑器：RichTextFX CodeArea + 行号 + 实时高亮 + 空态提示。
 *
 * <p>高亮是「宽容」的：SQL 有语法错误时仍然按 token 上色，不会因为一个
 * 未闭合引号让整段编辑区变成纯文本。
 */
public final class SqlEditor extends StackPane {

    private final CodeArea area = new CodeArea();
    private final Label hint = new Label("输入 SQL，⌘↵ 执行当前语句，⇧⌘↵ 执行全部");

    public SqlEditor() {
        getStyleClass().add("adb-query__editor-box");
        area.setId("query-editor");
        area.getStyleClass().add("adb-sql-editor");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.setWrapText(false);
        area.setStyleSpans(0, SqlHighlighter.compute(""));
        area.textProperty().addListener((observable, oldText, newText) -> {
            area.setStyleSpans(0, SqlHighlighter.compute(newText));
            updateHint();
        });

        hint.getStyleClass().add("adb-query__hint");
        hint.setMouseTransparent(true);
        StackPane.setAlignment(hint, Pos.TOP_LEFT);
        StackPane.setMargin(hint, new Insets(9, 12, 0, 48));

        getChildren().addAll(area, hint);
        updateHint();
    }

    public CodeArea area() {
        return area;
    }

    public String text() {
        return area.getText();
    }

    public void setText(String text) {
        area.replaceText(text == null ? "" : text);
        area.moveTo(area.getLength());
    }

    public int caret() {
        return area.getCaretPosition();
    }

    public void moveTo(int offset) {
        int clamped = Math.max(0, Math.min(offset, area.getLength()));
        area.moveTo(clamped);
        area.requestFollowCaret();
    }

    public void select(int start, int end) {
        int from = Math.max(0, Math.min(start, area.getLength()));
        int to = Math.max(from, Math.min(end, area.getLength()));
        area.selectRange(from, to);
        area.requestFollowCaret();
    }

    public void requestEditorFocus() {
        area.requestFocus();
    }

    public void addTextListener(Consumer<String> listener) {
        area.textProperty().addListener((observable, oldText, newText) -> listener.accept(newText));
    }

    private void updateHint() {
        boolean empty = area.getText().isEmpty();
        hint.setVisible(empty);
        hint.setManaged(empty);
    }
}
