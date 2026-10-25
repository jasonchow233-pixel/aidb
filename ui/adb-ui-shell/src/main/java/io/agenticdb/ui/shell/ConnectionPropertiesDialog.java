package io.agenticdb.ui.shell;

import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.Icons;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * U3：连接属性 —— <b>弹窗里套 TabPane</b>。
 *
 * <p>形态依据 {@code docs/UI-FORMS-AND-STATES.md} §4：
 * 「连接属性 = **弹窗，内部用 TabPane 分区**（常规 / SSL / 高级）」——
 * 这是最容易写错的一种：做成标签页会占掉主工作区；做成一屏表单则十几个字段挤在一起。
 *
 * <h2>属性怎么生效</h2>
 * 全部写进 {@link ConnectionProfile#options()}，而 options 会：
 * <ul>
 *   <li>作为 <b>JDBC 属性</b>传给驱动（{@code useSSL} / {@code characterEncoding} / 用户自填的任意参数）；</li>
 *   <li>其中 {@code queryTimeout}（秒）被 {@code JdbcSession} <b>自己消费</b> ——
 *       给每条语句挂超时（A10）。</li>
 * </ul>
 * ⛔ 口令类选项**不会**写进这里（口令走钥匙串，见 R4）。
 */
final class ConnectionPropertiesDialog {

    /** 语句超时（秒）—— 由 JdbcSession 消费。 */
    static final String QUERY_TIMEOUT = "queryTimeout";
    /** 字符集 —— 作为 JDBC 属性传给驱动。 */
    static final String CHARACTER_ENCODING = "characterEncoding";
    /** MySQL SSL 开关。 */
    static final String USE_SSL = "useSSL";
    /** MySQL：是否强制 SSL。 */
    static final String REQUIRE_SSL = "requireSSL";
    /** MySQL：是否校验服务端证书。 */
    static final String VERIFY_SERVER_CERTIFICATE = "verifyServerCertificate";

    private final Dialog<Map<String, String>> dialog = new Dialog<>();
    private final ConnectionProfile profile;

    private final TextField queryTimeout = new TextField();
    private final TextField characterEncoding = new TextField();
    private final CheckBox useSsl = new CheckBox("启用 SSL（MySQL）");
    private final CheckBox requireSsl = new CheckBox("强制 SSL（MySQL）");
    private final CheckBox verifyCertificate = new CheckBox("校验服务端证书（MySQL）");
    private final TextArea extra = new TextArea();

    private ConnectionPropertiesDialog(ConnectionProfile profile) {
        this.profile = profile;
        buildUi();
        load();
    }

    static ConnectionPropertiesDialog create(ConnectionProfile profile) {
        return new ConnectionPropertiesDialog(profile);
    }

    // ── 供门禁直接驱动（不点弹窗）─────────────────────────────

    /** 弹窗本体（门禁要断言「它是弹窗、内部是 TabPane」）。 */
    Dialog<Map<String, String>> dialog() {
        return dialog;
    }

    /** 语句超时输入框。 */
    TextField queryTimeoutField() {
        return queryTimeout;
    }

    /** 字符集输入框。 */
    TextField charsetField() {
        return characterEncoding;
    }

    /** 附加参数输入框。 */
    TextArea extraField() {
        return extra;
    }

    /** 当前收集到的属性。 */
    Map<String, String> collect() {
        return collectOptions();
    }

    Optional<Map<String, String>> show(Window owner, Collection<String> stylesheets) {
        if (stylesheets != null && !stylesheets.isEmpty()) {
            dialog.getDialogPane().getStylesheets().addAll(stylesheets);
            // ⭐ 主题类必须抄到 DialogPane 上 —— 否则弹窗里所有 -adb-* 查找落空。
        DialogStyles.apply(dialog, owner);
        }
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    private void buildUi() {
        dialog.setTitle("连接属性");
        dialog.setHeaderText(profile == null ? "连接属性"
                : profile.name() + " · " + profile.kind().displayName());

        queryTimeout.setId("props-query-timeout");
        queryTimeout.setPromptText("秒，0 = 不限");
        characterEncoding.setId("props-charset");
        characterEncoding.setPromptText("如 UTF-8");

        useSsl.setId("props-use-ssl");
        requireSsl.setId("props-require-ssl");
        verifyCertificate.setId("props-verify-cert");

        extra.setId("props-extra");
        extra.setPromptText("每行一个 key=value，例如：serverTimezone=Asia/Shanghai");
        extra.setPrefRowCount(6);

        TabPane tabs = new TabPane();
        tabs.setId("props-tabs");
        tabs.getTabs().addAll(
                new Tab("常规", new VBox(10,
                        labeled("语句超时（秒）", queryTimeout),
                        labeled("字符集", characterEncoding))),
                new Tab("SSL", new VBox(10, useSsl, requireSsl, verifyCertificate,
                        hint("MySQL 专用：这三个参数会作为 JDBC 属性传给驱动；"
                                + "PostgreSQL 的 SSL 请用高级页里的 sslmode=require。"))),
                new Tab("高级", new VBox(10,
                        labeled("附加 JDBC 参数", extra),
                        hint("这些参数会原样传给驱动；口令不要写在这里（口令走系统凭据库）。"))));
        tabs.getStyleClass().add("adb-form");

        dialog.getDialogPane().setContent(tabs);

        ButtonType ok = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        dialog.setResultConverter(button -> button == ok ? collect() : null);
    }

    /** 从当前档案里读回已有设置。 */
    void load() {
        Map<String, String> options = profile == null ? Map.of() : profile.options();
        queryTimeout.setText(options.getOrDefault(QUERY_TIMEOUT, ""));
        characterEncoding.setText(options.getOrDefault(CHARACTER_ENCODING, ""));
        useSsl.setSelected(Boolean.parseBoolean(options.getOrDefault(USE_SSL, "false")));
        requireSsl.setSelected(Boolean.parseBoolean(options.getOrDefault(REQUIRE_SSL, "false")));
        verifyCertificate.setSelected(
                Boolean.parseBoolean(options.getOrDefault(VERIFY_SERVER_CERTIFICATE, "true")));

        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (isManaged(entry.getKey())) {
                continue;
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        extra.setText(builder.toString().trim());
    }

    /** 收集当前设置（未填的项不写进去）。 */
    private Map<String, String> collectOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        put(options, QUERY_TIMEOUT, queryTimeout.getText());
        put(options, CHARACTER_ENCODING, characterEncoding.getText());
        if (useSsl.isSelected()) {
            options.put(USE_SSL, "true");
            options.put(REQUIRE_SSL, requireSsl.isSelected() ? "true" : "false");
            options.put(VERIFY_SERVER_CERTIFICATE, verifyCertificate.isSelected() ? "true" : "false");
        }
        for (String line : extra.getText() == null ? new String[0] : extra.getText().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }
            int index = trimmed.indexOf('=');
            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            if (!key.isEmpty()) {
                options.put(key, value);
            }
        }
        return Map.copyOf(options);
    }

    /** 这些键由对话框自己管理，不进「附加参数」框。 */
    private static boolean isManaged(String key) {
        return QUERY_TIMEOUT.equals(key) || CHARACTER_ENCODING.equals(key)
                || USE_SSL.equals(key) || REQUIRE_SSL.equals(key)
                || VERIFY_SERVER_CERTIFICATE.equals(key);
    }

    private static void put(Map<String, String> options, String key, String value) {
        if (value != null && !value.isBlank()) {
            options.put(key, value.trim());
        }
    }

    private static VBox labeled(String text, javafx.scene.Node field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        return new VBox(4, label, field);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        label.setWrapText(true);
        return label;
    }
}
