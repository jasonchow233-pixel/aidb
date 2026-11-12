package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.asset.AssetKind;
import io.agenticdb.asset.ObjectStoreProvider;
import io.agenticdb.objectstore.ObjectStoreCredentials;
import io.agenticdb.jdbc.SshEndpoint;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.CredentialStore;
import io.agenticdb.session.SshConfig;
import io.agenticdb.session.SshSecrets;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.HPos;
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
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
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
 * <p>⭐ R33-G8/G20：以前是「类型下拉 → 主机端口 → 复核」三步，没有 SSL / SSH，
 * 也没有 URI 入口。现在：
 * <ul>
 *   <li><b>第 1 步</b>（N27-a → R43-b）：一行六张彩色磁贴 —— <b>只摆真有驱动的六家</b>，
 *       外加「使用 URI 新建连接…」与一行「其它数据库走兼容协议」的提示。
 *       原来这里照 Navicat 摆 22 张（9 类型 + 13 厂商）配 22 个复选与搜索框，
 *       其中 16 张点进去是「本版不支持」—— 复选栏、搜索框、滚动容器与那张说明页随死磁贴一起删，
 *       「其它怎么办」改成页面上明写的一行；</li>
 *   <li><b>第 2 步</b>（N27-b）：6 个页签 —— 常规 / 高级 / 数据库 / SSL / SSH / 备注，
 *       常规页上方是 `AIDB ⟷ Database` 示意图；底部「测试连接 / URI… / 上一步 / 取消 / 确定」。</li>
 * </ul>
 *
 * <p>⛔ 按钮仍在<b>内容区</b>：{@code DialogPane.createButton} 会给按钮栏里每个按钮挂
 * {@code setResultAndClose}，点「下一步」会把整个向导关掉（4.5-12 的回归点）。
 *
 * <p>⛔ HTTP/HTTPS 代理隧道按 2026-11-10 裁定不做，页签已删除 ——
 * 不摆「未实现」的说明页，也不摆填不进驱动的输入框。（SSH 页有真字段，见 {@code sshControls()}。）
 */
public final class ConnectionWizard {

    public interface Tester {
        /** @return null 表示连接成功；非 null 为可读错误 */
        String test(ConnectionSpec spec);

        /**
         * ⭐ EXT：按档案测连接（SQL 走 {@link ConnectionSpec}，非 SQL 由实现方分支）。
         * 默认实现直接委派 {@link ConnectionProfile#spec(String)}，非 SQL 档案需实现方覆写。
         */
        default String testProfile(ConnectionProfile profile, String password) {
            return test(profile.spec(password));
        }
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
    record UriTarget(AssetKind kind, String host, int port, String database,
                     String username, String password, Map<String, String> options) {

        UriTarget {
            options = options == null ? Map.of() : Map.copyOf(options);
        }

        UriTarget(AssetKind kind, String host, int port, String database,
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
        // ⭐ P2-6：对象存储专属表单键也由向导渲染，不回显到「附加参数」框。
        all.add(ObjectStoreCredentials.OPT_PROVIDER);
        all.add(ObjectStoreCredentials.OPT_ENDPOINT);
        all.add(ObjectStoreCredentials.OPT_REGION);
        all.add(ObjectStoreCredentials.OPT_BUCKET);
        all.add(ObjectStoreCredentials.OPT_PATH_STYLE);
        return List.copyOf(all);
    }

    private final Dialog<ConnectionProfile> dialog = new Dialog<>();
    private final ToggleGroup typeGroup = new ToggleGroup();
    /**
     * 六张磁贴一行摆完的 {@link TilePane}。R43-b 之前是两组 5 列共 22 张外加滚动，
     * 因为那时类型页要照 Navicat 摆满厂商 —— 收成六张之后整页不滚，滚动容器一并删了。
     */
    private final TilePane tiles = new TilePane(8, 8);
    private final Button cancelButton = new Button(I18n.t("common.cancel"));
    private final HBox uriRow = new HBox(8);
    private final TextField uriField = new TextField();
    private DialectKind type = DialectKind.SQLITE;
    /**
     * 当前选中的非 SQL 资产种类（Redis / 对象存储）。为 {@code null} 表示走 SQL 方言分支；
     * 一旦选中非 SQL 磁贴就置上，属性页据此渲染家族自定义步骤而不是 SQL 六页签。
     */
    private AssetKind nonSqlKind = null;

    private final TextField name = new TextField();
    private final TextField file = new TextField();

    /**
     * ⭐ D-73：SQLite 那格的文件选择通道。默认是系统保存面板，门禁换替身
     * （原生面板在 Monocle 下按不住）。
     */
    private java.util.function.Function<javafx.stage.Window, java.io.File> fileChooser = window -> {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("wizard.file.chooserTitle"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.t("wizard.file.filter.sqlite"), "*.db", "*.sqlite"),
                new FileChooser.ExtensionFilter(I18n.t("wizard.file.filter.all"), "*.*"));
        return chooser.showSaveDialog(window);
    };

    /** 测试替身：见 {@link #fileChooser}。 */
    void setFileChooserForTest(java.util.function.Function<javafx.stage.Window, java.io.File> chooser) {
        this.fileChooser = chooser;
    }
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
    /**
     * ⭐ G5a：SSH 隧道页的控件。
     *
     * <p>这一页以前是一张「本版没有隧道引擎」的说明，因为那时确实没有 ——
     * 现在 {@code SshTunnel} 落地了，说明换成字段；两个秘密（跳板机口令、私钥口令短语）
     * 仍是 {@link PasswordField}，且<b>不进档案</b>：它们由调用方落进凭据库
     * （见 {@code ConnectionStore#saveSshSecrets}）。
     */
    private final CheckBox sshEnabled = new CheckBox(I18n.t("wizard.ssh.enable"));
    private final TextField sshHost = new TextField();
    private final TextField sshPort = new TextField();
    private final TextField sshUser = new TextField();
    private final ToggleGroup sshAuthGroup = new ToggleGroup();
    private final RadioButton sshByPassword = new RadioButton(I18n.t("wizard.ssh.auth.password"));
    private final RadioButton sshByKey = new RadioButton(I18n.t("wizard.ssh.auth.key"));
    private final PasswordField sshPassword = new PasswordField();
    private final TextField sshKeyPath = new TextField();
    private final PasswordField sshKeyPassphrase = new PasswordField();
    private final CheckBox sshTrustOnFirstUse = new CheckBox(I18n.t("wizard.ssh.tofu"));
    private final Label sshHint = new Label();
    /** 认证方式那两行（口令 / 私钥）单独一张小网格，换方式时整块重建。 */
    private GridPane sshAuthGrid;
    /** SSH 页的容器：字段与「这一家用不上隧道」的说明随选中的数据库换（同 TLS 页的做法）。 */
    private final StackPane sshPaneHost = new StackPane();
    private final TextArea remarks = new TextArea();
    /** Redis 的 TLS 开关（非 SQL 资产专用；SQL 的 TLS 由方言档位表达）。 */
    private final CheckBox redisSsl = new CheckBox(I18n.t("wizard.redis.ssl"));

    // ── ⭐ EXT P2-6：对象存储专属表单 ──
    // provider 由第 1 步选中的磁贴决定（四家共用一个 AssetKind），不在这里再选一次。
    private final Label osProviderLabel = new Label();
    private final TextField osEndpoint = new TextField();
    private final TextField osRegion = new TextField();
    private final TextField osBucket = new TextField();
    private final TextField osAccessKey = new TextField();
    private final PasswordField osSecretKey = new PasswordField();
    private final CheckBox osPathStyle = new CheckBox(I18n.t("wizard.os.pathStyle"));
    private final CheckBox osSsl = new CheckBox(I18n.t("wizard.os.ssl"));
    private ObjectStoreProvider selectedOsProvider = ObjectStoreProvider.S3;

    // ── ⭐ EXT（P3-2）：首步的三类资产族卡片选择器 ──
    private final ToggleGroup familyGroup = new ToggleGroup();
    private HBox familyCards;

    /**
     * ⭐ P3-2：磁贴从 7 张长到 11 张之后，一行铺开已经读不出「这是什么」了。
     * 三类资产族给一条筛选带；{@code ALL} 是默认值，保证「注册表里几家、
     * 页面上就几张」这条 R43-b 门禁仍然成立。
     *
     * <p>筛选只切 {@code visible/managed}，<b>不重建磁贴</b>：重建会把
     * {@code typeGroup} 里的旧 Toggle 一起丢掉，用户已经选中的那家会静默失效。
     */
    enum TileFamily {
        ALL("wizard.family.all"),
        SQL("wizard.family.sql"),
        REDIS("wizard.family.redis"),
        OBJECT_STORE("wizard.family.objectstore");

        private final String labelKey;

        TileFamily(String labelKey) {
            this.labelKey = labelKey;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        boolean accepts(ConnectionCatalog.Entry entry) {
            return switch (this) {
                case ALL -> true;
                case SQL -> entry.kind().isSql();
                case REDIS -> entry.kind().isRedis();
                case OBJECT_STORE -> entry.kind().isObjectStore();
            };
        }
    }

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
        // ⭐ N26：标题只走系统标题栏（Dialog 的 Stage），内容区不再画题头条。
        dialog.setTitle(initial == null
                ? I18n.t("wizard.title.new") : I18n.t("wizard.title.edit"));
        dialog.setHeaderText(null);
        // ⭐ N27：Navicat 17 的这一对页面是 900×700。R43-b 之后类型页只剩一行磁贴，
        //   这个尺寸是为<b>属性页</b>留的（库清单页签与 TLS 表单要用横向空间），不再由磁贴决定；
        //   于是类型页的磁贴块改成居中，不让下半屏空着（见 buildTileGrid）。
        dialog.getDialogPane().setPrefWidth(900);
        dialog.getDialogPane().setMinWidth(760);
        dialog.getDialogPane().setPrefHeight(700);
        dialog.getDialogPane().setMinHeight(560);

        build();
        loadInitial();
        updateStep();
    }

    private void build() {
        buildTileGrid();
        buildFamilyCards();

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

        // ⭐ G5a：SSH 隧道页。默认不勾选 —— 档案里 {@code SshConfig.enabled()} 以
        // 「地址 + 用户都填了」为准，界面上则由这颗复选说了算，两边都得干净地退回直连。
        sshEnabled.setId("wizard-ssh-enable");
        sshEnabled.selectedProperty().addListener((observable, old, on) -> updateSshEnabled());
        sshHost.setId("wizard-ssh-host");
        sshHost.setPromptText(I18n.t("wizard.ssh.host.prompt"));
        sshPort.setId("wizard-ssh-port");
        sshPort.setPromptText(String.valueOf(SshConfig.NONE.port()));
        sshPort.setPrefColumnCount(6);
        sshUser.setId("wizard-ssh-user");
        sshPassword.setId("wizard-ssh-password");
        sshKeyPath.setId("wizard-ssh-key-path");
        sshKeyPath.setPromptText(I18n.t("wizard.ssh.key.prompt"));
        sshKeyPassphrase.setId("wizard-ssh-key-passphrase");
        sshTrustOnFirstUse.setId("wizard-ssh-tofu");
        sshTrustOnFirstUse.setSelected(true);
        sshByPassword.setId("wizard-ssh-auth-password");
        sshByKey.setId("wizard-ssh-auth-key");
        sshByPassword.setToggleGroup(sshAuthGroup);
        sshByKey.setToggleGroup(sshAuthGroup);
        sshByPassword.setSelected(true);
        sshAuthGroup.selectedToggleProperty().addListener(
                (observable, old, selected) -> renderSshAuth());
        sshHint.setId("wizard-ssh-hint");
        sshHint.getStyleClass().add("adb-muted");
        sshHint.setWrapText(true);

        remarks.setId("wizard-remarks");
        remarks.setPromptText(I18n.t("wizard.remarks.prompt"));
        remarks.setPrefRowCount(5);

        redisSsl.setId("wizard-redis-ssl");
        // ⚠️ 不复用 adb-form__check：该样式类在 app.css 无定义（StyleClassCoverageTest 门禁），
        //     与其它向导复选框（savePassword / sshEnabled 等）保持一致，使用默认复选框样式。

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

    /** 右侧磁贴：六家各一张，摆成一行。 */
    private void buildTileGrid() {
        tiles.setId("wizard-type-tiles");
        // 六就是一行六张：TilePane 的 prefColumns 只是偏好，宽度够时它会自己加列，
        // 所以这里写 3 也照样排成一行 —— 写 6 是让代码和画面对得上。
        tiles.setPrefColumns(ConnectionCatalog.CONNECTIONS.size());
        tiles.setPrefTileWidth(112);
        tiles.setPrefTileHeight(108);
        // 类型页的高度是为属性页留的（弹窗两页同尺寸，不跳），所以磁贴块要在
        // 中间那条带子里居中 —— 贴着顶部摆等于让下半屏空着，像没做完。
        tiles.setAlignment(Pos.CENTER);
        for (ConnectionCatalog.Entry entry : ConnectionCatalog.CONNECTIONS) {
            tiles.getChildren().add(tile(entry));
        }
    }

    /** ⭐ P3-2：一条筛选带，四枚 chip（全部 / SQL / Redis / 对象存储）。 */
    private void buildFamilyCards() {
        Label title = new Label(I18n.t("wizard.family.title"));
        title.setId("wizard-family-title");
        title.getStyleClass().add("adb-page__title");

        Label hint = new Label(I18n.t("wizard.family.hint"));
        hint.setId("wizard-family-hint");
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);

        HBox chips = new HBox(6);
        chips.setId("wizard-family-chips");
        ToggleButton all = null;
        for (TileFamily family : TileFamily.values()) {
            ToggleButton chip = new ToggleButton(I18n.t(family.labelKey));
            chip.setId("wizard-family-" + family.id());
            chip.setUserData(family);
            chip.setToggleGroup(familyGroup);
            chip.getStyleClass().add("adb-chip");
            chip.setOnAction(event -> {
                // ⛔ ToggleGroup 允许再点一次变成「全不选」；筛选带必须永远有一族生效。
                if (!chip.isSelected()) {
                    chip.setSelected(true);
                }
            });
            chips.getChildren().add(chip);
            if (family == TileFamily.ALL) {
                all = chip;
            }
        }
        // 默认「全部」：11 张磁贴全部可见，与「注册表即页面」的旧门禁一致。
        familyGroup.selectToggle(all);
        markSelectedFamilyChip();
        familyGroup.selectedToggleProperty().addListener((observable, before, after) -> {
            markSelectedFamilyChip();
            TileFamily family = after == null ? TileFamily.ALL
                    : (TileFamily) after.getUserData();
            applyFamilyFilter(family);
        });

        VBox labels = new VBox(2, title, hint);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        familyCards = new HBox(12, labels, gap, chips);
        familyCards.setId("wizard-family-cards");
        familyCards.setAlignment(Pos.CENTER_LEFT);
        familyCards.getStyleClass().add("adb-toolbar__group");
    }

    /** 选中那枚 chip 用「活的限制」tint；不是新样式，复用网格筛选 chip 的语义。 */
    private void markSelectedFamilyChip() {
        for (Toggle toggle : familyGroup.getToggles()) {
            if (toggle instanceof ToggleButton chip) {
                boolean selected = chip.isSelected();
                if (selected && !chip.getStyleClass().contains("adb-chip--filter")) {
                    chip.getStyleClass().add("adb-chip--filter");
                } else if (!selected) {
                    chip.getStyleClass().remove("adb-chip--filter");
                }
            }
        }
    }

    /**
     * 只切换可见性。当前选中的磁贴被筛掉时，把选择挪到该族第一张 ——
     * 否则「下一步」会带着一个用户看不见的类型进属性页（假一致性）。
     */
    private void applyFamilyFilter(TileFamily family) {
        ToggleButton firstVisible = null;
        for (Node node : tiles.getChildren()) {
            boolean visible = family == TileFamily.ALL
                    || (node.getUserData() instanceof ConnectionCatalog.Entry entry
                        && family.accepts(entry));
            node.setVisible(visible);
            node.setManaged(visible);
            if (visible && firstVisible == null && node instanceof ToggleButton button) {
                firstVisible = button;
            }
        }
        Toggle selected = typeGroup.getSelectedToggle();
        if (selected instanceof Node node && (!node.isVisible() || !node.isManaged())
                && firstVisible != null) {
            typeGroup.selectToggle(firstVisible);
            ConnectionCatalog.Entry entry = (ConnectionCatalog.Entry) firstVisible.getUserData();
            if (entry.kind().isSql()) {
                selectType(entry.kind().dialectKind());
            } else {
                selectNonSql(entry);
            }
        }
    }

    /**
     * 一张磁贴 = 彩色图标 + 名称。说明收进 tooltip —— Navicat 的磁贴只有这两行，
     * 多一行小字会顶破 108px 的磁贴高度（R43-b 之后是一行六张，也没有第二行可挤）。
     */
    private ToggleButton tile(ConnectionCatalog.Entry entry) {
        // ⭐ R63-3：磁贴顶的是那一家自己的**官方标志**，走统一出口 graphic()（与树、
        //    状态栏、页签同一枚）。adb-db--* 类照旧挂在磁贴上 —— 库节点那种「形状是自己的、
        //    颜色跟厂走」的吃法还靠它，磁贴自己不再从 CSS 取色。
        // ⭐ P2-6：按具体磁贴行取图形 —— 对象存储四家同一个 AssetKind，
        //    若按 kind 反查会把四张磁贴都画成第一家的商标。
        Node glyph = ConnectionCatalog.graphicFor(entry, Icons.SIZE_TILE);
        Label icon = bigIcon(glyph);
        icon.getStyleClass().add("adb-type-tile__icon");

        Label title = new Label(entry.name());
        title.getStyleClass().add("adb-type-tile__name");
        title.setWrapText(true);
        // 单行文字的对齐看 -fx-alignment，setTextAlignment 只管多行 —— 只设后者，
        // 「MySQL」会贴在 88px 标签的左缘（实测中心偏左 20px）。
        title.setAlignment(Pos.CENTER);
        title.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        // 磁贴内容区 = 112 − 2×12 内边距 = 88。宽度按内容区算，别让它以为有 96 ——
        // 多出的 8px 会让长名字（PostgreSQL）在换行判定上差一点。
        title.setMaxWidth(88);

        // 两行名字也要装得下：44 图标 + 8 间距 + 2×16 行高 = 84，正好等于内容区高度。
        VBox box = new VBox(8, icon, title);
        box.setAlignment(Pos.CENTER);

        ToggleButton button = new ToggleButton();
        button.setId("wizard-tile-" + entry.id());
        button.setUserData(entry);
        button.setGraphic(box);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().addAll("adb-type-tile", entry.styleClass());
        button.setToggleGroup(typeGroup);
        // 卡片固定尺寸：磁贴是「选一个」，不是「填满整页」。
        button.setPrefSize(112, 108);
        button.setMinSize(112, 108);
        button.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        button.setAlignment(Pos.CENTER);
        button.setTooltip(new Tooltip(I18n.t(entry.hintKey())));
        button.setOnAction(event -> {
            // ⛔ 再点一次已选磁贴不允许变成「全不选」。
            if (!button.isSelected()) {
                button.setSelected(true);
            }
            // SQL 族走方言分支；非 SQL（Redis / 对象存储）走资产族自定义步骤 ——
            // 传整行 entry 而不是 kind，才能把「四家中的哪一家」带下去。
            if (entry.kind().isSql()) {
                selectType(entry.kind().dialectKind());
            } else {
                selectNonSql(entry);
            }
        });
        return button;
    }

    /**
     * 大图标必须自己占住尺寸：{@link Icons#of} 返回的是带 scaleX/scaleY 的 SVGPath，
     * 布局边界仍是 16×16 —— 直接叠进 VBox，下面的文字会压到图形上（实测帧里
     * 「MySQL」浮在圆柱体底边上）。把 Label 钉成 44×44，间距才按视觉算。
     */
    private static Label bigIcon(Node graphic) {
        return bigIcon(graphic, Icons.SIZE_TILE);
    }

    private static Label bigIcon(Node graphic, double size) {
        Label label = new Label();
        label.setGraphic(graphic);
        label.setAlignment(Pos.CENTER);
        label.setMinSize(size, size);
        label.setPrefSize(size, size);
        label.setMaxSize(size, size);
        return label;
    }

    void selectType(DialectKind kind) {
        // 切回 SQL 族时清掉非 SQL 选择，避免两路状态叠加。
        nonSqlKind = null;
        DialectKind previous = type;
        type = kind;
        // ⚠️ 磁贴组里也含非 SQL 资产（Redis），它没 DialectKind，必须先 isSql() 再反查，
        //    否则遍历到 Redis 磁贴时 dialectKind() 会抛「不是 SQL 资产」。
        typeGroup.getToggles().forEach(toggle -> toggle.setSelected(
                toggle.getUserData() instanceof ConnectionCatalog.Entry entry
                        && entry.kind().isSql() && entry.kind().dialectKind() == kind));
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
     * 选中一张非 SQL 磁贴（Redis / 对象存储）：不走方言分支，置 {@link #nonSqlKind} 让属性页
     * 渲染资产族自定义步骤。端口取该种类的默认端口（Redis 6379）。
     */
    void selectNonSql(ConnectionCatalog.Entry entry) {
        AssetKind kind = entry.kind();
        AssetKind previous = nonSqlKind;
        nonSqlKind = kind;
        if (entry.provider() != null) {
            selectedOsProvider = entry.provider();
            osProviderLabel.setText(selectedOsProvider.displayName());
        }
        // ⭐ 按磁贴 id 精确选中：对象存储四张是同一个 AssetKind，
        //    用 kind.equals 会把四张一起点亮（ToggleGroup 只剩最后一个，看上去像随机跳）。
        typeGroup.getToggles().forEach(toggle -> toggle.setSelected(
                toggle.getUserData() instanceof ConnectionCatalog.Entry e
                        && e.id().equals(entry.id())));
        // 端口预填：仅当用户没动过（空白，或仍等于上一个非 SQL 种类的默认端口）时才覆盖。
        // ⚠️ 这里不能用资产族常量兜底（DialectBranchAudit 门禁禁止 UI 层硬编码资产族常量）；
        //    previous 为空说明是首次选择，直接取当前种类的默认端口即可。
        int priorDefault = previous != null ? ConnectionProfile.defaultPort(previous)
                : ConnectionProfile.defaultPort(kind);
        String current = port.getText();
        if (current == null || current.isBlank()
                || current.equals(String.valueOf(priorDefault))) {
            port.setText(String.valueOf(ConnectionProfile.defaultPort(kind)));
        }
        redisSsl.setSelected(false);
        // 非 SQL 资产无方言档位；Redis 用 redisSsl、对象存储用 osSsl 表达 TLS。
        if (sslHost != null) {
            sslHost.getChildren().clear();
            sslHost.getChildren().add(
                    kind.isObjectStore() ? objectStoreSslNotice() : nonSqlSslNotice());
        }
    }

    /** 既有档案 / URI 的 kind 级入口：按 kind 反查首行磁贴（对象存储由 loadInitial 精确覆盖）。 */
    void selectNonSql(AssetKind kind) {
        ConnectionCatalog.Entry entry = ConnectionCatalog.forKind(kind);
        if (entry != null) {
            selectNonSql(entry);
        }
    }

    /** 当前是否「本地文件库」（SQLite）：非 SQL 资产一律按「非文件」渲染常规页。 */
    private boolean isFileBased() {
        return nonSqlKind == null && type != null && type.dialect().usesFilePath();
    }

    /** 非 SQL 资产的 TLS 说明 + 开关（Redis 用 options 的 ssl=true）。 */
    private Node nonSqlSslNotice() {
        VBox box = new VBox(10);
        Label summary = new Label(I18n.t("wizard.redis.sslNote"));
        summary.setWrapText(true);
        summary.getStyleClass().add("adb-muted");
        box.getChildren().addAll(summary, redisSsl);
        HBox centered = new HBox(box);
        centered.setAlignment(Pos.CENTER);
        return centered;
    }

    /** 对象存储的 TLS 页：开关 + 凭据落盘口径说明。 */
    private Node objectStoreSslNotice() {
        VBox box = new VBox(10);
        Label summary = new Label(I18n.t("wizard.os.credentialsHint"));
        summary.setWrapText(true);
        summary.getStyleClass().add("adb-muted");
        box.getChildren().addAll(summary, osSsl);
        HBox centered = new HBox(box);
        centered.setAlignment(Pos.CENTER);
        return centered;
    }

    /**
     * ⭐ P2-6：对象存储专属页。provider 由第 1 步的磁贴决定（展示为只读标签），
     * 其余是 endpoint / region / bucket / 凭据 / path-style。
     *
     * <p>凭据<b>不</b>写进 {@code options}：那条 map 会明文落盘到 connections.json。
     * accessKey / secretKey 经 {@link ObjectStoreCredentials#encode} 编成一条串，
     * 由 {@link #password()} 交给调用方存进系统凭据库。
     */
    private Node objectStorePane() {
        GridPane grid = grid();
        grid.setId("wizard-os-grid");
        osProviderLabel.setId("wizard-os-provider");
        osEndpoint.setId("wizard-os-endpoint");
        osRegion.setId("wizard-os-region");
        osBucket.setId("wizard-os-bucket");
        osAccessKey.setId("wizard-os-access-key");
        osSecretKey.setId("wizard-os-secret-key");
        osPathStyle.setId("wizard-os-path-style");
        osSsl.setId("wizard-os-ssl");

        grid.addRow(0, new Label(I18n.t("wizard.os.provider")), osProviderLabel);
        grid.addRow(1, new Label(I18n.t("wizard.os.endpoint")), osEndpoint);
        grid.addRow(2, new Label(I18n.t("wizard.os.region")), osRegion);
        grid.addRow(3, new Label(I18n.t("wizard.os.bucket")), osBucket);
        grid.addRow(4, new Label(I18n.t("wizard.os.accessKey")), osAccessKey);
        grid.addRow(5, new Label(I18n.t("wizard.os.secretKey")), osSecretKey);
        grid.addRow(6, new Label(""), osPathStyle);

        Label hint = new Label(I18n.t("wizard.os.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("adb-muted");
        VBox box = new VBox(12, grid, hint);
        box.getStyleClass().add("adb-form");
        return box;
    }

    /** 示意图里要画的「对方」资产种类：非 SQL 用 {@link #nonSqlKind}，否则从方言推。 */
    private AssetKind diagramAssetKind() {
        return nonSqlKind != null ? nonSqlKind : AssetKind.from(type.dialect().kind());
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
        if (target.kind().isSql()) {
            selectType(target.kind().dialectKind());
        } else {
            selectNonSql(target.kind());
        }
        if (name.getText() == null || name.getText().isBlank()) {
            String suffix = target.database().isBlank() ? "" : " · " + target.database();
            name.setText(target.kind().displayName() + suffix);
        }
        boolean fileBased = target.kind().isSql() && target.kind().dialect().usesFilePath();
        file.setText(fileBased ? target.database() : "");
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
            return new UriTarget(AssetKind.from(DialectKind.SQLITE), "", 0, path, "", "");
        }

        // Redis / Redis-SSL 连接 URI（非 SQL 资产；用 AssetKind.of 解析避免字面量分支）。
        if (normalized.startsWith("redis://") || normalized.startsWith("rediss://")) {
            boolean tls = normalized.startsWith("rediss://");
            String body = raw.substring(normalized.indexOf("://") + 3);
            int slash = body.indexOf('/');
            String authority = slash < 0 ? body : body.substring(0, slash);
            String dbPath = slash < 0 ? "" : body.substring(slash + 1);
            int at = authority.lastIndexOf('@');
            String user = "", secret = "";
            if (at >= 0) {
                String userinfo = authority.substring(0, at);
                authority = authority.substring(at + 1);
                int colon = userinfo.indexOf(':');
                user = decode(colon < 0 ? userinfo : userinfo.substring(0, colon));
                secret = colon < 0 ? "" : decode(userinfo.substring(colon + 1));
            }
            if (authority.isBlank()) {
                throw new IllegalArgumentException(I18n.t("wizard.uri.missingHost"));
            }
            int portValue = 6379;
            String hostVal = authority;
            int colonPort = authority.lastIndexOf(':');
            if (colonPort >= 0) {
                try {
                    portValue = Integer.parseInt(authority.substring(colonPort + 1));
                    hostVal = authority.substring(0, colonPort);
                } catch (NumberFormatException ignored) {
                    // 没带端口：回落默认 6379。
                }
            }
            Map<String, String> opts = new LinkedHashMap<>();
            if (tls) {
                opts.put("ssl", "true");
            }
            return new UriTarget(AssetKind.of("redis"), hostVal, portValue, dbPath, user, secret, opts);
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
        return new UriTarget(AssetKind.from(kind), uri.getHost(), portValue, decode(db), user, secret, params);
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

    private Node typeStep() {
        Label title = new Label(I18n.t("wizard.type.select"));
        title.getStyleClass().add("adb-wizard__tiles-title");
        HBox header = new HBox(8, title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("adb-wizard__tiles-header");

        // 六张磁贴一屏放得下，所以类型页不再有左栏复选、搜索框与滚动容器：
        // 那三样都是「磁贴多到数不过来」的解药，摆六张时它们是噪音。
        Label hint = new Label(I18n.t("wizard.type.hint"));
        hint.setId("wizard-type-hint");
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);
        // ⭐ P3-2：11 张磁贴靠一条族筛选带收口；它是 body 的直接子节点，
        //    tiles 的父节点仍是 VBox（R43-b 那条「grow 链」门禁不受影响）。
        VBox body = new VBox(10, header, familyCards, tiles, hint);
        body.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(tiles, Priority.ALWAYS);

        Button uriToggle = new Button(I18n.t("wizard.type.uri"));
        uriToggle.setId("wizard-type-uri");
        uriToggle.getStyleClass().addAll("adb-button", "adb-button--ghost");
        uriToggle.setOnAction(event -> toggleUriRow(!uriRow.isVisible()));

        // Navicat 口径（§6.7）：URI 入口在左，导航与取消在右，**同一行**；
        // 取消在主动作左边、主动作贴右缘 —— 和导入 / 导出向导同一档手感。
        Region navGap = new Region();
        HBox.setHgrow(navGap, Priority.ALWAYS);
        HBox nav = new HBox(8, uriToggle, navGap,
                cancelButton, navButton("wizard-next", I18n.t("wizard.next"), this::goNext));
        nav.setAlignment(Pos.CENTER_LEFT);
        VBox outer = new VBox(10, body, uriRow, saveError, nav);
        // body 吃掉 outer 里多出来的高度，tiles 再吃掉 body 里多出来的 —— 少了这一句，
        // outer 只按 pref 高排到 y≈255，下面 445px 是死的（首拍实拍就是这个）。
        VBox.setVgrow(body, Priority.ALWAYS);
        // 和其他四个向导同一档留白（16px）；属性页的 GridPane 自带 12px，不加类免双份。
        outer.getStyleClass().add("adb-wizard__content");
        return outer;
    }

    // ─────────────────── 第 2 步：属性页（6 个页签） ───────────────────

    private Node propertiesStep() {
        // 非 SQL 资产（Redis）：不走 SQL 六页签，渲染资产族自定义步骤。
        if (nonSqlKind != null && !nonSqlKind.isSql()) {
            return nonSqlPropertiesStep();
        }
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
                tab("wizard-tab-ssh", I18n.t("wizard.tab.ssh"), sshPane()),
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

        // N27-b：底部五键 —— 左「测试连接 / URI…」，右「上一步 / 取消 / 确定」。
        Region navGap = new Region();
        HBox.setHgrow(navGap, Priority.ALWAYS);
        HBox nav = new HBox(8, testButton, uriButton, navGap,
                navButton("wizard-back", I18n.t("wizard.back"), this::goBack),
                cancelButton, saveButton);
        nav.setAlignment(Pos.CENTER_LEFT);
        // 页签内容有 16px 内边距（CSS），页脚两行也要跟着缩进来 ——
        // 否则「Not tested yet」与第一颗按钮会贴着弹窗左缘，和上面的页签对不齐。
        nav.getStyleClass().add("adb-wizard__footer");
        VBox status = new VBox(2, testResult, saveError);
        status.getStyleClass().add("adb-wizard__footer");

        VBox box = new VBox(8, tabs, status, nav);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return box;
    }

    /**
     * 非 SQL 资产的属性页（Redis）：复用常规 / 高级 / SSL / SSH / 备注五页，但去掉 SQL 专属的
     * 「数据库」页（库下标落在常规页的 database 字段）。SQL 六页签的渲染逻辑不动。
     */
    private Node nonSqlPropertiesStep() {
        TabPane tabs = new TabPane();
        this.tabs = tabs;
        tabs.setId("wizard-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("adb-form");
        java.util.List<Tab> pages = new java.util.ArrayList<>();
        pages.add(tab("wizard-tab-general", I18n.t("wizard.tab.general"), generalPane()));
        if (nonSqlKind.isObjectStore()) {
            // 对象存储多一页专属子表单；Redis 仍是五页，SQL 走另一条六页分支（门禁钉着 6）。
            pages.add(tab("wizard-tab-objectstore", I18n.t("wizard.tab.objectstore"),
                    objectStorePane()));
        }
        pages.add(tab("wizard-tab-advanced", I18n.t("wizard.tab.advanced"), advancedPane()));
        pages.add(tab("wizard-tab-ssl", I18n.t("wizard.tab.ssl"), sslPane()));
        pages.add(tab("wizard-tab-ssh", I18n.t("wizard.tab.ssh"), sshPane()));
        pages.add(tab("wizard-tab-remarks", I18n.t("wizard.tab.remarks"), remarksPane()));
        tabs.getTabs().addAll(pages);
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

        Region navGap = new Region();
        HBox.setHgrow(navGap, Priority.ALWAYS);
        HBox nav = new HBox(8, testButton, uriButton, navGap,
                navButton("wizard-back", I18n.t("wizard.back"), this::goBack),
                cancelButton, saveButton);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.getStyleClass().add("adb-wizard__footer");
        VBox status = new VBox(2, testResult, saveError);
        status.getStyleClass().add("adb-wizard__footer");

        VBox box = new VBox(8, tabs, status, nav);
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
        grid.setId("wizard-general-grid");
        grid.addRow(0, new Label(I18n.t("wizard.name")), name);
        if (isFileBased()) {
            Button browse = new Button(I18n.t("wizard.browse"));
            browse.setId("wizard-browse");
            browse.getStyleClass().addAll("adb-button", "adb-button--ghost");
            browse.setOnAction(event -> {
                // ⭐ D-73：选择通道抽成字段（向导族里这颗键是唯一的先例，却从没被门禁点过 ——
                //    传输那两个向导补浏览时抄的就是它，先把它钉住，别让后人抄一个没验证过的形状）。
                java.io.File selected = fileChooser.apply(dialog.getOwner());
                if (selected != null) {
                    file.setText(selected.getAbsolutePath());
                }
            });
            HBox fileRow = new HBox(8, file, browse);
            HBox.setHgrow(file, Priority.ALWAYS);
            grid.addRow(1, new Label(I18n.t("wizard.file")), fileRow);
            grid.addRow(2, new Label(), new Label(I18n.t("wizard.general.sqliteNote")));
        } else {
            grid.addRow(1, new Label(I18n.t("wizard.host")), host);
            grid.addRow(2, new Label(I18n.t("wizard.port")), port);
            grid.addRow(3, new Label(I18n.t("wizard.database")), database);
            grid.addRow(4, new Label(I18n.t("wizard.username")), username);
            grid.addRow(5, new Label(I18n.t("wizard.password")), password);
            grid.addRow(6, new Label(), savePassword);
        }
        // Navicat 的常规页把表单摆在示意图下方居中，左右留白靠 GridPane 的档位宽度。
        HBox centered = new HBox(grid);
        centered.setAlignment(Pos.CENTER);
        VBox box = new VBox(20, connectionDiagram(), centered);
        box.setId("wizard-general");
        return box;
    }

    /**
     * `AIDB ⟷ Database` 示意图（N27-b）。
     *
     * <p>不画厂商 logo：右侧那枚是**我们自己的同风格标识**（与向导磁贴、导航树同一行登记），
     * 旁边写当前选中的库型名，换类型时两者一起换 —— 一张「谁连谁」的示意图，不是品牌橱窗。
     */
    private Node connectionDiagram() {
        Label appIcon = bigIcon(Icons.of(Icons.Icon.BRAND, Icons.SIZE_TILE));
        Label appName = new Label("AIDB");
        appName.getStyleClass().add("adb-wizard__diagram-name");
        VBox app = new VBox(6, appIcon, appName);
        app.setAlignment(Pos.CENTER);

        Region line = new Region();
        line.getStyleClass().add("adb-wizard__diagram-line");
        line.setMinWidth(120);
        line.setPrefWidth(120);
        line.setMinHeight(1);
        line.setMaxHeight(1);

        // ⭐ R54：这一格整块就是「你要连哪家」，图形不能还是那颗通用圆柱。
        Node vendor = ConnectionCatalog.graphic(diagramAssetKind(), Icons.SIZE_TILE);
        Label dbIcon = bigIcon(vendor != null ? vendor
                : Icons.colored(Icons.Icon.DATABASE, Icons.SIZE_TILE));
        Label dbName = new Label(diagramAssetKind().displayName());
        dbName.getStyleClass().add("adb-wizard__diagram-name");
        VBox database = new VBox(6, dbIcon, dbName);
        database.setAlignment(Pos.CENTER);

        HBox box = new HBox(18, app, line, database);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("adb-wizard__diagram");
        return box;
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
        Label summary = new Label(isFileBased()
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
        // 非 SQL 资产（Redis）：TLS 由 redisSsl 复选框表达，不摆方言档位。
        if (nonSqlKind != null && !nonSqlKind.isSql()) {
            sslHost.getChildren().clear();
            sslHost.getChildren().add(nonSqlSslNotice());
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
        // 说明与字段同宽、同左缘：放在 GridPane 里跨两列，不然它自己会回到 x=0（贴弹窗边）。
        sslHint.setMaxWidth(550);
        grid.add(sslHint, 0, 2, 2, 1);
        box.getChildren().add(grid);
        updateSslHint();
        // 与常规页同一套居中：换页签时表单不该横向跳一格。
        HBox centered = new HBox(box);
        centered.setAlignment(Pos.CENTER);
        return centered;
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

    // ─────────────────── SSH 隧道页（G5a） ───────────────────

    private Node sshPane() {
        refreshSshPane();
        return sshPaneHost;
    }

    /**
     * SSH 页的内容随选中的数据库换：本地文件库（SQLite）没有「另一台机器」可跳，
     * 给它字段等于摆一排填了不生效的输入框 —— 和 TLS 页同一口径。
     *
     * <p>刷新点在 {@link #sshPane()}：类型是第 1 步选的，页签内容在那之后才构建，
     * 构建时按<b>当时</b>这一家取内容，所以退回第 1 步换库不需要额外刷新。
     */
    private void refreshSshPane() {
        if (sshPaneHost == null) {
            return;
        }
        sshPaneHost.getChildren().clear();
        sshPaneHost.getChildren().add(isFileBased()
                ? notice(I18n.t("wizard.ssh.sqlite"), I18n.t("wizard.ssh.sqliteNext"))
                : sshControls());
    }

    private Node sshControls() {
        GridPane grid = grid();
        grid.setId("wizard-ssh-grid");
        grid.addRow(0, new Label(I18n.t("wizard.ssh.section")), sshEnabled);
        grid.addRow(1, new Label(I18n.t("wizard.ssh.host")), sshHost);
        grid.addRow(2, new Label(I18n.t("wizard.ssh.port")), sshPort);
        grid.addRow(3, new Label(I18n.t("wizard.ssh.user")), sshUser);
        grid.addRow(4, new Label(I18n.t("wizard.ssh.auth")),
                new HBox(12, sshByPassword, sshByKey));
        // 认证方式决定下面露出哪几行：口令认证摆私钥框，用户会以为私钥也参与了认证。
        sshAuthGrid = new GridPane();
        sshAuthGrid.setHgap(10);
        sshAuthGrid.setVgap(8);
        grid.add(sshAuthGrid, 0, 5, 2, 1);
        grid.addRow(6, new Label(I18n.t("wizard.ssh.trust")), sshTrustOnFirstUse);
        sshHint.setMaxWidth(550);
        grid.add(sshHint, 0, 7, 2, 1);
        renderSshAuth();
        updateSshEnabled();

        VBox box = new VBox(10, grid);
        HBox centered = new HBox(box);
        centered.setAlignment(Pos.CENTER);
        return centered;
    }

    /** 认证方式下面的那几行：口令 → 一个密码框；私钥 → 路径 + 口令短语。 */
    private void renderSshAuth() {
        if (sshAuthGrid == null) {
            return;
        }
        sshAuthGrid.getChildren().clear();
        if (sshAuthGroup.getSelectedToggle() == sshByKey) {
            sshAuthGrid.addRow(0, new Label(I18n.t("wizard.ssh.key")), sshKeyPath);
            Button browse = new Button(I18n.t("wizard.ssh.browse"));
            browse.setId("wizard-ssh-key-browse");
            browse.getStyleClass().addAll("adb-button", "adb-button--ghost");
            browse.setOnAction(event -> choosePrivateKey());
            sshAuthGrid.add(browse, 2, 0);
            sshAuthGrid.addRow(1, new Label(I18n.t("wizard.ssh.passphrase")), sshKeyPassphrase);
            // 引擎只吃 RSA / ECDSA（MINA 没带 eddsa 依赖），提示词却很容易让人填 id_ed25519，
            // 所以把「支持哪些格式」写在字段旁边，而不是等连接失败再猜。
            Label keyFormats = new Label(I18n.t("wizard.ssh.key.formats"));
            keyFormats.setId("wizard-ssh-key-formats");
            keyFormats.getStyleClass().add("adb-muted");
            keyFormats.setWrapText(true);
            keyFormats.setMaxWidth(550);
            sshAuthGrid.add(keyFormats, 0, 2, 3, 1);
        } else {
            sshAuthGrid.addRow(0, new Label(I18n.t("wizard.ssh.password")), sshPassword);
        }
        updateSshEnabled();
    }

    /**
     * 没勾选「使用 SSH 隧道」时把整组字段灰掉，但<b>不</b>清空值。
     *
     * <p>不清空是因为：用户常先填隧道、再发现 TLS 页还没配，切回来时把地址敲掉了；
     * 灰掉但留值，配合 {@link #sshConfigFromForm()} 里「复选没勾 = 不启用」的判定，
     * 两边语义都对得上。
     */
    private void updateSshEnabled() {
        if (sshHost == null) {
            return;
        }
        boolean on = sshEnabled.isSelected();
        for (Node node : List.<Node>of(sshHost, sshPort, sshUser, sshByPassword, sshByKey,
                sshTrustOnFirstUse)) {
            node.setDisable(!on);
        }
        if (sshAuthGrid != null) {
            sshAuthGrid.setDisable(!on);
        }
        sshHint.setText(on ? I18n.t("wizard.ssh.hint") : I18n.t("wizard.ssh.hintOff"));
    }

    private void choosePrivateKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("wizard.ssh.key.choose"));
        java.io.File picked = chooser.showOpenDialog(dialog.getOwner());
        if (picked != null) {
            sshKeyPath.setText(picked.getAbsolutePath());
        }
    }

    /** 表单 → 档案里那份<b>无秘密</b>的 SSH 配置。 */
    SshConfig sshConfigFromForm() {
        if (!sshEnabled.isSelected()) {
            return SshConfig.NONE;
        }
        boolean byKey = sshAuthGroup.getSelectedToggle() == sshByKey;
        return new SshConfig(sshHost.getText(), parsePort(sshPort.getText(),
                SshEndpoint.DEFAULT_PORT), sshUser.getText(),
                byKey ? sshKeyPath.getText() : "", sshTrustOnFirstUse.isSelected());
    }

    /**
     * 表单里的两个秘密。{@code #sshSavePassword} 不复用「保存密码」复选：
     * 跳板机口令不填就<b>连不上</b>，而库口令不填还能手输，两者「不保存」的代价不对等，
     * 所以这里由调用方（{@code AppShell}）无条件落进凭据库，界面在说明里写明。
     */
    SshSecrets sshSecretsFromForm() {
        if (!sshEnabled.isSelected()) {
            return SshSecrets.NONE;
        }
        boolean byKey = sshAuthGroup.getSelectedToggle() == sshByKey;
        return new SshSecrets(byKey ? "" : sshPassword.getText(),
                byKey ? sshKeyPassphrase.getText() : "");
    }

    /** 交给调用方落进凭据库的秘密；未启用隧道时是空的（条目会被删掉）。 */
    public SshSecrets sshSecrets() {
        return sshSecretsFromForm();
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
        // 页签内容自己已有 16px（CSS）—— 这里只补到 Navicat 的 24px 落点，不叠成 40。
        box.setPadding(new Insets(8));
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

    /** 弹窗外壳 —— 门禁查档位尺寸（N27：900×700）与系统标题（N26）。 */
    javafx.scene.control.DialogPane dialogPane() {
        return dialog.getDialogPane();
    }

    String dialogTitle() {
        return dialog.getTitle();
    }

    /** 门禁：dialog 自带的按钮栏必须空着（导航与取消都在内容的同一行）。 */
    javafx.collections.ObservableList<javafx.scene.control.ButtonType> dialogButtonTypes() {
        return dialog.getDialogPane().getButtonTypes();
    }

    /** 保存结果（{@code commit()} 后非空）。 */
    Optional<ConnectionProfile> dialogResult() {
        return Optional.ofNullable(dialog.getResult());
    }

    /**
     * 交给调用方落进凭据库的那一条串。
     *
     * <p>⭐ P2-6：对象存储的 AK/SK 不是「可选口令」而是连接必需参数，
     * 所以不看「保存口令」复选框，统一经 {@link ObjectStoreCredentials#encode}
     * 编成一条串（三段密钥点号分隔），由后端会话工厂解出；否则连接根本建不起来。
     */
    public String password() {
        if (nonSqlKind != null && nonSqlKind.isObjectStore()) {
            return ObjectStoreCredentials.encode(
                    osAccessKey.getText() == null ? "" : osAccessKey.getText().trim(),
                    osSecretKey.getText() == null ? "" : osSecretKey.getText(),
                    "");
        }
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
            case "sshkey" -> propertiesPose("sshkey");
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
        // ⭐ G5a：SSH 页也摆实值 —— 这一帧要证的正是「隧道页不再是那张说明牌」。
        if ("ssh".equals(tab) || "sshkey".equals(tab)) {
            sshEnabled.setSelected(true);
            sshHost.setText("bastion.example.com");
            sshPort.setText("22");
            sshUser.setText("tunnel");
            if ("sshkey".equals(tab)) {
                // D-41 那一格（支持哪些格式）只在私钥分支在场，口令分支由 p5-connect-ssh 拍。
                sshAuthGroup.selectToggle(sshByKey);
                sshKeyPath.setText("/Users/me/.ssh/id_rsa");
                sshKeyPassphrase.setText("sample-key-passphrase");
            } else {
                sshPassword.setText("sample-bastion-password");
            }
        }
        step = 2;
        updateStep();
        selectTab("sshkey".equals(tab) ? "ssh" : tab);
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
        dialog.getDialogPane().setContent(step == 1 ? typeStep() : propertiesStep());
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
        grid.setMaxWidth(620);
        ColumnConstraints caption = new ColumnConstraints();
        // N27-b：标签右对齐，与输入框左缘贴齐后再留 10px —— 扫读时值是一列直线。
        caption.setMinWidth(110);
        caption.setPrefWidth(110);
        caption.setHalignment(HPos.RIGHT);
        ColumnConstraints value = new ColumnConstraints();
        value.setPrefWidth(430);
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
        if (isFileBased()) {
            if (file.getText() == null || file.getText().isBlank()) {
                showStepError(I18n.t("wizard.error.file"));
                return false;
            }
            return true;
        }
        // 非 SQL 资产（Redis）：主机必填，库下标可选（默认 0）。
        if (nonSqlKind != null && !nonSqlKind.isSql()) {
            if (nonSqlKind.isObjectStore()) {
                // 对象存储用自己的子表单，不复用 SQL 的 host/database 校验口径。
                if (osBucket.getText() == null || osBucket.getText().isBlank()) {
                    showStepError(I18n.t("wizard.error.bucket"));
                    return false;
                }
                if (osEndpoint.getText() == null || osEndpoint.getText().isBlank()) {
                    showStepError(I18n.t("wizard.error.endpoint"));
                    return false;
                }
                return true;
            }
            if (host.getText() == null || host.getText().isBlank()) {
                showStepError(I18n.t("wizard.error.host"));
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
        String error;
        if (nonSqlKind != null && !nonSqlKind.isSql()) {
            // 非 SQL（Redis）：档案不能落 ConnectionSpec（spec() 对非 SQL 抛异常），
            // 走覆写的 testProfile，由 AppShell 接 RedisSessions 直接验 host/port/auth/db/TLS。
            // 对象存储的表单口令在专属页（AK/SK 编码串），Redis/SQL 用主口令框原值
            // —— 测连接不能用「保存口令」复选框过滤后的值，否则测的是空账号。
            error = tester.testProfile(profile,
                    nonSqlKind != null && nonSqlKind.isObjectStore()
                            ? password() : password.getText());
        } else {
            // SQL：规格必须带上<b>表单里</b>的 SSH 秘密（spec(password) 默认用
            // SshSecrets.NONE，会让「测连接走隧道」悄悄缺口令，变成最难查的假失败）。
            // 密码同理用表单里的，不读凭据库（档案还没落库）。
            error = tester.test(profile.spec(password.getText(), sshSecretsFromForm()));
        }
        testResult.setText(error == null ? I18n.t("wizard.test.ok")
                : I18n.t("wizard.test.failed", error));
    }

    private ConnectionProfile buildProfile() {
        DialectKind kind = type;
        String id = initial == null ? UUID.randomUUID().toString() : initial.id();
        // 非 SQL 资产要用自己的 displayName 兜底，否则新 Redis/对象存储档案会叫「SQLite」。
        String connectionName = name.getText() == null || name.getText().isBlank()
                ? (nonSqlKind != null ? nonSqlKind.displayName() : kind.displayName())
                : name.getText().trim();
        Map<String, String> options = collectOptions();
        ConnectionProfile profile;
        // 非 SQL 资产（Redis / 对象存储）：用 AssetKind 主构造器建档。
        // Redis 的 database 字段借存库下标；对象存储借存 bucket（host 存 endpoint）。
        if (nonSqlKind != null && !nonSqlKind.isSql()) {
            boolean os = nonSqlKind.isObjectStore();
            String endpointOrHost = os
                    ? (osEndpoint.getText() == null ? "" : osEndpoint.getText().trim())
                    : (host.getText() == null ? "" : host.getText().trim());
            String bucketOrDb = os
                    ? (osBucket.getText() == null ? "" : osBucket.getText().trim())
                    : (database.getText() == null ? "" : database.getText().trim());
            return new ConnectionProfile(id, connectionName, nonSqlKind,
                    endpointOrHost,
                    parsePort(port.getText(), ConnectionProfile.defaultPort(nonSqlKind)),
                    bucketOrDb,
                    username.getText() == null ? "" : username.getText().trim(),
                    initial != null && initial.favorite(),
                    initial == null ? 0 : initial.lastUsedAt(),
                    initial == null ? "" : initial.group(), options, SshConfig.NONE)
                    .withSsh(sshConfigFromForm());
        }
        if (kind.dialect().usesFilePath()) {
            profile = new ConnectionProfile(id, connectionName, kind, "", 0,
                    file.getText() == null ? "" : file.getText().trim(), "",
                    initial != null && initial.favorite(),
                    initial == null ? 0 : initial.lastUsedAt(),
                    initial == null ? "" : initial.group(), options);
        } else {
            profile = new ConnectionProfile(id, connectionName, kind,
                    host.getText() == null ? "" : host.getText().trim(),
                    parsePort(port.getText(), ConnectionProfile.defaultPort(kind)),
                    database.getText() == null ? "" : database.getText().trim(),
                    username.getText() == null ? "" : username.getText().trim(),
                    initial != null && initial.favorite(),
                    initial == null ? 0 : initial.lastUsedAt(),
                    initial == null ? "" : initial.group(), options);
        }
        // ⭐ G5a：隧道配置跟着档案走，秘密不跟（SshConfig 里根本没有那两个字段）。
        return profile.withSsh(sshConfigFromForm());
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
        // 非 SQL 资产：没有方言档位；TLS 走 redisSsl 复选框（options 的 ssl=true）。
        if (nonSqlKind == null) {
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
        } else if (nonSqlKind.isRedis()) {
            put(options, "ssl", redisSsl.isSelected() ? "true" : null);
        } else if (nonSqlKind.isObjectStore()) {
            // ⭐ P2-6：非敏感参数落 options；AK/SK 绝不进来（会被明文写进 connections.json）。
            put(options, ObjectStoreCredentials.OPT_PROVIDER, selectedOsProvider.id());
            put(options, ObjectStoreCredentials.OPT_ENDPOINT, osEndpoint.getText());
            put(options, ObjectStoreCredentials.OPT_REGION, osRegion.getText());
            put(options, ObjectStoreCredentials.OPT_BUCKET, osBucket.getText());
            options.put(ObjectStoreCredentials.OPT_PATH_STYLE,
                    String.valueOf(osPathStyle.isSelected()));
            put(options, "ssl", osSsl.isSelected() ? "true" : null);
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
        // 既有连接可能是非 SQL 档案（Redis / 对象存储）：用 isSql() 分流，
        // 对象存储还要按 options 里的 provider 精确选中四张磁贴中的那一张。
        if (initial.kind().isSql()) {
            selectType(initial.kind().dialectKind());
        } else if (initial.kind().isObjectStore()) {
            String providerId = initial.options().getOrDefault(
                    ObjectStoreCredentials.OPT_PROVIDER, "s3");
            // tileId 的口吻在 TileDescriptor.tileId()：分隔符是 '-'（CSS id 安全）。
            ConnectionCatalog.Entry entry = ConnectionCatalog.forTileId(
                    initial.kind().id() + "-" + providerId);
            if (entry != null) {
                selectNonSql(entry);
            } else {
                selectNonSql(initial.kind());
            }
        } else {
            selectNonSql(initial.kind());
        }
        name.setText(initial.name());
        boolean initialFileBased = initial.kind().isSql()
                && initial.kind().dialect().usesFilePath();
        file.setText(initialFileBased ? initial.database() : "");
        host.setText(initial.host());
        int portValue = initial.port() > 0 ? initial.port()
                : ConnectionProfile.defaultPort(initial.kind());
        port.setText(String.valueOf(portValue));
        database.setText(initial.database());
        username.setText(initial.username());
        Optional<String> saved = credentials.password(initial.id());
        if (initial.kind().isObjectStore()) {
            // 凭据库里那条是三段编码串，解回专属页的两个框；界面从不假装知道存了什么。
            saved.ifPresent(stored -> {
                String[] parts = ObjectStoreCredentials.decode(stored);
                osAccessKey.setText(parts[0]);
                osSecretKey.setText(parts[1]);
            });
        } else {
            saved.ifPresent(password::setText);
        }
        savePassword.setSelected(saved.isPresent());

        loadInitialSsh(initial.ssh());
        applyOptions(initial.options());
    }

    /**
     * ⭐ G5a：把档案里的隧道配置回填进表单。
     *
     * <p>秘密单独从凭据库读（{@link SshConfig} 里根本没有那两个字段），读不到就留空框 ——
     * 与库口令同一条口径：界面<b>从不</b>假装知道存了什么。
     */
    private void loadInitialSsh(SshConfig ssh) {
        sshEnabled.setSelected(ssh.enabled());
        sshHost.setText(ssh.host());
        sshPort.setText(ssh.host().isBlank() ? "" : String.valueOf(ssh.port()));
        sshUser.setText(ssh.username());
        boolean byKey = !ssh.privateKeyPath().isBlank();
        if (byKey) {
            sshByKey.setSelected(true);
        } else {
            sshByPassword.setSelected(true);
        }
        sshKeyPath.setText(ssh.privateKeyPath());
        sshTrustOnFirstUse.setSelected(ssh.trustOnFirstUse());
        SshSecrets saved = storedSshSecrets(initial.id());
        if (saved.password() != null) {
            sshPassword.setText(saved.password());
        }
        if (saved.keyPassphrase() != null) {
            sshKeyPassphrase.setText(saved.keyPassphrase());
        }
    }

    /** 凭据库里那两条 SSH 秘密；没有则 {@link SshSecrets#NONE}。 */
    private SshSecrets storedSshSecrets(String profileId) {
        return new SshSecrets(
                credentials.password(SshConfig.sshPasswordKey(profileId)).orElse(null),
                credentials.password(SshConfig.sshKeyPassphraseKey(profileId)).orElse(null));
    }

    /**
     * 把一批驱动参数回填到界面：TLS 页自己管的键回填档位与证书框，
     * 超时 / 字符集 / 备注回填各自的框，<b>其余原样进「附加参数」</b>。
     *
     * <p>既有连接（{@link #loadInitial()}）和 URI（{@link #applyUri()}）走同一条回填路，
     * 否则 URI 里带的 {@code ?sslmode=require} 会被静默丢掉，界面上却还显示「不加密」。
     */
    private void applyOptions(Map<String, String> options) {
        queryTimeout.setText(options.getOrDefault(OPT_QUERY_TIMEOUT, ""));
        charset.setText(options.getOrDefault(OPT_CHARSET, ""));
        // 非 SQL 资产：没有方言档位；TLS 复选框从 options 的 ssl 回填。
        if (nonSqlKind == null) {
            Dialect dialect = type.dialect();
            sslMode.getSelectionModel().select(dialect.sslLevel(options));
            String rootKey = dialect.sslRootCertOption();
            sslRoot.setText(rootKey == null ? "" : options.getOrDefault(rootKey, ""));
        } else if (nonSqlKind.isRedis()) {
            redisSsl.setSelected("true".equals(options.getOrDefault("ssl", "")));
        } else if (nonSqlKind.isObjectStore()) {
            osEndpoint.setText(options.getOrDefault(ObjectStoreCredentials.OPT_ENDPOINT, ""));
            osRegion.setText(options.getOrDefault(ObjectStoreCredentials.OPT_REGION, ""));
            osBucket.setText(options.getOrDefault(ObjectStoreCredentials.OPT_BUCKET, ""));
            osPathStyle.setSelected(Boolean.parseBoolean(
                    options.getOrDefault(ObjectStoreCredentials.OPT_PATH_STYLE, "true")));
            osSsl.setSelected("true".equals(options.getOrDefault("ssl", "")));
            String providerId = options.getOrDefault(ObjectStoreCredentials.OPT_PROVIDER, "");
            if (!providerId.isBlank()) {
                try {
                    selectedOsProvider = ObjectStoreProvider.of(providerId);
                    osProviderLabel.setText(selectedOsProvider.displayName());
                } catch (IllegalArgumentException ignored) {
                    // 旧档案里的未知 provider：保留默认，不偷偷改成别家。
                }
            }
        }
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
        DialogStyles.apply(dialog, dialog.getOwner());
        dialog.show();
    }

    public Optional<ConnectionProfile> showAndWait() {
        dialog.setOnHidden(event -> onFinished.accept(Optional.ofNullable(dialog.getResult())));
        DialogStyles.apply(dialog, dialog.getOwner());
        return dialog.showAndWait();
    }

    public ConnectionWizard onFinished(Consumer<Optional<ConnectionProfile>> handler) {
        this.onFinished = handler == null ? result -> { } : handler;
        return this;
    }
}
