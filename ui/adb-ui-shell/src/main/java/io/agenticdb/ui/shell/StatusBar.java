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
 * <p>⭐ U8（2026-10-26）：从 11 个分段压到 **4 项 + 2 个状态**。
 * Navicat 的底栏一眼扫过去只有「连着什么 · 在看哪几行 · 跑多久 · 什么状态」，
 * 我们以前把每一件事都单开一段，还常驻显示着没人会读的驱动版本与应用版本号
 * （那两串属于「关于」弹窗）。合并口径：
 * <ul>
 *   <li>方言 + 连接名 → 一颗<b>连接灯</b>（状态点 + {@code SQLite · demo}），
 *       服务器版本与当前用户进它的 tooltip；</li>
 *   <li>事务状态 + 只读/可写 → 一段「自动提交 · 可写」；</li>
 *   <li>「刷新于 5 分钟前」→ 行范围段的 tooltip（数据新旧是<b>点开看</b>的信息，
 *       不是每 30 秒都值得占一格的东西）；</li>
 *   <li>未选择 / 没有耗时 / 没有待提交 → <b>整段不占位</b>，不显示占位符。</li>
 * </ul>
 *
 * <p>⚠️ 4.5-11：{@code setDialect} / {@code setSelection} / {@code setTransaction}
 * 接的是真实数据（方言、选中行数、会话事务状态），不是写死的「DEV / 未选择 / 自动提交」。
 *
 * <p>⭐ UX-5-D：左段保留「<b>当前语句</b>」—— 截断只发生在渲染层，
 * tooltip 与点击复制走的都是原文（截半条 SQL 复制出去执行是正确性事故）。
 */
public final class StatusBar extends HBox {

    /**
     * ⭐ UX-5-D：语句段的最大宽度。
     *
     * <p>封顶是为了<b>界面</b>，不是为了内容：超出由 {@code ELLIPSIS} 在渲染时截断，
     * tooltip 与点击复制走的都是完整原文。
     */
    private static final double SQL_MAX_WIDTH = 240.0;
    /** 相对时间的自重算周期（秒）——「刚刚」挂着一个小时不更新就是在骗人。 */
    private static final double REFRESH_TICK_SECONDS = 30.0;
    /** 连接灯的「已连上」修饰类 —— 灯色跟着真实连接状态走。 */
    private static final String LIVE = "adb-status__source--live";

    /** U8：方言 + 连接名合成的一颗连接灯（状态点 + 「SQLite · demo」）。 */
    private final Label source = segment("—", "adb-status__source");
    /** 方言与连接名的当前值 —— 合成显示，各自仍可被门禁读回。 */
    private String dialectName = "—";
    private String connectionName = "未连接";
    /** 服务器信息（版本 · 用户）：U8 后退到连接灯的 tooltip。 */
    private String serverInfo = "";
    private final Tooltip sourceTooltip = new Tooltip();

    private final Label range = segment("0 行", "adb-status__range");
    /** U8：行范围也讲「没数据不占位」—— 对象首页/表清单上那根悬空的「—」就是它。 */
    private final Region rangeSeparator = separator();
    private final Tooltip rangeTooltip = new Tooltip();
    /**
     * ⭐ U8：消息位只在**真有话说**时占位 —— 它是回执通道（复制成功、保存失败、
     * 结果截断），不是常驻播报。以前开场白「就绪」和每次加载后的「已加载 users · 200 行」
     * 都常驻一格，后者还和左边的行范围段（1–200 / 200）重复同一件事。
     */
    private final Label message = segment("", "adb-status__message");
    /** 消息位的分隔符 —— 与 {@link #message} 同进退（不留孤零零的「·」）。 */
    private final Region messageSeparator = separator();
    private final Label selection = segment("", "adb-status__selection");
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
    /** U8：事务状态 + 读写权限合成的一段「自动提交 · 可写」。 */
    private final Label session = segment("自动提交 · 只读", "adb-status__session");
    private String transactionState = "自动提交";
    private boolean readonly = true;
    private final Label dirty = segment("", "adb-status__dirty");
    /** ⭐ UX-5-D：左段的「当前语句」—— 显示压平后的一行，复制与 tooltip 用原文。 */
    private final Label sql = segment("", "adb-status__sql");
    /** 语句段的分隔符 —— 与 {@link #sql} 同进退（不留孤零零的「·」）。 */
    private final Region sqlSeparator = separator();
    private final Tooltip sqlTooltip = new Tooltip();
    /** 当前语句原文；空串 = 这个页面没有语句。 */
    private String rawSql = "";
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
        // ⭐ UX-5-D：语句段同理 —— 开场两页都没有内容，先整段隐藏。
        hide(sql, sqlSeparator);
        // U8：消息位开场没话说 —— 「就绪」不是信息，占位才是。
        hide(message, messageSeparator);
        // U8：没有选择就不占位，「未选择」这三个字对谁都没有信息量。
        selection.setVisible(false);
        selection.setManaged(false);
        // U8：连接灯的状态点是**图标**，不是文本符号（与行号列、布尔列同一口径）。
        source.setGraphic(Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT));
        source.getGraphic().getStyleClass().add("adb-status__light");
        // Label 用 setTooltip 而不是 Tooltip.install：属性式 tooltip 才能被门禁直接读回。
        source.setTooltip(sourceTooltip);
        paintSource();
        // 行范围的 tooltip 同时挂「最后刷新」，所以它常驻（没取过数时只说范围）。
        range.setTooltip(rangeTooltip);
        sql.setMaxWidth(SQL_MAX_WIDTH);
        sql.setTextOverrun(OverrunStyle.ELLIPSIS);
        sql.setCursor(Cursor.HAND);          // 「点得动」的暗示，与 tooltip 一起给
        sql.setTooltip(sqlTooltip);
        sql.setOnMouseClicked(this::copySql);

        HBox left = new HBox(6, source, rangeSeparator, range, sqlSeparator, sql,
                messageSeparator, message, dirty);
        left.setAlignment(Pos.CENTER_LEFT);
        left.getStyleClass().add("adb-statusbar__left");

        HBox center = new HBox(6, selection, durationSeparator, duration);
        center.setAlignment(Pos.CENTER);
        center.getStyleClass().add("adb-statusbar__center");

        HBox right = new HBox(6, session);
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
        dialectName = value == null || value.isBlank() ? "—" : value;
        paintSource();
    }

    public void setConnection(String value) {
        connectionName = value == null || value.isBlank() ? "未连接" : value;
        paintSource();
    }

    /** 方言与连接名合成一颗灯；tooltip 补服务器版本与当前用户（U8）。 */
    private void paintSource() {
        source.setText(dialectName + " · " + connectionName);
        // 灯要真的表达「连上没有」：常亮的灰点只是装饰，连上时它必须是绿的。
        // 用修饰类而不是换掉 adb-status__source —— 胶囊的底、内边距都挂在源类上。
        if (isConnected()) {
            if (!source.getStyleClass().contains(LIVE)) {
                source.getStyleClass().add(LIVE);
            }
        } else {
            source.getStyleClass().remove(LIVE);
        }
        sourceTooltip.setText(serverInfo.isEmpty()
                ? "当前连接：" + connectionName
                : "当前连接：" + connectionName + "\n" + serverInfo);
    }

    /** U8：AppShell 在未连接时把连接名写回「未连接」，这是唯一的判据。 */
    private boolean isConnected() {
        return !"未连接".equals(connectionName);
    }

    /** 供门禁断言：悬浮连接灯时看到的服务器信息。 */
    public String sourceTooltipText() {
        return sourceTooltip.getText();
    }

    /** 供门禁断言：连接灯是否处于「已连上」态（决定灯色）。 */
    public boolean isSourceLive() {
        return source.getStyleClass().contains(LIVE);
    }

    /**
     * ⭐ U8：行范围段。空白或「—」= 这一页根本没有「第几行到第几行」可说
     * （对象首页、表清单、未连接）—— 整段连同分隔符收掉，不留悬空占位。
     */
    public void setRange(String value) {
        boolean present = value != null && !value.isBlank() && !value.strip().equals("—");
        show(range, rangeSeparator, present);
        if (present) {
            range.setText(value);
        }
        paintRangeTooltip();
    }

    /** 供门禁断言：行范围段当前是否占位。 */
    public boolean isRangeVisible() {
        return range.isVisible() && range.isManaged();
    }

    public void setMessage(String value) {
        boolean present = value != null && !value.isBlank();
        show(message, messageSeparator, present);
        if (present) {
            message.setText(value);
        }
    }

    /** 供门禁断言：消息位当前是否占位（U8：没话说就不许占一格）。 */
    public boolean isMessageVisible() {
        return message.isVisible() && message.isManaged();
    }

    public void setSelection(int count) {
        setSelectionText(count <= 0 ? null : "已选 " + count + " 行");
    }

    /**
     * ⭐ N5：由页面直接给出选中项文案（表清单是「对象」不是「行」）。
     *
     * <p>数据页说「已选 7 行」；表清单说「已选 users · 200 行」/「已选 3 个项目」。
     * 两处含义不同，不能都塞进「行」这一个量词里。
     *
     * <p>U8：没有选中时整段不占位（不再常驻「未选择」）。
     */
    public void setSelectionText(String text) {
        boolean present = text != null && !text.isBlank();
        selection.setText(present ? text : "");
        selection.setVisible(present);
        selection.setManaged(present);
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
     * ⭐ UX-5-D：最后一次取数的时刻（epoch 毫秒）。
     *
     * <p>耗时说「这次跑了多久」，回答不了「这屏数据多旧」—— 页面开着不动半小时，
     * 界面上必须看得出来它是旧的。
     *
     * <p>U8：相对时间从独立分段挪进行范围的 <b>tooltip</b> —— 一格常驻位置换不来
     * 「一眼就看到」，但悬停时它和行数本来就该一起读。
     *
     * @param epochMillis ≤0 表示「还没有取过数」→ 停掉自重算计时器并从 tooltip 里去掉
     */
    public void setRefreshedAt(long epochMillis) {
        refreshedAt = epochMillis;
        paintRangeTooltip();
        if (epochMillis > 0) {
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
        transactionState = inTransaction ? "事务中" : "自动提交";
        paintSession();
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
     * <p>「连的是哪个版本的什么库、以什么身份连的」是排查行为差异时最先要看的信息；
     * U8 后它不再占一格，挂在连接灯的 tooltip 上。
     */
    public void setServerInfo(String version, String user) {
        String text = version == null ? "" : version.trim();
        if (user != null && !user.isBlank()) {
            text = text.isEmpty() ? user.trim() : text + " · " + user.trim();
        }
        serverInfo = text;
        paintSource();
    }

    public void setReadonly(boolean value) {
        readonly = value;
        paintSession();
    }

    /** U8：事务状态与读写权限合成一段，读起来是一句人话而不是两个词。 */
    private void paintSession() {
        session.setText(transactionState + " · " + (readonly ? "只读" : "可写"));
    }

    public String message() {
        return message.getText();
    }

    /** 供门禁断言：当前显示的方言。 */
    public String dialectText() {
        return dialectName;
    }

    /** 供门禁断言：连接灯实际显示的文字（方言 · 连接名）。 */
    public String sourceText() {
        return source.getText();
    }

    /** 供门禁断言：当前显示的选中行数。 */
    public String selectionText() {
        return selection.isVisible() ? selection.getText() : "未选择";
    }

    /** 供门禁断言：当前显示的事务状态。 */
    public String transactionText() {
        return transactionState;
    }

    /** 供门禁断言：当前显示的读写状态。 */
    public String readonlyText() {
        return readonly ? "只读" : "可写";
    }

    /** 供门禁断言：当前语句的<b>原文</b>（点击复制的内容，不是界面那行截断文本）。 */
    public String sqlText() {
        return rawSql;
    }

    /** 供门禁断言：悬浮语句段时看到的完整内容。 */
    public String sqlTooltipText() {
        return sqlTooltip.getText();
    }

    /** 供门禁断言：行范围 tooltip 里的「最后刷新」文本（没取过数为空串）。 */
    public String refreshedText() {
        return refreshedAt > 0 ? "刷新于 " + relativeTime(refreshedAt, clock.getAsLong()) : "";
    }

    /** 供门禁断言：行范围的 tooltip 全文（范围 + 最后刷新）。 */
    public String rangeTooltipText() {
        return rangeTooltip.getText();
    }

    /** 供门禁注入固定时钟：相对时间必须能对着一个已知的「现在」断言。 */
    void setClockForTest(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        paintRangeTooltip();
    }

    /** 行范围 tooltip：范围本身 + 最后刷新（U8 后相对时间住在这里）。 */
    private void paintRangeTooltip() {
        String scope = "当前范围：" + range.getText();
        String when = refreshedText();
        rangeTooltip.setText(when.isEmpty() ? scope : scope + "\n" + when);
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
                    Duration.seconds(REFRESH_TICK_SECONDS), event -> paintRangeTooltip()));
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
