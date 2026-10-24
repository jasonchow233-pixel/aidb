package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import java.util.function.LongSupplier;

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
 *
 * <p>⭐ UX-5-D：补上 Navicat 状态栏里我们缺的两项信息 ——
 * 左段「<b>当前语句</b>」（截断只发生在渲染层，点击复制的是原文），
 * 右段「<b>最后刷新</b>」的相对时间。
 * 「12 ms」说的是这次跑了多久，回答不了「这屏数据多旧」；
 * 而排查一条取数行为时，能一眼看到界面用的到底是哪条 SQL、并且把它复制走，
 * 是唯一不需要用户去查询页重新拼一遍的路径。
 */
public final class StatusBar extends HBox {

    /**
     * ⭐ UX-5-D：语句段的最大宽度。
     *
     * <p>封顶是为了<b>界面</b>，不是为了内容：超出由 {@code ELLIPSIS} 在渲染时截断，
     * tooltip 与点击复制走的都是完整原文 —— 截半条 SQL 复制出去执行是正确性事故。
     */
    private static final double SQL_MAX_WIDTH = 240.0;
    /** 相对时间的自重算周期（秒）——「刚刚」挂着一个小时不更新就是在骗人。 */
    private static final double REFRESH_TICK_SECONDS = 30.0;

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
    /** ⭐ UX-5-D：左段的「当前语句」—— 显示压平后的一行，复制与 tooltip 用原文。 */
    private final Label sql = segment("", "adb-status__sql");
    /** 语句段的分隔符 —— 与 {@link #sql} 同进退（不留孤零零的「·」）。 */
    private final Region sqlSeparator = separator();
    private final Tooltip sqlTooltip = new Tooltip();
    /** 当前语句原文；空串 = 这个页面没有语句。 */
    private String rawSql = "";
    /** ⭐ UX-5-D：右段的「最后刷新」相对时间。 */
    private final Label refreshed = segment("", "adb-status__refreshed");
    private final Region refreshedSeparator = separator();
    /** 最后一次取数的时刻（epoch 毫秒）；≤0 = 还没有取过数。 */
    private long refreshedAt = -1L;
    /** 「现在几点」——门禁注入固定时钟，否则断言会随用例运行时长漂移。 */
    private LongSupplier clock = System::currentTimeMillis;
    /** 相对时间的自重算计时器；没有时刻时必须是停的（别为空段白跑）。 */
    private Timeline relativeTicker;

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
        // ⭐ UX-5-D：语句段与刷新段同理 —— 开场两页都没有内容，先整段隐藏。
        hide(sql, sqlSeparator);
        hide(refreshed, refreshedSeparator);
        sql.setMaxWidth(SQL_MAX_WIDTH);
        sql.setTextOverrun(OverrunStyle.ELLIPSIS);
        sql.setCursor(Cursor.HAND);          // 「点得动」的暗示，与 tooltip 一起给
        Tooltip.install(sql, sqlTooltip);
        sql.setOnMouseClicked(this::copySql);

        HBox left = new HBox(6, dialect, separator(), connection, separator(), range,
                sqlSeparator, sql, separator(), message, dirty);
        left.setAlignment(Pos.CENTER_LEFT);
        left.getStyleClass().add("adb-statusbar__left");

        HBox center = new HBox(6, selection, durationSeparator, duration);
        center.setAlignment(Pos.CENTER);
        center.getStyleClass().add("adb-statusbar__center");

        HBox right = new HBox(6, transaction, separator(), readonly, separator(),
                refreshedSeparator, refreshed, separator(), server, separator(), version);
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
     * ⭐ UX-5-D：当前正在看的语句（数据页的分页 SQL / 查询页刚执行的那段）。
     *
     * <p>状态位与 {@link #setDuration} 同一套纪律：
     * <b>没有语句就整段隐藏</b>（连同分隔符），不留「—」这种看着像坏了的占位。
     *
     * @param statement null / 空白表示「这个页面没有语句」（表清单、对象首页）
     */
    public void setSql(String statement) {
        boolean present = statement != null && !statement.isBlank();
        rawSql = present ? statement : "";
        show(sql, sqlSeparator, present);
        if (!present) {
            return;
        }
        // 显示的是压平后的一行，tooltip 与复制用的是原文。
        sql.setText(oneLine(statement));
        sqlTooltip.setText(statement.strip() + "\n\n点击复制完整语句");
    }

    /**
     * ⭐ UX-5-D：最后一次取数的时刻（epoch 毫秒），显示为相对时间。
     *
     * <p>耗时说「这次跑了多久」，回答不了「这屏数据多旧」——
     * 页面开着不动半小时，界面上必须看得出来它是旧的。
     *
     * @param epochMillis ≤0 表示「还没有取过数」→ 整段隐藏并<b>停掉</b>自重算计时器
     */
    public void setRefreshedAt(long epochMillis) {
        refreshedAt = epochMillis;
        boolean present = epochMillis > 0;
        show(refreshed, refreshedSeparator, present);
        if (present) {
            paintRelativeTime();
            startTicker();
        } else {
            stopTicker();
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

    /** 供门禁断言：当前语句的<b>原文</b>（点击复制的内容，不是界面那行截断文本）。 */
    public String sqlText() {
        return rawSql;
    }

    /** 供门禁断言：悬浮语句段时看到的完整内容。 */
    public String sqlTooltipText() {
        return sqlTooltip.getText();
    }

    /** 供门禁断言：当前显示的「最后刷新」文本。 */
    public String refreshedText() {
        return refreshed.getText();
    }

    /** 供门禁注入固定时钟：相对时间必须能对着一个已知的「现在」断言。 */
    void setClockForTest(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        paintRelativeTime();
    }

    /** 状态位与分隔符同进退：只藏 Label 会在两段之间留下一个孤零零的「·」。 */
    private static void show(Label label, Region separator, boolean present) {
        label.setVisible(present);
        label.setManaged(present);
        separator.setVisible(present);
        separator.setManaged(present);
    }

    private static void hide(Label label, Region separator) {
        show(label, separator, false);
    }

    /** 压成一行：状态栏只有 24px 高，换行会把整条撑破。 */
    private static String oneLine(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    /** 点语句段 = 复制原文。顺手在消息位说一声，不然用户不知道剪贴板里已经有了什么。 */
    private void copySql(MouseEvent event) {
        if (rawSql.isEmpty()) {
            return;
        }
        event.consume();
        ClipboardContent content = new ClipboardContent();
        content.putString(rawSql);
        Clipboard.getSystemClipboard().setContent(content);
        message.setText("已复制当前语句");
    }

    private void paintRelativeTime() {
        if (refreshedAt > 0) {
            refreshed.setText("刷新于 " + relativeTime(refreshedAt, clock.getAsLong()));
        }
    }

    /** 相对时间：<1 分钟「刚刚」，往后依次是分钟 / 小时 / 天。 */
    static String relativeTime(long then, long now) {
        long seconds = Math.max(0, (now - then) / 1000);
        if (seconds < 60) {
            return "刚刚";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        return hours / 24 + " 天前";
    }

    private void startTicker() {
        if (relativeTicker == null) {
            relativeTicker = new Timeline(new KeyFrame(
                    Duration.seconds(REFRESH_TICK_SECONDS), event -> paintRelativeTime()));
            relativeTicker.setCycleCount(Timeline.INDEFINITE);
        }
        relativeTicker.play();
    }

    private void stopTicker() {
        if (relativeTicker != null) {
            relativeTicker.stop();
        }
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
