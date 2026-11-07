package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.sql.SqlBrackets;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.LineNumberFactory;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * SQL 编辑器：RichTextFX CodeArea + 行号 + 实时高亮 + 空态提示 + 溢出滚动条。
 *
 * <p>高亮是「宽容」的：SQL 有语法错误时仍然按 token 上色，不会因为一个
 * 未闭合引号让整段编辑区变成纯文本。
 *
 * <p>⭐ D-97：CodeArea 外面套一层 flowless 的 {@link VirtualizedScrollPane}。
 * 0.11.7 的 {@code GenericStyledArea} 是裸 Region、自己不带 ScrollBar（{@code javap}
 * 实测只有 {@code estimatedScrollX} / {@code totalWidthEstimate}），所以单行超出盒子宽度时
 * 内容不丢、但界面上没有任何线索说右边还有东西 —— 只有把光标点到行尾，内容才跟着
 * 光标横移出来。横条只能由外层虚拟化视口给出，这条路是 RichTextFX 官方给的，
 * 不是自创：{@code VirtualizedScrollPane} 读 CodeArea 的 {@code totalWidthEstimate}
 * 来摆条，并把条的位置回灌给编辑区。
 *
 * <p>两条都用 {@code AS_NEEDED}：短文本与折行盒（视图设计器那两格是 D-95 开的折行）
 * 仍然一条都不出现，避免做成装饰条。
 */
public final class SqlEditor extends StackPane {

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> viewport = new VirtualizedScrollPane<>(
            area, ScrollPane.ScrollBarPolicy.AS_NEEDED, ScrollPane.ScrollBarPolicy.AS_NEEDED);
    private final Label hint = new Label(I18n.t("editor.hint",
            io.agenticdb.ui.Shortcuts.of("Mod+Enter"),
            io.agenticdb.ui.Shortcuts.of("Shift+Mod+Enter")));

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

        getChildren().addAll(viewport, hint);
        updateHint();
    }

    /**
     * 编辑区本体。
     *
     * <p>D-97 起它的父件是视口而不是这个盒子本身 —— 宽高、滚动位置都在视口那一层，
     * 但 {@link CodeArea#requestFollowCaret()} 仍然有效：视口会把条的位置回灌给编辑区。
     */
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
