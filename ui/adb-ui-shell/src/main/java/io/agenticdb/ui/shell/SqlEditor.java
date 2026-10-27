package io.agenticdb.ui.shell;

import io.agenticdb.sql.SqlBrackets;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.LineNumberFactory;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * SQL 编辑器：RichTextFX CodeArea + 行号 + 实时高亮 + 空态提示。
 *
 * <p>高亮是「宽容」的：SQL 有语法错误时仍然按 token 上色，不会因为一个
 * 未闭合引号让整段编辑区变成纯文本。
 */
public final class SqlEditor extends StackPane {

    private final CodeArea area = new CodeArea();
    private final Label hint = new Label("输入 SQL，" + io.agenticdb.ui.Shortcuts.of("Mod+Enter") + " 执行当前语句，" + io.agenticdb.ui.Shortcuts.of("Shift+Mod+Enter") + " 执行全部");

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
            updateBracketHighlight();
        });
        // ⭐ Q1：光标移动时高亮配对的括号。
        area.caretPositionProperty().addListener(
                (observable, oldPosition, newPosition) -> updateBracketHighlight());

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

    /**
     * 换掉空态提示。
     *
     * <p>默认那句是教用户「怎么执行 SQL」的，只读视图（DDL 页 / 新建表预览）
     * 拿它当空态就是错的 —— 那里没有可执行的东西。
     * 传 {@code null} 或空串等于清空提示。
     */
    public void setHint(String text) {
        hint.setText(text == null ? "" : text);
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

    /**
     * ⭐ Q1：高亮配对的括号。
     *
     * <p>RichTextFX 0.11 没有 {@code addInlineStyle}，
     * 所以走 {@link SqlHighlighter#withBracket}：把高亮类的样式段拆开、
     * 把新类加进去 —— SQL 的语法配色不受影响。
     */
    private void updateBracketHighlight() {
        String text = area.getText();
        if (text == null || text.isEmpty()) {
            area.setStyleSpans(0, SqlHighlighter.compute(text == null ? "" : text));
            return;
        }
        StyleSpans<Collection<String>> base = SqlHighlighter.compute(text);
        SqlBrackets.Match match = SqlBrackets.matchAt(text, area.getCaretPosition());
        if (match == null) {
            area.setStyleSpans(0, base);
            return;
        }
        area.setStyleSpans(0, SqlHighlighter.withBracket(
                SqlHighlighter.withBracket(base, match.start()), match.end()));
    }

    private void updateHint() {
        boolean empty = area.getText().isEmpty();
        hint.setVisible(empty);
        hint.setManaged(empty);
    }
}
