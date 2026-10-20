package io.agenticdb.ui.shell;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 状态栏：10 个固定位置。P0 先显示静态骨架，P1 起接真实会话。
 *
 * <p>⛔ 不要用「一条长消息」代替分段 —— 用户需要一眼定位是连接、事务还是选中行出了问题。
 */
public final class StatusBar extends HBox {

    private final Label env = segment("DEV", "adb-status__env", "adb-status__env--dev");
    private final Label connection = segment("未连接", "adb-status__connection");
    private final Label range = segment("0 行", "adb-status__range");
    private final Label message = segment("就绪", "adb-status__message");
    private final Label selection = segment("未选择", "adb-status__selection");
    private final Label duration = segment("— ms", "adb-status__duration");
    private final Label transaction = segment("自动提交", "adb-status__transaction");
    private final Label dirty = segment("", "adb-status__dirty");
    private final Label readonly = segment("只读", "adb-status__readonly");
    private final Label version = segment("v0.1.0 · P2", "adb-status__version");

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
                env, separator(), connection, separator(), range, separator(), message,
                spacer,
                dirty, separator(), selection, separator(), duration, separator(),
                transaction, separator(), readonly, separator(), version);
    }

    public void setEnv(String value, String styleClass) {
        env.setText(value);
        env.getStyleClass().removeIf(c -> c.startsWith("adb-status__env--"));
        env.getStyleClass().add(styleClass);
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

    public void setSelection(String value) {
        selection.setText(value);
    }

    public void setDuration(long millis) {
        duration.setText(millis < 0 ? "— ms" : millis + " ms");
    }

    public void setTransaction(String value) {
        transaction.setText(value);
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
