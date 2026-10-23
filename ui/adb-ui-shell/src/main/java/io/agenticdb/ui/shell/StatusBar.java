package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 状态栏：左 / 中 / 右三段。
 *
 * <p>⛔ 不要用「一条长消息」代替分段 —— 用户需要一眼定位是连接、事务还是选中行出了问题。
 *
 * <p>⭐ 2026-10 版式对齐 Navicat：**选中行数与耗时居中**，
 * 左边是「现在连着什么库、在看哪几行、刚发生了什么」，右边是事务 / 权限 / 版本。
 * 选中信息是操作时的即时反馈，放正中比缩在右侧更容易被扫到。
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
    /**
     * ⭐ 第十八轮：耗时段在「没有计时」时**整段隐藏**（连同它前面的分隔符）。
     *
     * <p>以前 {@code setDuration(-1)} 写的是占位文字 {@code "— ms"} —— 而这个方法
     * 在三处的调用意图都是「清掉计时」（未连接 / 非查询页）。于是在表清单页的状态栏上
     * 出现「<b>未选择 — ms</b>」：悬空的破折号读起来像界面坏了，Navicat 在此处只说
     * 「已选择 1 个项目」，没有计时就什么都不显示。
     */
    private final Label duration = segment("— ms", "adb-status__duration");
    /** 耗时段的分隔符 —— 必须与 {@link #duration} 同进退，否则会剩一个孤零零的「·」。 */
    private final Region durationSeparator = separator();
    private final Label transaction = segment("自动提交", "adb-status__transaction");
    private final Label dirty = segment("", "adb-status__dirty");
    private final Label readonly = segment("只读", "adb-status__readonly");
    private final Label version = segment(io.agenticdb.ui.AppVersion.DISPLAY, "adb-status__version");
    /** ⭐ U6：服务器信息（版本 · 当前用户）。 */
    private final Label server = segment("—", "adb-status__server");

    public StatusBar() {
        getStyleClass().add("adb-statusbar");
        setAlignment(Pos.CENTER_LEFT);
        int height = io.agenticdb.ui.Tokens.H_STATUSBAR_LIGHT;
        setPrefHeight(height);
        setMinHeight(height);
        setMaxHeight(height);
        dirty.setVisible(false);
        dirty.setManaged(false);
        // 起步没有计时（还没跑过任何语句）→ 耗时段连同分隔符一起不占位。
        duration.setVisible(false);
        duration.setManaged(false);
        durationSeparator.setVisible(false);
        durationSeparator.setManaged(false);

        HBox left = new HBox(6, dialect, separator(), connection, separator(), range,
                separator(), message, dirty);
        left.setAlignment(Pos.CENTER_LEFT);
        left.getStyleClass().add("adb-statusbar__left");

        HBox center = new HBox(6, selection, durationSeparator, duration);
        center.setAlignment(Pos.CENTER);
        center.getStyleClass().add("adb-statusbar__center");

        HBox right = new HBox(6, transaction, separator(), readonly, separator(), server,
                separator(), version);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.getStyleClass().add("adb-statusbar__right");

        // 左右各一个弹性空隙：中间段始终落在窗口正中。
        Region leftSpacer = new Region();
        Region rightSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        getChildren().addAll(left, leftSpacer, center, rightSpacer, right);
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

    /**
     * ⭐ N5：由页面直接给出选中项文案（表清单是「对象」不是「行」）。
     *
     * <p>数据页说「已选 7 行」；表清单说「已选 users · 200 行」/「已选 3 个项目」。
     * 两处含义不同，不能都塞进「行」这一个量词里。
     */
    public void setSelectionText(String text) {
        selection.setText(text == null || text.isBlank() ? "未选择" : text);
    }

    /**
     * @param millis 本次语句耗时；**负数表示「没有计时」**（未连接 / 非查询页），
     *               此时整段连同分隔符隐藏 —— 不留「— ms」这种悬空占位。
     */
    public void setDuration(long millis) {
        boolean present = millis >= 0;
        duration.setVisible(present);
        duration.setManaged(present);
        durationSeparator.setVisible(present);
        durationSeparator.setManaged(present);
        if (present) {
            duration.setText(millis + " ms");
        }
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
        // ⭐ 状态点用矢量实心圆（不是 Unicode 的 ●）。
        dirty.setGraphic(Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT));
        dirty.setText("  " + count + " 待提交");
        dirty.setVisible(count > 0);
        dirty.setManaged(count > 0);
    }

    /**
     * ⭐ U6：服务器信息（版本 · 用户）。
     *
     * <p>「连的是哪个版本的什么库、以什么身份连的」是排查行为差异时最先要看的信息 ——
     * 以前状态栏里根本没有这一项。
     */
    public void setServerInfo(String version, String user) {
        String text = version == null ? "" : version.trim();
        if (user != null && !user.isBlank()) {
            text = text.isEmpty() ? user.trim() : text + " · " + user.trim();
        }
        server.setText(text.isEmpty() ? "—" : text);
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
