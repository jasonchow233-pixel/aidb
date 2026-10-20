package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.CredentialStore;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.event.ActionEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 新建/编辑连接向导：三步（类型与名称 → 连接参数 → 测试与保存），
 * 可回退且不丢输入。口令只在保存时写入 {@link CredentialStore}。
 */
public final class ConnectionWizard {

    public interface Tester {
        /** @return null 表示连接成功；非 null 为可读错误 */
        String test(ConnectionSpec spec);
    }

    private final Dialog<ConnectionProfile> dialog = new Dialog<>();
    private final ComboBox<DialectKind> type = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField file = new TextField();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField database = new TextField();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label review = new Label();
    private final Label testResult = new Label("尚未测试");
    private final Label error = new Label();
    private final TextArea summary = new TextArea();
    private final BorderPane layout = new BorderPane();
    private final HBox navRow = new HBox(8);
    private final Button backButton = new Button("上一步");
    private final Button nextButton = new Button("下一步");
    private final Button testButton = new Button("测试连接");
    private ConnectionProfile pendingProfile;

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
        dialog.setHeaderText("选择数据库类型并命名连接");
        build();
        if (initial != null) {
            type.setValue(initial.kind());
            name.setText(initial.name());
            file.setText(initial.kind() == DialectKind.SQLITE ? initial.database() : "");
            host.setText(initial.host());
            port.setText(String.valueOf(initial.port() > 0 ? initial.port()
                    : ConnectionProfile.defaultPort(initial.kind())));
            database.setText(initial.database());
            username.setText(initial.username());
            credentials.password(initial.id()).ifPresent(password::setText);
            dialog.setHeaderText("编辑连接：" + initial.name());
        }
    }

    private void build() {
        type.getItems().setAll(FXCollections.observableArrayList(DialectKind.values()));
        type.setValue(DialectKind.SQLITE);
        type.setId("wizard-type");
        name.setId("wizard-name");
        file.setId("wizard-file");
        host.setId("wizard-host");
        port.setId("wizard-port");
        database.setId("wizard-database");
        username.setId("wizard-username");
        password.setId("wizard-password");
        review.setId("wizard-review");
        testResult.setId("wizard-test-result");
        error.setId("wizard-error");
        error.getStyleClass().add("adb-grid__state-text--error");
        error.setWrapText(true);
        summary.setId("wizard-summary");
        summary.setEditable(false);
        summary.setWrapText(true);
        summary.setPrefRowCount(4);

        // ⛔ 导航按钮不能放进 DialogPane.getButtonTypes()：
        // DialogPane.createButton 会给每一个按钮挂 ACTION 处理器并调用
        // dialog.setResultAndClose(...)，点“下一步”会把整个向导关掉。
        // 所以“上一步 / 下一步 / 测试连接”放在内容区，只有“保存 / 取消”用原生按钮。
        backButton.setId("wizard-back");
        nextButton.setId("wizard-next");
        testButton.setId("wizard-test");
        backButton.setOnAction(event -> back());
        nextButton.setOnAction(event -> next());
        testButton.setOnAction(event -> testConnection());
        navRow.setAlignment(Pos.CENTER_LEFT);
        navRow.getChildren().addAll(backButton, nextButton, testButton, error);
        HBox.setHgrow(error, Priority.ALWAYS);
        layout.setBottom(navRow);
        layout.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(layout);

        ButtonType save = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(save, cancel);

        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.setId("wizard-save");
        // 事件过滤器先于 DialogPane 的处理器运行：校验不通过时吞掉事件，向导不关闭。
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!validateBasic() || !validateDetails()) {
                event.consume();
                pendingProfile = null;
                return;
            }
            pendingProfile = buildProfile();
        });

        dialog.setOnShown(event -> updateButtons());
        dialog.setResultConverter(button -> button == save ? pendingProfile : null);
        updateStep();
    }

    private void back() {
        if (step == 3) {
            step = 2;
        } else if (step == 2) {
            step = 1;
        }
        updateStep();
    }

    private void next() {
        if (step == 1) {
            if (!validateBasic()) {
                return;
            }
            step = 2;
        } else if (step == 2) {
            if (!validateDetails()) {
                return;
            }
            step = 3;
        }
        updateStep();
    }

    private void updateStep() {
        if (step == 1) {
            dialog.setHeaderText(initial == null ? "第 1 步 / 共 3 步：类型与名称"
                    : "编辑连接 · 第 1 步 / 共 3 步");
            layout.setCenter(step1());
        } else if (step == 2) {
            dialog.setHeaderText("第 2 步 / 共 3 步：连接参数");
            layout.setCenter(step2());
        } else {
            dialog.setHeaderText("第 3 步 / 共 3 步：测试与保存");
            layout.setCenter(step3());
        }
        updateButtons();
    }

    private void updateButtons() {
        backButton.setDisable(step == 1);
        nextButton.setDisable(step == 3);
        testButton.setDisable(step != 3);
    }

    private Node step1() {
        GridPane grid = grid();
        grid.addRow(0, new Label("类型"), type);
        grid.addRow(1, new Label("名称"), name);
        Label hint = new Label("SQLite 用于本地文件；PostgreSQL/MySQL 用于服务器。");
        hint.getStyleClass().add("adb-muted");
        VBox box = new VBox(8, grid, hint);
        return box;
    }

    private Node step2() {
        if (type.getValue() == DialectKind.SQLITE) {
            GridPane grid = grid();
            grid.addRow(0, new Label("数据库文件"), file);
            Button browse = new Button("浏览…");
            browse.setId("wizard-browse");
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
            grid.addRow(0, new Label("数据库文件"), fileRow);
            return grid;
        }

        GridPane grid = grid();
        grid.addRow(0, new Label("主机"), host);
        grid.addRow(1, new Label("端口"), port);
        grid.addRow(2, new Label("数据库"), database);
        grid.addRow(3, new Label("用户名"), username);
        grid.addRow(4, new Label("密码"), password);
        return grid;
    }

    private Node step3() {
        review.setText("类型：" + type.getValue().displayName() + "\n"
                + "名称：" + name.getText().trim() + "\n"
                + "目标：" + targetSummary() + "\n"
                + "口令：" + (password.getText().isEmpty() ? "（未设置）" : "（已设置）"));
        summary.setText(targetSummary());
        return new VBox(8, review, testResult, new Label("摘要"), summary);
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));
        return grid;
    }

    private String targetSummary() {
        return switch (type.getValue()) {
            case SQLITE -> file.getText().trim();
            case POSTGRESQL, MYSQL -> host.getText().trim() + ":" + port.getText().trim()
                    + "/" + database.getText().trim();
        };
    }

    private boolean validateBasic() {
        if (type.getValue() == null) {
            return fail("请选择数据库类型");
        }
        if (name.getText() == null || name.getText().isBlank()) {
            return fail("连接名称不能为空");
        }
        clearError();
        return true;
    }

    private boolean validateDetails() {
        if (type.getValue() == DialectKind.SQLITE) {
            if (file.getText() == null || file.getText().isBlank()) {
                return fail("请选择数据库文件");
            }
            clearError();
            return true;
        }
        if (host.getText() == null || host.getText().isBlank()) {
            return fail("主机不能为空");
        }
        if (database.getText() == null || database.getText().isBlank()) {
            return fail("数据库不能为空");
        }
        clearError();
        return true;
    }

    /** 校验失败：错误写在常驻的错误行里（第 1、2 步看不到第 3 步的摘要区）。 */
    private boolean fail(String message) {
        error.setText(message);
        return false;
    }

    private void clearError() {
        error.setText("");
    }

    private void testConnection() {
        if (!validateBasic() || !validateDetails()) {
            testResult.setText("请先补全连接参数");
            return;
        }
        ConnectionProfile profile = buildProfile();
        if (profile == null) {
            testResult.setText("请先补全连接参数");
            return;
        }
        testResult.setText("正在测试…");
        String message = tester.test(profile.spec(password.getText()));
        testResult.setText(message == null ? "连接成功" : "连接失败：" + message);
    }

    private ConnectionProfile buildProfile() {
        DialectKind kind = type.getValue();
        String id = initial == null ? UUID.randomUUID().toString() : initial.id();
        Map<String, String> options = initial == null ? Map.of() : initial.options();
        String connectionName = name.getText() == null ? kind.displayName() : name.getText().trim();
        if (kind == DialectKind.SQLITE) {
            return new ConnectionProfile(id, connectionName, kind, "", 0, file.getText().trim(),
                    "", initial != null && initial.favorite(),
                    initial == null ? 0 : initial.lastUsedAt(), options);
        }
        int portValue = parsePort(port.getText(), ConnectionProfile.defaultPort(kind));
        return new ConnectionProfile(id, connectionName, kind, host.getText().trim(), portValue,
                database.getText().trim(), username.getText().trim(),
                initial != null && initial.favorite(),
                initial == null ? 0 : initial.lastUsedAt(), options);
    }

    private int parsePort(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    public String password() {
        return password.getText();
    }

    /** 测试用：把 DialogPane 挂到普通 Stage 上，避免无头 Dialog 窗口的焦点问题。 */
    javafx.scene.control.DialogPane dialogPane() {
        return dialog.getDialogPane();
    }

    public void show() {
        dialog.setOnHidden(event -> onFinished.accept(Optional.ofNullable(dialog.getResult())));
        dialog.show();
    }

    public Optional<ConnectionProfile> showAndWait() {
        dialog.setOnHidden(event -> onFinished.accept(Optional.ofNullable(dialog.getResult())));
        return dialog.showAndWait();
    }

    public ConnectionWizard onFinished(Consumer<Optional<ConnectionProfile>> handler) {
        this.onFinished = handler == null ? result -> { } : handler;
        return this;
    }
}
