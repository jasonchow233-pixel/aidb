package io.agenticdb.ui.shell;

import javafx.animation.PauseTransition;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * 清单类页面（表清单 / 对象首页）表格的公共行为。
 *
 * <p>两页以前各写各的：列宽拖完就忘、长注释截断后无从看全文。
 * 这里收成一处，两页共用同一套语义与同一记忆格式。
 */
final class ListColumns {

    /** 列宽落盘的防抖时长：拖一把会触发几十次宽度变化。 */
    static final int DEBOUNCE_MILLIS = 400;

    /** 存/读的宽度必须能用：0 是「还没布局」的结果，不是用户的意图。 */
    static final int MIN_STORED = 40;
    static final int MAX_STORED = 1200;

    /** 单元格左右内边距 —— 估算文本占多宽时不能把它算进去。 */
    private static final double CELL_CHROME = ColumnWidths.LIST_CHROME;

    private ListColumns() {
    }

    // ───────────────────────── 纯逻辑 ─────────────────────────

    static boolean usable(double width) {
        return width >= MIN_STORED && width <= MAX_STORED;
    }

    /**
     * 这段文本在这一列里**放不下**（CJK 按两个字宽算）。
     *
     * <p>判定用列的配置宽度（{@code getPrefWidth}）而不是布局后的
     * {@code getWidth}：后者被约束策略按窗口宽度改写过，
     * 用它判定会让「窗口一拉宽，Tooltip 就消失了」。
     */
    static boolean truncated(String text, double columnWidth) {
        if (text == null || text.isBlank() || "—".equals(text)) {
            return false;
        }
        return ColumnWidths.visualLength(text) * ColumnWidths.CHAR_UNIT + CELL_CHROME
                > columnWidth;
    }

    /** 截断的、且是**真值**的那一格才配得上 Tooltip（「—」不是信息）。 */
    static boolean needsTooltip(String text, double columnWidth) {
        return truncated(text, columnWidth);
    }

    static String widthKey(String prefix, String title) {
        return prefix + ".width." + title;
    }

    // ───────────────────────── 接线 ─────────────────────────

    /** 记住列宽：先还原上次的，再盯住宽度变化防抖落盘。 */
    static void installWidthMemory(TableView<?> table, String prefix, SettingsStore settings) {
        restore(table, prefix, settings);
        PauseTransition timer = new PauseTransition(Duration.millis(DEBOUNCE_MILLIS));
        timer.setOnFinished(event -> save(table, prefix, settings));
        for (TableColumn<?, ?> column : table.getColumns()) {
            column.widthProperty().addListener((obs, oldValue, newValue) -> timer.playFromStart());
        }
    }

    static void save(TableView<?> table, String prefix, SettingsStore settings) {
        for (TableColumn<?, ?> column : table.getColumns()) {
            if (column.getText() == null || column.getText().isBlank()) {
                continue;
            }
            if (usable(column.getWidth())) {
                settings.setInt(widthKey(prefix, column.getText()),
                        (int) Math.round(column.getWidth()));
            }
        }
    }

    static void restore(TableView<?> table, String prefix, SettingsStore settings) {
        for (TableColumn<?, ?> column : table.getColumns()) {
            if (column.getText() == null || column.getText().isBlank()) {
                continue;
            }
            int saved = settings.getInt(widthKey(prefix, column.getText()), -1);
            if (saved >= MIN_STORED && saved <= MAX_STORED) {
                column.setPrefWidth(saved);
            }
        }
    }

    /**
     * 给这一格挂上「截断时才出现」的 Tooltip。
     *
     * <p>为什么不是每格都挂：短值明明完整显示着，鼠标一扫就弹出一个一模一样的
     * 气泡，那是噪音；而截断的那一格，Tooltip 是拿到全文的**唯一**出口。
     */
    static void applyTooltip(TableCell<?, ?> cell, String text) {
        TableColumn<?, ?> column = cell.getTableColumn();
        if (column == null || !needsTooltip(text, column.getPrefWidth())) {
            cell.setTooltip(null);
            return;
        }
        // 不挂自定义样式类：app.css 已有一条全局 .tooltip 规则，
        // 再加一个没人定义的类只会让 StyleClassCoverageTest 红。
        cell.setTooltip(new Tooltip(text));
    }
}
