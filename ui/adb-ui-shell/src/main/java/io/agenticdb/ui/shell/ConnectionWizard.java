package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.CredentialStore;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 新建/编辑连接向导（Navicat 口径）：<b>类型选择页 → 属性页</b>。
 *
 * <p>⭐ R33-G8/G20：以前是「类型下拉 → 主机端口 → 复核」三步，没有 SSL / SSH / HTTP，
 * 也没有 URI 入口。现在：
 * <ul>
 *   <li><b>第 1 步</b>：搜索 + 磁贴，只列<b>真实支持</b>的三种库型（SQLite / PostgreSQL / MySQL），
 *       外加「使用 URI 新建连接…」；</li>
 *   <li><b>第 2 步</b>：7 个页签 —— 常规 / 高级 / 数据库 / SSL / SSH / HTTP / 备注，底部
 *       「测试连接 / URI… / 上一步 / 确定」。</li>
 * </ul>
 *
 * <p>⛔ 按钮仍在<b>内容区</b>：{@code DialogPane.createButton} 会给按钮栏里每个按钮挂
 * {@code setResultAndClose}，点「下一步」会把整个向导关掉（4.5-12 的回归点）。
 *
 * <p>⛔ SSH / HTTP 两页<b>只说明不伪装</b>：本版没有隧道引擎，摆一组填不进驱动的输入框
 * 等于骗人。页签在（形态对齐 Navicat），内容写清楚为什么没有、替代做法是什么。
 */
public final class ConnectionWizard {

    public interface Tester {
        /** @return null 表示连接成功；非 null 为可读错误 */
        String test(ConnectionSpec spec);
    }

    /**
     * ⭐ D-3：档位（{@link Dialect.SslLevel}）只是界面语言，
     * <b>能表达哪几档、落成什么参数</b>全部问方言。
     *
     * <p>以前这里挂着 {@code postgres()} / {@code mysql()} 两张表，界面自己 switch 方言 ——
     * MariaDB 因此拿到了 PostgreSQL 的 {@code sslmode}（它的驱动认 {@code sslMode}，
     * 而且没有 prefer 这一档），填进去的参数根本不被识别。
     */
    static String labelOf(Dialect.SslLevel level) {
        return switch (level) {
            case OFF -> I18n.t("wizard.ssl.mode.off");
            case PREFER -> I18n.t("wizard.ssl.mode.prefer");
            case REQUIRE -> I18n.t("wizard.ssl.mode.require");
            case VERIFY_CA -> I18n.t("wizard.ssl.mode.verifyCa");
            case VERIFY_IDENTITY -> I18n.t("wizard.ssl.mode.verifyIdentity");
        };
    }

    /** URI 解析结果（口令只回填到输入框，不写进 options）。 */
    record UriTarget(DialectKind kind, String host, int port, String database,
                     String username, String password, Map<String, String> options) {

        UriTarget {
            options = options == null ? Map.of() : Map.copyOf(options);
        }

        UriTarget(DialectKind kind, String host, int port, String database,
                  String username, String password) {
            this(kind, host, port, database, username, password, Map.of());
        }
    }

    static final String OPT_QUERY_TIMEOUT = "queryTimeout";
    static final String OPT_CHARSET = "characterEncoding";
    static final String OPT_REMARKS = "remarks";

    /**
     * 所有方言的 TLS 参数名（档位参数 + 根证书参数）—— 由方言层自报，不在界面硬编码。
     *
     * <p>这些键由 TLS 页渲染，必须从「附加参数」框里<b>隐藏</b>：否则同一个键在两个地方
     * 各写一遍，保存时后写的赢，用户在附加参数里看到的档位就不是界面显示的那个。
     */
    private static final List<String> SSL_KEYS = collectSslKeys();

    private static List<String> collectSslKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (DialectKind kind : DialectKind.values()) {
            Dialect dialect = kind.dialect();
            for (Dialect.SslLevel level : Dialect.SslLevel.values()) {
                keys.addAll(dialect.sslOptions(level).keySet());
            }
            String root = dialect.sslRootCertOption();
            if (root != null) {
                keys.add(root);
            }
        }
        return List.copyOf(keys);
    }

    /** 由本向导自己渲染的键：不从「附加参数」框里回显，也不许用户在框里覆盖。 */
    private static final List<String> MANAGED_KEYS = managedKeys();

    private static List<String> managedKeys() {
        List<String> all = new java.util.ArrayList<>(
                List.of(OPT_QUERY_TIMEOUT, OPT_CHARSET, OPT_REMARKS));
        all.addAll(SSL_KEYS);
        return List.copyOf(all);
    }

    private final Dialog<ConnectionProfile> dialog = new Dialog<>();
    private final ToggleGroup typeGroup = new ToggleGroup();
    /**
     * 类型磁贴用 FlowPane 摆成**一行卡片**（Navicat 的连接类型页是网格，不是竖排大条）。
     * 以前是 VBox + VGrow，三个磁贴被拉成每个 ~220px 高的空白块（p5-connect-type 实拍）。
     */
    private final FlowPane tiles = new FlowPane(8, 8);
    private final Button cancelButton = new Button(I18n.t("common.cancel"));
    private final TextField search = new TextField();
    private final HBox uriRow = new HBox(8);
    private final TextField uriField = new TextField();
    private DialectKind type = DialectKind.SQLITE;

    private final TextField name = new TextField();
    private final TextField file = new TextField();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField database = new TextField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox savePassword = new CheckBox(I18n.t("wizard.savePassword"));

    private final TextField queryTimeout = new TextField();
    private final TextField charset = new TextField();
    private final TextArea extra = new TextArea();
    private final ComboBox<Dialect.SslLevel> sslMode = new ComboBox<>();
    /** TLS 页的容器：档位控件与如实说明是两套内容，随选中的数据库换。 */
    private final StackPane sslHost = new StackPane();
    private final TextField sslRoot = new TextField();
    private final Label sslHint = new Label();
    private final TextArea remarks = new TextArea();
    private TabPane tabs;

    private final Label testResult = new Label(I18n.t("wizard.test.notYet"));
    private final Label saveError = new Label();

    private final ConnectionProfile initial;
    private final CredentialStore credentials;
    private final Tester tester;
    private int step = 1;
    private Consumer<Optional<ConnectionProfile>> onFinished = result -> { };

    public ConnectionWizard(Window owner, ConnectionProfile initial,
                            CredentialStore credentials, Tester tester) {
        this.initial = initial;
        this.credentials = credentials == null ? CredentialStore.inMemory() : credentials;
        this.tester = tester == null ? spec -> I18n.t("wizard.test.noTester") : tester;
        dialog.initOwner(owner);
        dialog.setTitle(I18n.t("wizard.title"));
        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);
        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        build();
        loadInitial();
        updateStep();
    }

    private void build() {
        search.setId("wizard-type-search");
        search.setPromptText(I18n.t("wizard.search.prompt"));
        search.textProperty().addListener((observable, old, text) -> filterTiles(text));

        tiles.setId("wizard-type-tiles");
        for (DialectKind kind : DialectKind.values()) {
            tiles.getChildren().add(tile(kind));
        }

        uriField.setId("wizard-uri");
        uriField.setPromptText(I18n.t("wizard.uri.example"));
        HBox.setHgrow(uriField, Priority.ALWAYS);
        Button apply = new Button(I18n.t("wizard.uri.apply"));
        apply.setId("wizard-uri-apply");
        apply.getStyleClass().addAll("adb-button", "adb-button--primary");
        apply.setOnAction(event -> applyUri());

        uriRow.getChildren().setAll(uriField, apply);
        uriRow.setId("wizard-uri-row");
        uriRow.setVisible(false);
        uriRow.setManaged(false);

        name.setId("wizard-name");
        file.setId("wizard-file");
        host.setId("wizard-host");
        port.setId("wizard-port");
        database.setId("wizard-database");
        username.setId("wizard-username");
        password.setId("wizard-password");
        savePassword.setId("wizard-save-password");
        savePassword.setSelected(true);

        queryTimeout.setId("wizard-query-timeout");
        queryTimeout.setPromptText(I18n.t("wizard.timeout.prompt"));
        charset.setId("wizard-charset");
        charset.setPromptText(I18n.t("wizard.charset.prompt"));
        extra.setId("wizard-extra");
        extra.setPromptText(I18n.t("wizard.extra.prompt"));
        extra.setPrefRowCount(5);

        sslMode.setId("wizard-ssl-mode");
        // 档位名是界面文案，枚举在方言层 —— 用 converter 把两者接上。
        sslMode.setConverter(new StringConverter<>() {
            @Override
            public String toString(Dialect.SslLevel level) {
                return level == null ? "" : labelOf(level);
            }

            @Override
            public Dialect.SslLevel fromString(String text) {
                return null;
            }
        });
        sslMode.setMaxWidth(Double.MAX_VALUE);
        sslMode.valueProperty().addListener((observable, old, mode) -> updateSslHint());
        sslRoot.setId("wizard-ssl-root");
        sslRoot.setPromptText(I18n.t("wizard.sslRoot.prompt"));
        sslHint.setId("wizard-ssl-hint");
        sslHint.getStyleClass().add("adb-muted");
        sslHint.setWrapText(true);

        remarks.setId("wizard-remarks");
        remarks.setPromptText(I18n.t("wizard.remarks.prompt"));
        remarks.setPrefRowCount(5);

        testResult.setId("wizard-test-result");
        testResult.getStyleClass().add("adb-muted");
        saveError.setId("wizard-save-error");
        saveError.getStyleClass().add("adb-error");
        saveError.setWrapText(true);
        saveError.setVisible(false);
        saveError.setManaged(false);

        // ⭐ R33：dialog 的按钮栏**留空** —— 导航与取消都在内容区同一行。
        // 以前取消走按钮栏，和内容区那行叠成两层（p5-connect-general 实拍：
        // 「上一步 / URI… / 测试连接 / 确定」一行，下面再吊一颗「取消」）。
        // 取消按钮自己实现（Esc 也走它），所以结果转换器原样返回 dialog.getResult()。
        cancelButton.setId("wizard-cancel");
        cancelButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        cancelButton.setOnAction(event -> cancel());
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.setResultConverter(button -> dialog.getResult());
        dialog.setOnShown(event -> {
            updateButtons();
            // 页签是**显示前**就被程序选中的（截图摆态走这条路）：TabPane 的皮肤
            // 在隐藏期间量过一次内容，上屏后要再走一遍 CSS + 布局才拿到 28px 的行高。
            // 少这一遍，SSL 页的下拉会以 22px 的高度定格，值被切掉半截（p5-connect-ssl 实拍）。
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            javafx.application.Platform.runLater(() -> {
                dialog.getDialogPane().applyCss();
                dialog.getDialogPane().layout();
            });
            Scene scene = dialog.getDialogPane().getScene();
            if (scene != null) {
                // 按钮栏空了，Esc 要自己接住（它原来是 CANCEL_CLOSE 的福利）。
                scene.addEventHandler(KeyEvent.KEY_PRESSED, key -> {
                    if (key.getCode() == KeyCode.ESCAPE) {
                        cancel();
                        key.consume();
                    }
                });
            }
        });
    }

    /** 取消：不算结果，直接关。 */
    void cancel() {
        dialog.setResult(null);
        dialog.hide();
    }

    // ─────────────────── 第 1 步：类型选择页 ───────────────────

    private ToggleButton tile(DialectKind kind) {
        Label icon = new Label();
        icon.setGraphic(Icons.of(Icons.Icon.DATABASE, Icons.SIZE_PRIMARY));
        icon.getStyleClass().add("adb-type-tile__icon");

        Label title = new Label(kind.displayName());
        title.getStyleClass().add("adb-type-tile__name");
        Label hint = new Label(hintOf(kind));
        hint.getStyleClass().addAll("adb-muted", "adb-type-tile__hint");
        hint.setWrapText(true);

        VBox box = new VBox(4, icon, title, hint);
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(true);
        hint.setMaxWidth(132);
        hint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        ToggleButton button = new ToggleButton();
        button.setId("wizard-tile-" + kind.name());
        button.setUserData(kind);
        button.setGraphic(box);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("adb-type-tile");
        button.setToggleGroup(typeGroup);
        // 卡片固定尺寸：磁贴是「选一个」，不是「填满整页」。
        button.setPrefSize(156, 100);
        button.setMinSize(156, 100);
        button.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        button.setAlignment(Pos.CENTER);
        button.setOnAction(event -> {
            // ⛔ 再点一次已选磁贴不允许变成「全不选」。
            if (!button.isSelected()) {
                button.setSelected(true);
            }
            selectType(kind);
        });
        return button;
    }

    private static String hintOf(DialectKind kind) {
        return switch (kind) {
            case SQLITE -> I18n.t("wizard.tile.sqlite");
            case POSTGRESQL -> I18n.t("wizard.tile.postgresql");
            case MYSQL -> I18n.t("wizard.tile.mysql");
            case MARIADB -> I18n.t("wizard.tile.mariadb");
            case SQLSERVER -> I18n.t("wizard.tile.sqlserver");
            case ORACLE -> I18n.t("wizard.tile.oracle");
        };
    }

    private void filterTiles(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (Node node : tiles.getChildren()) {
            ToggleButton tile = (ToggleButton) node;
            DialectKind kind = (DialectKind) tile.getUserData();
            boolean matches = needle.isEmpty()
                    || kind.displayName().toLowerCase(Locale.ROOT).contains(needle)
                    || kind.id().contains(needle);
            tile.setVisible(matches);
            tile.setManaged(matches);
        }
    }

    void selectType(DialectKind kind) {
        DialectKind previous = type;
        type = kind;
        typeGroup.getToggles().forEach(toggle -> toggle.setSelected(toggle.getUserData() == kind));
        String current = port.getText();
        if (current == null || current.isBlank()
                || current.equals(String.valueOf(ConnectionProfile.defaultPort(previous)))) {
            port.setText(kind.dialect().usesFilePath() ? ""
                    : String.valueOf(ConnectionProfile.defaultPort(kind)));
        }
        applySslLevels(kind);
        refreshSslPane();
    }

    /**
     * 换类型时刷新 TLS 页：下拉只列<b>这一家驱动真能表达</b>的档位。
     *
     * <p>原选中值在新一家里不存在就退回「不加密」—— 留着不存在的档位，
     * 保存的就是一个没人认的参数。
     */
    private void applySslLevels(DialectKind kind) {
        if (sslMode == null || sslRoot == null) {
            return;
        }
        Dialect dialect = kind.dialect();
        List<Dialect.SslLevel> levels = dialect.sslLevels();
        Dialect.SslLevel previous = sslMode.getValue();
        sslMode.getItems().setAll(levels);
        sslMode.getSelectionModel().select(
                previous != null && levels.contains(previous) ? previous : Dialect.SslLevel.OFF);
        sslRoot.setDisable(dialect.sslRootCertOption() == null);
    }

    void toggleUriRow(boolean visible) {
        uriRow.setVisible(visible);
        uriRow.setManaged(visible);
        if (visible) {
            uriField.requestFocus();
        }
    }

    /** 解析 URI 并填进属性页。返回是否成功（失败原因写在错误行）。 */
    boolean applyUri() {
        UriTarget target;
        try {
            target = parseUri(uriField.getText());
        } catch (RuntimeException e) {
            showStepError(e.getMessage() == null ? I18n.t("wizard.uri.unparseable") : e.getMessage());
            return false;
        }
        clearStepError();
        selectType(target.kind());
        if (name.getText() == null || name.getText().isBlank()) {
            String suffix = target.database().isBlank() ? "" : " · " + target.database();
            name.setText(target.kind().displayName() + suffix);
        }
        file.setText(target.kind().dialect().usesFilePath() ? target.database() : "");
        host.setText(target.host());
        if (target.port() > 0) {
            port.setText(String.valueOf(target.port()));
        }
        database.setText(target.database());
        username.setText(target.username() == null ? "" : target.username());
        password.setText(target.password() == null ? "" : target.password());
        if (!target.options().isEmpty()) {
            applyOptions(target.options());
        }
        toggleUriRow(false);
        step = 2;
        updateStep();
        return true;
    }

    /**
     * 解析连接 URI。
     *
     * <p><b>支持哪家由方言自己报 scheme</b>（{@link Dialect#uriScheme()} 与
     * {@link Dialect#uriAliases()}）—— 加一种数据库不必回来改这段判断。
     * 参数串两种写法都认：{@code ?k=v&k2=v2}，以及 SQL Server 的 {@code ;k=v;}。
     * 口令只回填口令框；TLS 页自己管的键回填档位下拉，其余原样进「附加参数」。
     */
    static UriTarget parseUri(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException(I18n.t("wizard.uri.required"));
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:")) {
            raw = raw.substring("jdbc:".length());
            normalized = raw.toLowerCase(Locale.ROOT);
        }
        // Oracle 的 EZConnect 写法是 oracle:thin:@//host:1521/服务名，换成本项目的通用形态。
        if (normalized.startsWith("oracle:thin:@//")) {
            raw = "oracle://" + raw.substring("oracle:thin:@//".length());
            normalized = raw.toLowerCase(Locale.ROOT);
        }
        String sqliteScheme = DialectKind.SQLITE.dialect().uriScheme();
        if (normalized.startsWith(sqliteScheme + ":")) {
            String path = raw.substring(sqliteScheme.length() + 1);
            if (path.startsWith("//")) {
                path = path.substring(2);
            }
            if (path.isBlank()) {
                throw new IllegalArgumentException(I18n.t("wizard.uri.missingPath"));
            }
            return new UriTarget(DialectKind.SQLITE, "", 0, path, "", "");
        }

        DialectKind kind = kindOfScheme(normalized);
        if (kind == null) {
            throw new IllegalArgumentException(I18n.t("wizard.uri.unsupported"));
        }
        Dialect dialect = kind.dialect();

        Map<String, String> params = new LinkedHashMap<>();
        String address = raw;
        if (dialect.usesSemicolonParameters()) {
            // 分号族：第一段是地址，后面每段都是 k=v（大小写按驱动原样保留）。
            String[] segments = raw.split(";");
            address = segments[0];
            for (int i = 1; i < segments.length; i++) {
                addPair(params, segments[i]);
            }
        } else {
            int query = raw.indexOf('?');
            if (query >= 0) {
                for (String pair : raw.substring(query + 1).split("&")) {
                    addPair(params, pair);
                }
                address = raw.substring(0, query);
            }
        }

        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(I18n.t("wizard.uri.malformed", e.getMessage()));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(I18n.t("wizard.uri.missingHost"));
        }
        String user = "";
        String secret = "";
        String info = uri.getUserInfo();
        if (info != null && !info.isBlank()) {
            int colon = info.indexOf(':');
            user = decode(colon < 0 ? info : info.substring(0, colon));
            secret = colon < 0 ? "" : decode(info.substring(colon + 1));
        }
        String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        // SQL Server 把库名放在参数串上（databaseName=…），路径是空的。
        String dbKey = dialect.databaseOptionKey();
        if (dbKey != null && params.get(dbKey) != null && !params.get(dbKey).isBlank()) {
            db = params.get(dbKey);
        }
        int portValue = uri.getPort() > 0 ? uri.getPort()
                : ConnectionProfile.defaultPort(kind);
        return new UriTarget(kind, uri.getHost(), portValue, decode(db), user, secret, params);
    }

    /** 按方言自报的 scheme / 别名认类型（SQLite 走文件路径，不在这条路上）。 */
    private static DialectKind kindOfScheme(String normalized) {
        for (DialectKind kind : DialectKind.values()) {
            if (kind == DialectKind.SQLITE) {
                continue;
            }
            for (String alias : kind.dialect().uriAliases()) {
                if (normalized.startsWith(alias.toLowerCase(Locale.ROOT) + "://")) {
                    return kind;
                }
            }
        }
        return null;
    }

    private static void addPair(Map<String, String> into, String pair) {
        int equals = pair.indexOf('=');
        if (equals <= 0) {
            return;
        }
        into.put(pair.substring(0, equals).trim(), decode(pair.substring(equals + 1).trim()));
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return value;
        }
    }

    // ─────────────────── 第 2 步：属性页（7 个页签） ───────────────────

    private Node typeStep() {
        Label hint = new Label(I18n.t("wizard.type.hint"));
        hint.getStyleClass().add("adb-muted");

        Button uriToggle = new Button(I18n.t("wizard.type.uri"));
        uriToggle.setId("wizard-type-uri");
        uriToggle.getStyleClass().addAll("adb-button", "adb-button--ghost");
        uriToggle.setOnAction(event -> toggleUriRow(!uriRow.isVisible()));

        // 磁贴贴顶、留白在下方 —— 竖排大条会让三个磁贴各占 ~220px（实拍过）。
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        VBox box = new VBox(10, search, tiles, hint, uriRow, gap);
        VBox.setVgrow(box, Priority.ALWAYS);

        // Navicat 口径（§6.7）：URI 入口在左，导航与取消在右，**同一行**；
        // 取消在主动作左边、主动作贴右缘 —— 和导入 / 导出向导同一档手感。
        Region navGap = new Region();
        HBox.setHgrow(navGap, Priority.ALWAYS);
        HBox nav = new HBox(8, uriToggle, navGap,
                cancelButton, navButton("wizard-next", I18n.t("wizard.next"), this::goNext));
        nav.setAlignment(Pos.CENTER_LEFT);
        VBox outer = new VBox(8, box, saveError, nav);
        // 和其他四个向导同一档留白（16px）；属性页的 GridPane 自带 12px，不加类免双份。
        outer.getStyleClass().add("adb-wizard__content");
        return outer;
    }

    private Node propertiesStep() {
        TabPane tabs = new TabPane();
        this.tabs = tabs;
        tabs.setId("wizard-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("adb-form");
        tabs.getTabs().addAll(
                tab("wizard-tab-general", I18n.t("wizard.tab.general"), generalPane()),
                tab("wizard-tab-advanced", I18n.t("wizard.tab.advanced"), advancedPane()),
                tab("wizard-tab-databases", I18n.t("wizard.tab.databases"), databasesPane()),
                tab("wizard-tab-ssl", I18n.t("wizard.tab.ssl"), sslPane()),
                tab("wizard-tab-ssh", I18n.t("wizard.tab.ssh"), notice(
                        I18n.t("wizard.ssh.notice"),
                        I18n.t("wizard.ssh.workaround"))),
                tab("wizard-tab-http", I18n.t("wizard.tab.http"), notice(
                        I18n.t("wizard.http.notice"),
                        I18n.t("wizard.http.workaround"))),
                tab("wizard-tab-remarks", I18n.t("wizard.tab.remarks"), remarksPane()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Button testButton = new Button(I18n.t("wizard.test"));
        testButton.setId("wizard-test");
        testButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        testButton.setOnAction(event -> testConnection());

        Button uriButton = new Button(I18n.t("wizard.uriButton"));
        uriButton.setId("wizard-uri-open");
        uriButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        uriButton.setOnAction(event -> {
            step = 1;
            updateStep();
            toggleUriRow(true);
        });

        Button saveButton = new Button(I18n.t("common.ok"));
        saveButton.setId("wizard-save");
        saveButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        saveButton.setOnAction(event -> commit());

        HBox nav = new HBox(8, navButton("wizard-back", I18n.t("wizard.back"), this::goBack),
                uriButton, testButton, cancelButton, saveButton);
        nav.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(8, tabs, testResult, saveError, nav);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return box;
    }

    private static Tab tab(String id, String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setId(id);
        tab.setClosable(false);
        return tab;
    }

    private Node generalPane() {
        GridPane grid = grid();
        grid.addRow(0, new Label(I18n.t("wizard.name")), name);
        if (type.dialect().usesFilePath()) {
            Button browse = new Button(I18n.t("wizard.browse"));
            browse.setId("wizard-browse");
            browse.getStyleClass().addAll("adb-button", "adb-button--ghost");
            browse.setOnAction(event -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle(I18n.t("wizard.file.chooserTitle"));
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter(I18n.t("wizard.file.filter.sqlite"), "*.db", "*.sqlite"),
                        new FileChooser.ExtensionFilter(I18n.t("wizard.file.filter.all"), "*.*"));
                java.io.File selected = chooser.showSaveDialog(dialog.getOwner());
                if (selected != null) {
                    file.setText(selected.getAbsolutePath());
                }
            });
            HBox fileRow = new HBox(8, file, browse);
            HBox.setHgrow(file, Priority.ALWAYS);
            grid.addRow(1, new Label(I18n.t("wizard.file")), fileRow);
            grid.addRow(2, new Label(), new Label(I18n.t("wizard.general.sqliteNote")));
            return grid;
        }
        grid.addRow(1, new Label(I18n.t("wizard.host")), host);
        grid.addRow(2, new Label(I18n.t("wizard.port")), port);
        grid.addRow(3, new Label(I18n.t("wizard.database")), database);
        grid.addRow(4, new Label(I18n.t("wizard.username")), username);
        grid.addRow(5, new Label(I18n.t("wizard.password")), password);
        grid.addRow(6, new Label(), savePassword);
        return grid;
    }

    private Node advancedPane() {
        VBox box = new VBox(10,
                labeled(I18n.t("wizard.timeout"), queryTimeout),
                labeled(I18n.t("wizard.charset"), charset),
                labeled(I18n.t("wizard.extra"), extra),
                hint(I18n.t("wizard.advanced.hint")));
        return box;
    }

    private Node databasesPane() {
        Label summary = new Label(type.dialect().usesFilePath()
                ? I18n.t("wizard.databases.sqlite")
                : I18n.t("wizard.databases.server"));
        summary.setId("wizard-databases-summary");
        summary.setWrapText(true);
        summary.getStyleClass().add("adb-muted");
        return new VBox(10, summary, hint(I18n.t("wizard.databases.hint")));
    }

    private Node sslPane() {
        refreshSslPane();
        return sslHost;
    }

    /**
     * TLS 页的内容<b>随选中的数据库换</b>：能不能摆档位、根证书收不收，都是方言的事。
     *
     * <p>页签内容只在第一次构建，类型是之后才选的 —— 不做这一步刷新，
     * 「SQLite 没有 SSL」的说明会跟着 PostgreSQL 的连接一起留在页面上。
     */
    private void refreshSslPane() {
        if (sslHost == null) {
            return;
        }
        sslHost.getChildren().clear();
        Dialect dialect = type.dialect();
        sslHost.getChildren().add(dialect.supportsSslOptionsUi()
                ? sslControls() : sslNotice(dialect));
    }

    /** 这一家的 TLS 不是档位能表达的：给如实说明，不摆填了不生效的控件。 */
    private Node sslNotice(Dialect dialect) {
        String key = dialect.sslOptionsNoticeKey();
        // SQLite 的文案走界面自己的键（本地文件这句话没有方言专属信息量）。
        return key == null
                ? notice(I18n.t("wizard.ssl.sqlite"), I18n.t("wizard.ssl.sqliteNext"))
                : notice(I18n.t("wizard.ssl.unavailable"), I18n.t(key));
    }

    private Node sslControls() {
        VBox box = new VBox(10);
        GridPane grid = grid();
        grid.addRow(0, new Label(I18n.t("wizard.ssl.encryption")), sslMode);
        grid.addRow(1, new Label(I18n.t("wizard.ssl.rootCert")), sslRoot);
        box.getChildren().addAll(grid, sslHint);
        updateSslHint();
        return box;
    }

    private void updateSslHint() {
        if (sslRoot == null || sslHint == null) {
            return;
        }
        Dialect dialect = type.dialect();
        sslRoot.setDisable(dialect.sslRootCertOption() == null);
        Dialect.SslLevel level = sslMode.getValue();
        // 文案按方言选（界面说话，方言决定参数）：MariaDB 现在收 serverSslCert，
        // 不能再套 MySQL 那句「本版本不动根证书输入框」。
        if (dialect.kind() == DialectKind.MYSQL) {
            sslHint.setText(I18n.t("wizard.ssl.mysqlHint"));
        } else if (dialect.kind() == DialectKind.MARIADB) {
            sslHint.setText(I18n.t("wizard.ssl.mariadbHint"));
        } else if (level == Dialect.SslLevel.VERIFY_CA
                || level == Dialect.SslLevel.VERIFY_IDENTITY) {
            sslHint.setText(I18n.t("wizard.ssl.verifyHint"));
        } else {
            sslHint.setText(I18n.t("wizard.ssl.requireHint"));
        }
    }

    private Node remarksPane() {
        VBox box = new VBox(10, labeled(I18n.t("wizard.remarks.label"), remarks),
                hint(I18n.t("wizard.remarks.hint")));
        VBox.setVgrow(remarks, Priority.ALWAYS);
        return box;
    }

    private static Node notice(String headline, String detail) {
        Label title = new Label(headline);
        title.getStyleClass().add("adb-empty__title");
        title.setWrapText(true);
        Label body = new Label(detail);
        body.getStyleClass().add("adb-muted");
        body.setWrapText(true);
        VBox box = new VBox(8, title, body);
        box.setId("wizard-notice");
        box.setPadding(new Insets(24));
        return box;
    }

    // ─────────────────── 导航与操作 ───────────────────

    void goBack() {
        if (step == 2) {
            step = 1;
            clearStepError();
            updateStep();
        }
    }

    void goNext() {
        if (step != 1) {
            return;
        }
        if (!validateType()) {
            return;
        }
        clearStepError();
        step = 2;
        updateStep();
    }

    void runTest() {
        testConnection();
    }

    /** 保存：校验通过才关窗，校验失败留在属性页并把原因写在常驻错误行。 */
    void commit() {
        if (!validateType() || !validateDetails()) {
            if (step != 2) {
                step = 2;
                updateStep();
            }
            return;
        }
        clearStepError();
        dialog.setResult(buildProfile());
        if (dialog.isShowing()) {
            dialog.hide();
        }
    }

    int step() {
        return step;
    }

    /** 当前步骤的内容节点（不含 Dialog 外壳）——测试直接挂它。 */
    Node stepContent() {
        return dialog.getDialogPane().getContent();
    }

    /** 门禁：dialog 自带的按钮栏必须空着（导航与取消都在内容的同一行）。 */
    javafx.collections.ObservableList<javafx.scene.control.ButtonType> dialogButtonTypes() {
        return dialog.getDialogPane().getButtonTypes();
    }

    /** 保存结果（{@code commit()} 后非空）。 */
    Optional<ConnectionProfile> dialogResult() {
        return Optional.ofNullable(dialog.getResult());
    }

    /** 只有勾了「保存口令」才交给调用方落进凭据库。 */
    public String password() {
        return savePassword.isSelected() ? password.getText() : "";
    }

    /**
     * 验收帧：把向导摆到某一屏（G8/G20 的画面证据）。
     *
     * <p>只摆状态、不连接也不落库：{@code type|uri} 停在第 1 步，其余进属性页并
     * 选中对应页签。pose 名由 {@code scripts/shots.sh} 的 {@code ADB_CONNECT_WIZARD}
     * 给出，打错一个字母就会静默落到默认页 —— 对应关系由
     * {@code ScreenshotScriptAuditTest} 守着。
     */
    void poseForShots(String pose) {
        switch (pose == null ? "" : pose) {
            case "type" -> { }
            case "uri" -> {
                toggleUriRow(true);
                uriField.setText("postgresql://app@db.example.com:5432/shop?sslmode=require");
            }
            case "general" -> propertiesPose("general");
            case "advanced" -> propertiesPose("advanced");
            case "databases" -> propertiesPose("databases");
            case "ssl" -> propertiesPose("ssl");
            case "ssh" -> propertiesPose("ssh");
            case "http" -> propertiesPose("http");
            case "remarks" -> propertiesPose("remarks");
            default -> { }
        }
    }

    /** 摆一张填好示例值的 PostgreSQL 属性页，并选中指定页签。 */
    private void propertiesPose(String tab) {
        selectType(DialectKind.POSTGRESQL);
        name.setText("Production · shop");
        host.setText("db.example.com");
        port.setText("5432");
        database.setText("shop");
        username.setText("app");
        password.setText("sample-password");
        queryTimeout.setText("15");
        charset.setText("UTF-8");
        extra.setText("serverTimezone=Asia/Shanghai\nconnectTimeout=5000");
        sslMode.getSelectionModel().select(Dialect.SslLevel.VERIFY_CA);
        sslRoot.setText("/etc/ssl/certs/company-ca.pem");
        remarks.setText("Read-only account; changes go through DBA approval.");
        step = 2;
        updateStep();
        selectTab(tab);
    }

    private void selectTab(String pose) {
        if (tabs == null) {
            return;
        }
        String id = "wizard-tab-" + pose;
        tabs.getTabs().stream()
                .filter(tab -> id.equals(tab.getId()))
                .findFirst()
                .ifPresent(tab -> tabs.getSelectionModel().select(tab));
    }

    private void updateStep() {
        if (step == 1) {
            dialog.setHeaderText(initial == null ? I18n.t("wizard.step1.new")
                    : I18n.t("wizard.step1.edit"));
            dialog.getDialogPane().setContent(typeStep());
        } else {
            dialog.setHeaderText(I18n.t("wizard.step2", type.displayName()));
            dialog.getDialogPane().setContent(propertiesStep());
        }
        updateButtons();
    }

    private Button navButton(String id, String label, Runnable action) {
        Button button = new Button(label);
        button.setId(id);
        button.getStyleClass().addAll("adb-button", "adb-button--ghost");
        button.setOnAction(event -> action.run());
        return button;
    }

    private void updateButtons() {
        if (step != 2) {
            saveError.setVisible(false);
            saveError.setManaged(false);
        }
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        ColumnConstraints caption = new ColumnConstraints();
        caption.setMinWidth(72);
        ColumnConstraints value = new ColumnConstraints();
        value.setHgrow(Priority.ALWAYS);
        value.setFillWidth(true);
        grid.getColumnConstraints().addAll(caption, value);
        return grid;
    }

    private static VBox labeled(String text, Node field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        if (field instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return new VBox(4, label, field);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        label.setWrapText(true);
        return label;
    }

    // ─────────────────── 校验 / 收集 / 测试 ───────────────────

    private boolean validateType() {
        if (type == null) {
            showStepError(I18n.t("wizard.error.type"));
            return false;
        }
        return true;
    }

    private boolean validateDetails() {
        if (name.getText() == null || name.getText().isBlank()) {
            showStepError(I18n.t("wizard.error.name"));
            return false;
        }
        if (type.dialect().usesFilePath()) {
            if (file.getText() == null || file.getText().isBlank()) {
                showStepError(I18n.t("wizard.error.file"));
                return false;
            }
            return true;
        }
        if (host.getText() == null || host.getText().isBlank()) {
            showStepError(I18n.t("wizard.error.host"));
            return false;
        }
        if (database.getText() == null || database.getText().isBlank()) {
            showStepError(I18n.t("wizard.error.database"));
            return false;
        }
        return true;
    }

    private void showStepError(String message) {
        saveError.setText(message);
        saveError.setVisible(true);
        saveError.setManaged(true);
    }

    private void clearStepError() {
        saveError.setVisible(false);
        saveError.setManaged(false);
    }

    private void testConnection() {
        ConnectionProfile profile = buildProfile();
        if (profile == null) {
            testResult.setText(I18n.t("wizard.test.incomplete"));
            return;
        }
        testResult.setText(I18n.t("wizard.test.running"));
        String error = tester.test(profile.spec(password.getText()));
        testResult.setText(error == null ? I18n.t("wizard.test.ok")
                : I18n.t("wizard.test.failed", error));
    }

    private ConnectionProfile buildProfile() {
        DialectKind kind = type;
        String id = initial == null ? UUID.randomUUID().toString() : initial.id();
        String connectionName = name.getText() == null || name.getText().isBlank()
                ? kind.displayName() : name.getText().trim();
        Map<String, String> options = collectOptions();
        if (kind.dialect().usesFilePath()) {
            return new ConnectionProfile(id, connectionName, kind, "", 0,
                    file.getText() == null ? "" : file.getText().trim(), "",
                    initial != null && initial.favorite(),
                    initial == null ? 0 : initial.lastUsedAt(),
                    initial == null ? "" : initial.group(), options);
        }
        return new ConnectionProfile(id, connectionName, kind,
                host.getText() == null ? "" : host.getText().trim(),
                parsePort(port.getText(), ConnectionProfile.defaultPort(kind)),
                database.getText() == null ? "" : database.getText().trim(),
                username.getText() == null ? "" : username.getText().trim(),
                initial != null && initial.favorite(),
                initial == null ? 0 : initial.lastUsedAt(),
                initial == null ? "" : initial.group(), options);
    }

    /**
     * 汇总 options：附加参数 → 高级 → SSL → 备注。
     *
     * <p>⭐ SSL/超时/备注这些键<b>以后写者为准</b>：用户可以在「附加参数」里写
     * 驱动认识而界面没有的键，但界面自己渲染过的键由界面说了算 ——
     * 否则一个残留的 {@code sslmode=require} 会让「不加密」变成谎话。
     */
    Map<String, String> collectOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        String text = extra.getText() == null ? "" : extra.getText();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            int equals = trimmed.indexOf('=');
            if (trimmed.isEmpty() || equals <= 0) {
                continue;
            }
            String key = trimmed.substring(0, equals).trim();
            String value = trimmed.substring(equals + 1).trim();
            if (!key.isEmpty() && !MANAGED_KEYS.contains(key)) {
                options.put(key, value);
            }
        }
        put(options, OPT_QUERY_TIMEOUT, queryTimeout.getText());
        put(options, OPT_CHARSET, charset.getText());
        Dialect dialect = type.dialect();
        if (dialect.supportsSslOptionsUi()) {
            Dialect.SslLevel level = sslMode.getValue() == null
                    ? Dialect.SslLevel.OFF : sslMode.getValue();
            // 档位 → 驱动参数：键与值都由方言给，界面不再猜。
            options.putAll(dialect.sslOptions(level));
            String rootKey = dialect.sslRootCertOption();
            if (rootKey != null) {
                put(options, rootKey, sslRoot.getText());
            }
        }
        put(options, OPT_REMARKS, remarks.getText());
        return Map.copyOf(options);
    }

    private static void put(Map<String, String> options, String key, String value) {
        if (value != null && !value.isBlank()) {
            options.put(key, value.trim());
        }
    }

    private int parsePort(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    // ─────────────────── 载入既有连接 ───────────────────

    private void loadInitial() {
        if (initial == null) {
            selectType(DialectKind.SQLITE);
            return;
        }
        selectType(initial.kind());
        name.setText(initial.name());
        file.setText(initial.kind().dialect().usesFilePath() ? initial.database() : "");
        host.setText(initial.host());
        int portValue = initial.port() > 0 ? initial.port()
                : ConnectionProfile.defaultPort(initial.kind());
        port.setText(String.valueOf(portValue));
        database.setText(initial.database());
        username.setText(initial.username());
        Optional<String> saved = credentials.password(initial.id());
        saved.ifPresent(password::setText);
        savePassword.setSelected(saved.isPresent());

        applyOptions(initial.options());
    }

    /**
     * 把一批驱动参数回填到界面：TLS 页自己管的键回填档位与证书框，
     * 超时 / 字符集 / 备注回填各自的框，<b>其余原样进「附加参数」</b>。
     *
     * <p>既有连接（{@link #loadInitial()}）和 URI（{@link #applyUri()}）走同一条回填路，
     * 否则 URI 里带的 {@code ?sslmode=require} 会被静默丢掉，界面上却还显示「不加密」。
     */
    private void applyOptions(Map<String, String> options) {
        Dialect dialect = type.dialect();
        queryTimeout.setText(options.getOrDefault(OPT_QUERY_TIMEOUT, ""));
        charset.setText(options.getOrDefault(OPT_CHARSET, ""));
        sslMode.getSelectionModel().select(dialect.sslLevel(options));
        String rootKey = dialect.sslRootCertOption();
        sslRoot.setText(rootKey == null ? "" : options.getOrDefault(rootKey, ""));
        remarks.setText(options.getOrDefault(OPT_REMARKS, ""));

        StringBuilder rest = new StringBuilder();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (MANAGED_KEYS.contains(entry.getKey())) {
                continue;
            }
            rest.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        extra.setText(rest.toString().trim());
    }

    public void show() {
        dialog.setOnHidden(event -> onFinished.accept(Optional.ofNullable(dialog.getResult())));
        DialogStyles.apply(dialog, dialog.getOwner(), "connection-wizard");
        dialog.show();
    }

    public Optional<ConnectionProfile> showAndWait() {
        dialog.setOnHidden(event -> onFinished.accept(Optional.ofNullable(dialog.getResult())));
        DialogStyles.apply(dialog, dialog.getOwner(), "connection-wizard");
        return dialog.showAndWait();
    }

    public ConnectionWizard onFinished(Consumer<Optional<ConnectionProfile>> handler) {
        this.onFinished = handler == null ? result -> { } : handler;
        return this;
    }
}
