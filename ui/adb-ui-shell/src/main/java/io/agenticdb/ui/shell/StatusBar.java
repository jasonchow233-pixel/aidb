package io.agenticdb.ui.shell;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 状态栏：10 个固定位置。
 *
 * <p>⛔ 不要用「一条长消息」代替分段 —— 用户需要一眼定位是连接、事务还是选中行出了问题。
 *
 * <p>⚠️ 4.5-11：以前 {@code setEnv} / {@code setSelection} / {@code setTransaction}
 * <b>零调用</b>，所以「DEV」「未选择」「自动提交」永远是这三个字。
 * 现在三个字段都接了真实数据：
 * <ul>
 *   <li>{@code env} → 当前连接的<b>方言</b>（SQLite / PostgreSQL / MySQL）；</li>
 *   <li>{@code selection} → 数据页<b>选中行数</b>；</li>
 *   <li>{@code transaction} → 会话<b>真实事务状态</b>（自动提交 / 事务中）。</li>
 * </ul>
 */
public final class StatusBar extends HBox {

    private final Label dialect = segment("—", "adb-status__dialect");
    private final Label connection = segment("未连接", "adb-status__connection");
    private final Label range = segment("0 行", "adb-status__range");
    private final Label message = segment("就绪", "adb-status__message");
    private final Label selection = segment("未选择", "adb-status__selection");
    private final Label duration = segment("— ms", "adb-status__duration");
    private final Label transaction = segment("自动提交", "adb-status__transaction");
    private final Label dirty = segment("", "adb-status__dirty");
    private final Label readonly = segment("只读", "adb-status__readonly");
    private final Label version = segment("v0.1.0 · P3", "adb-status__version");

    public StatusBar() {
        getStyleClass().add("adb-statusbar");
        setAlignment(Pos.CENTER_LEFT);
        setPrefHeight(26);
        setMinHeight(26);
        setMaxHeight(26);
        dirty.setVisible(false);
        dirty.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                dialect, separator(), connection, separator(), range, separator(), message,
                spacer,
                dirty, separator(), selection, separator(), duration, separator(),
                transaction, separator(), readonly, separator(), version);
    }

    /**
     * 当前连接的方言（SQLite / PostgreSQL / MySQL）。
     *
     * <p>4.5-11：以前这个位置显示写死的「DEV」，而且 {@code setEnv} 零调用 ——
     * 连测试环境都分不出来。现在显示的是真正有用的信息：当前连的是什么库。
     */
    public void setDialect(String value) {
        dialect.setText(value == null || value.isBlank() ? "—" : value);
    }

    public void setConnection(String value) {
        connection.setText(value);
    }

    public void setRange(String value) {
        range.setText(value);
    }

    public void setMessage(String value) {
        message.setText(value);
    }

    public void setSelection(int count) {
        selection.setText(count <= 0 ? "未选择" : "已选 " + count + " 行");
    }

    public void setDuration(long millis) {
        duration.setText(millis < 0 ? "— ms" : millis + " ms");
    }

    /**
     * 会话的真实事务状态。
     *
     * <p>4.5-11：以前这个位置写死「自动提交」，{@code setTransaction} 零调用。
     */
    public void setTransaction(boolean inTransaction) {
        transaction.setText(inTransaction ? "事务中" : "自动提交");
    }

    public void setDirty(int count) {
        dirty.setText("● " + count + " 待提交");
        dirty.setVisible(count > 0);
        dirty.setManaged(count > 0);
    }

    public void setReadonly(boolean value) {
        readonly.setText(value ? "只读" : "可写");
    }

    public String message() {
        return message.getText();
    }

    /** 供门禁断言：当前显示的方言。 */
    public String dialectText() {
        return dialect.getText();
    }

    /** 供门禁断言：当前显示的选中行数。 */
    public String selectionText() {
        return selection.getText();
    }

    /** 供门禁断言：当前显示的事务状态。 */
    public String transactionText() {
        return transaction.getText();
    }

    private static Label segment(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        label.setAlignment(Pos.CENTER_LEFT);
        return label;
    }

    private static Region separator() {
        Region region = new Region();
        region.getStyleClass().add("adb-status__separator");
        return region;
    }
}
