package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.CredentialStore;
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
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** SSL 档位。对用户是中性的说明，落进 options 时按方言换成驱动认识的值。 */
    enum SslMode {
        OFF("不加密"),
        PREFER("优先加密"),
        REQUIRE("必须加密"),
        VERIFY_CA("校验 CA"),
        VERIFY_IDENTITY("校验主机名");

        private final String label;

        SslMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        /** PostgreSQL {@code sslmode} 的取值。 */
        String postgres() {
            return switch (this) {
                case OFF -> "disable";
                case PREFER -> "prefer";
                case REQUIRE -> "require";
                case VERIFY_CA -> "verify-ca";
                case VERIFY_IDENTITY -> "verify-full";
            };
        }

        /** MySQL Connector/J 8 {@code sslMode} 的取值。 */
        String mysql() {
            return switch (this) {
                case OFF -> "DISABLED";
                case PREFER -> "PREFERRED";
                case REQUIRE -> "REQUIRED";
                case VERIFY_CA -> "VERIFY_CA";
                case VERIFY_IDENTITY -> "VERIFY_IDENTITY";
            };
        }

        static SslMode of(Map<String, String> options, DialectKind kind) {
            String raw = kind == DialectKind.MYSQL
                    ? options.get(OPT_SSL_MODE_MYSQL) : options.get(OPT_SSL_MODE_PG);
            if (raw == null || raw.isBlank()) {
                return OFF;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "prefer", "preferred" -> PREFER;
                case "require", "required" -> REQUIRE;
                case "verify_ca" -> VERIFY_CA;
                case "verify_full", "verify_identity" -> VERIFY_IDENTITY;
                default -> OFF;
            };
        }
    }

    /** URI 解析结果（口令只回填到输入框，不写进 options）。 */
    record UriTarget(DialectKind kind, String host, int port, String database,
                     String username, String password) {
    }

    static final String OPT_QUERY_TIMEOUT = "queryTimeout";
    static final String OPT_CHARSET = "characterEncoding";
    static final String OPT_SSL_MODE_PG = "sslmode";
    static final String OPT_SSL_MODE_MYSQL = "sslMode";
    static final String OPT_SSL_ROOT = "sslrootcert";
    static final String OPT_REMARKS = "remarks";

    /** 由本向导自己渲染的键：不从「附加参数」框里回显，也不许用户在框里覆盖。 */
    private static final List<String> MANAGED_KEYS = List.of(
            OPT_QUERY_TIMEOUT, OPT_CHARSET, OPT_SSL_MODE_PG, OPT_SSL_MODE_MYSQL,
            OPT_SSL_ROOT, OPT_REMARKS);

    private final Dialog<ConnectionProfile> dialog = new Dialog<>();
    private final ToggleGroup typeGroup = new ToggleGroup();
    /**
     * 类型磁贴用 FlowPane 摆成**一行卡片**（Navicat 的连接类型页是网格，不是竖排大条）。
     * 以前是 VBox + VGrow，三个磁贴被拉成每个 ~220px 高的空白块（p5-connect-type 实拍）。
     */
    private final FlowPane tiles = new FlowPane(8, 8);
    private final Button cancelButton = new Button("取消");
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
    private final CheckBox savePassword = new CheckBox("保存口令（系统凭据库）");

    private final TextField queryTimeout = new TextField();
    private final TextField charset = new TextField();
    private final TextArea extra = new TextArea();
    private final ComboBox<SslMode> sslMode = new ComboBox<>();
    private final TextField sslRoot = new TextField();
    private final Label sslHint = new Label();
    private final TextArea remarks = new TextArea();
    private TabPane tabs;

    private final Label testResult = new Label("尚未测试");
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
        this.tester = tester == null ? spec -> "未配置测试器" : tester;
        dialog.initOwner(owner);
        dialog.setTitle("连接向导");
        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);
        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        build();
        loadInitial();
        updateStep();
    }

    private void build() {
        search.setId("wizard-type-search");
        search.setPromptText("搜索数据库（SQLite / PostgreSQL / MySQL）");
        search.textProperty().addListener((observable, old, text) -> filterTiles(text));

        tiles.setId("wizard-type-tiles");
        for (DialectKind kind : DialectKind.values()) {
            tiles.getChildren().add(tile(kind));
        }

        uriField.setId("wizard-uri");
        uriField.setPromptText("例如 postgresql://user:pass@host:5432/db?sslmode=require");
        HBox.setHgrow(uriField, Priority.ALWAYS);
        Button apply = new Button("解析并填入");
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
        queryTimeout.setPromptText("秒，0 = 不限");
        charset.setId("wizard-charset");
        charset.setPromptText("如 UTF-8");
        extra.setId("wizard-extra");
        extra.setPromptText("每行一个 key=value，例如：serverTimezone=Asia/Shanghai");
        extra.setPrefRowCount(5);

        sslMode.setId("wizard-ssl-mode");
        sslMode.getItems().setAll(SslMode.values());
        sslMode.getSelectionModel().select(SslMode.OFF);
        sslMode.setMaxWidth(Double.MAX_VALUE);
        sslMode.valueProperty().addListener((observable, old, mode) -> updateSslHint());
        sslRoot.setId("wizard-ssl-root");
        sslRoot.setPromptText("服务端 CA 证书路径（PostgreSQL）");
        sslHint.setId("wizard-ssl-hint");
        sslHint.getStyleClass().add("adb-muted");
        sslHint.setWrapText(true);

        remarks.setId("wizard-remarks");
        remarks.setPromptText("给这条连接留一句话（不参与连接）");
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
            case SQLITE -> "本地单文件数据库，无需服务器";
            case POSTGRESQL -> "默认端口 5432，支持 SSL";
            case MYSQL -> "默认端口 3306，支持 SSL";
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
            port.setText(kind == DialectKind.SQLITE ? ""
                    : String.valueOf(ConnectionProfile.defaultPort(kind)));
        }
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
            showStepError(e.getMessage() == null ? "URI 无法解析" : e.getMessage());
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
        toggleUriRow(false);
        step = 2;
        updateStep();
        return true;
    }

    /**
     * 解析连接 URI。
     *
     * <p>支持 {@code sqlite:文件路径}、{@code postgresql://…}、{@code postgres://…}、
     * {@code mysql://…}；也接受带 {@code jdbc:} 前缀的写法。查询串里的参数
     * <b>原样</b>进「附加参数」（口令除外，它只回填到口令框）。
     */
    static UriTarget parseUri(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("请输入连接 URI");
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:")) {
            raw = raw.substring("jdbc:".length());
            normalized = raw.toLowerCase(Locale.ROOT);
        }
        if (normalized.startsWith("sqlite:")) {
            String path = raw.substring("sqlite:".length());
            if (path.startsWith("//")) {
                path = path.substring(2);
            }
            if (path.isBlank()) {
                throw new IllegalArgumentException("SQLite URI 里缺少文件路径");
            }
            return new UriTarget(DialectKind.SQLITE, "", 0, path, "", "");
        }

        DialectKind kind = normalized.startsWith("postgresql://") || normalized.startsWith("postgres://")
                ? DialectKind.POSTGRESQL
                : normalized.startsWith("mysql://") ? DialectKind.MYSQL : null;
        if (kind == null) {
            throw new IllegalArgumentException("只支持 sqlite: / postgresql:// / postgres:// / mysql://");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URI 格式不对：" + e.getMessage());
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URI 里缺少主机名");
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
        int portValue = uri.getPort() > 0 ? uri.getPort()
                : ConnectionProfile.defaultPort(kind);
        return new UriTarget(kind, uri.getHost(), portValue, decode(db), user, secret);
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
        Label hint = new Label("选中一种库型后点「下一步」填连接参数。");
        hint.getStyleClass().add("adb-muted");

        Button uriToggle = new Button("使用 URI 新建连接…");
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
                cancelButton, navButton("wizard-next", "下一步 →", this::goNext));
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
                tab("wizard-tab-general", "常规", generalPane()),
                tab("wizard-tab-advanced", "高级", advancedPane()),
                tab("wizard-tab-databases", "数据库", databasesPane()),
                tab("wizard-tab-ssl", "SSL", sslPane()),
                tab("wizard-tab-ssh", "SSH", notice(
                        "本版没有内置 SSH 隧道引擎，这一页暂时不提供字段。",
                        "需要穿过跳板机时，请先在网络层建立端口转发（例如 OpenSSH 的"
                                + " ssh -L 本地端口:数据库主机:数据库端口 用户@跳板机），"
                                + "再在上面的「常规」页里填 127.0.0.1 与那个本地端口。")),
                tab("wizard-tab-http", "HTTP", notice(
                        "本版不支持 HTTP 隧道（Navicat 用一段 PHP 脚本转发）。",
                        "如果目标环境只暴露 HTTP 入口，请在网关侧开一个直连数据库端口的"
                                + "通道，或改用 SSH 端口转发（见上一页）。")),
                tab("wizard-tab-remarks", "备注", remarksPane()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Button testButton = new Button("测试连接");
        testButton.setId("wizard-test");
        testButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        testButton.setOnAction(event -> testConnection());

        Button uriButton = new Button("URI…");
        uriButton.setId("wizard-uri-open");
        uriButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        uriButton.setOnAction(event -> {
            step = 1;
            updateStep();
            toggleUriRow(true);
        });

        Button saveButton = new Button("确定");
        saveButton.setId("wizard-save");
        saveButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        saveButton.setOnAction(event -> commit());

        HBox nav = new HBox(8, navButton("wizard-back", "← 上一步", this::goBack),
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
        grid.addRow(0, new Label("连接名"), name);
        if (type.dialect().usesFilePath()) {
            Button browse = new Button("浏览…");
            browse.setId("wizard-browse");
            browse.getStyleClass().addAll("adb-button", "adb-button--ghost");
            browse.setOnAction(event -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("选择 SQLite 文件");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("SQLite", "*.db", "*.sqlite"),
                        new FileChooser.ExtensionFilter("All", "*.*"));
                java.io.File selected = chooser.showSaveDialog(dialog.getOwner());
                if (selected != null) {
                    file.setText(selected.getAbsolutePath());
                }
            });
            HBox fileRow = new HBox(8, file, browse);
            HBox.setHgrow(file, Priority.ALWAYS);
            grid.addRow(1, new Label("数据库文件"), fileRow);
            grid.addRow(2, new Label(), new Label("SQLite 是单文件数据库，没有主机与口令。"));
            return grid;
        }
        grid.addRow(1, new Label("主机"), host);
        grid.addRow(2, new Label("端口"), port);
        grid.addRow(3, new Label("默认库"), database);
        grid.addRow(4, new Label("用户名"), username);
        grid.addRow(5, new Label("口令"), password);
        grid.addRow(6, new Label(), savePassword);
        return grid;
    }

    private Node advancedPane() {
        VBox box = new VBox(10,
                labeled("语句超时（秒）", queryTimeout),
                labeled("字符集", charset),
                labeled("附加 JDBC 参数", extra),
                hint("超时由应用自己消费（0 = 不限）；其余参数原样传给驱动。"
                        + "口令不要写在这里 —— 口令只走系统凭据库。"));
        return box;
    }

    private Node databasesPane() {
        Label summary = new Label(type.dialect().usesFilePath()
                ? "SQLite 是单文件数据库，没有库列表。"
                : "连接后由服务器返回库列表；打开哪一个是「常规」页的「默认库」。");
        summary.setId("wizard-databases-summary");
        summary.setWrapText(true);
        summary.getStyleClass().add("adb-muted");
        return new VBox(10, summary,
                hint("这一页在 Navicat 里还能勾选「只显示哪些库」；本版不做，"
                        + "因为侧栏目前按服务器返回的全部库展示，勾了也没有地方生效。"));
    }

    private Node sslPane() {
        if (type == DialectKind.SQLITE) {
            return notice("SQLite 是本地文件，没有网络传输，也就没有 SSL。",
                    "把连接换成 PostgreSQL / MySQL 后，这一页会给出可用的加密档位。");
        }
        VBox box = new VBox(10);
        GridPane grid = grid();
        grid.addRow(0, new Label("加密"), sslMode);
        grid.addRow(1, new Label("根证书"), sslRoot);
        box.getChildren().addAll(grid, sslHint);
        updateSslHint();
        return box;
    }

    private void updateSslHint() {
        if (sslRoot == null || sslHint == null) {
            return;
        }
        sslRoot.setDisable(type != DialectKind.POSTGRESQL);
        if (type == DialectKind.MYSQL) {
            sslHint.setText("MySQL 由驱动按下拉档位设置 sslMode；校验 CA 需要把 CA 导入"
                    + " JVM 信任库，本版不改「根证书」这一格。");
        } else if (sslMode.getValue() == SslMode.VERIFY_CA
                || sslMode.getValue() == SslMode.VERIFY_IDENTITY) {
            sslHint.setText("校验档位需要服务端证书链可验证；填「根证书」指向 CA 的 PEM 文件。");
        } else {
            sslHint.setText("「必须加密」不校验证书；要校验请选后面的两档。");
        }
    }

    private Node remarksPane() {
        VBox box = new VBox(10, labeled("备注", remarks),
                hint("备注只落在这条连接的元数据里，不参与连接参数。"));
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
        name.setText("生产库 · shop");
        host.setText("db.example.com");
        port.setText("5432");
        database.setText("shop");
        username.setText("app");
        password.setText("示例口令");
        queryTimeout.setText("15");
        charset.setText("UTF-8");
        extra.setText("serverTimezone=Asia/Shanghai\nconnectTimeout=5000");
        sslMode.getSelectionModel().select(SslMode.VERIFY_CA);
        sslRoot.setText("/etc/ssl/certs/company-ca.pem");
        remarks.setText("只读账号；变更请走 DBA 审批。");
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
            dialog.setHeaderText(initial == null ? "第 1 步 / 共 2 步：选择数据库类型"
                    : "编辑连接 · 选择数据库类型");
            dialog.getDialogPane().setContent(typeStep());
        } else {
            dialog.setHeaderText("第 2 步 / 共 2 步：" + type.displayName() + " 连接属性");
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
            showStepError("请选择数据库类型");
            return false;
        }
        return true;
    }

    private boolean validateDetails() {
        if (name.getText() == null || name.getText().isBlank()) {
            showStepError("连接名不能为空");
            return false;
        }
        if (type.dialect().usesFilePath()) {
            if (file.getText() == null || file.getText().isBlank()) {
                showStepError("请选择数据库文件");
                return false;
            }
            return true;
        }
        if (host.getText() == null || host.getText().isBlank()) {
            showStepError("主机不能为空");
            return false;
        }
        if (database.getText() == null || database.getText().isBlank()) {
            showStepError("默认库不能为空");
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
            testResult.setText("请先补全连接参数");
            return;
        }
        testResult.setText("正在测试…");
        String error = tester.test(profile.spec(password.getText()));
        testResult.setText(error == null ? "连接成功" : "连接失败：" + error);
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
        SslMode mode = sslMode.getValue() == null ? SslMode.OFF : sslMode.getValue();
        if (type != DialectKind.SQLITE) {
            if (mode != SslMode.OFF) {
                if (type == DialectKind.MYSQL) {
                    options.put(OPT_SSL_MODE_MYSQL, mode.mysql());
                } else {
                    options.put(OPT_SSL_MODE_PG, mode.postgres());
                }
            }
            if (type == DialectKind.POSTGRESQL) {
                put(options, OPT_SSL_ROOT, sslRoot.getText());
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

        Map<String, String> options = initial.options();
        queryTimeout.setText(options.getOrDefault(OPT_QUERY_TIMEOUT, ""));
        charset.setText(options.getOrDefault(OPT_CHARSET, ""));
        sslMode.getSelectionModel().select(SslMode.of(options, initial.kind()));
        sslRoot.setText(options.getOrDefault(OPT_SSL_ROOT, ""));
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
