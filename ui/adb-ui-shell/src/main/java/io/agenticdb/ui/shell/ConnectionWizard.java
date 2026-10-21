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
    private final TextArea summary = new TextArea();
    /** 第 3 步的常驻错误行：校验失败时保存不关窗，原因写在这里（4.5-12）。 */
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
        dialog.setHeaderText("选择数据库类型并命名连接");
        build();
        if (initial != null) {
            type.setValue(initial.kind());
            name.setText(initial.name());
            file.setText(initial.kind().dialect().usesFilePath() ? initial.database() : "");
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
        summary.setId("wizard-summary");
        summary.setEditable(false);
        summary.setWrapText(true);
        summary.setPrefRowCount(4);
        saveError.setId("wizard-save-error");
        saveError.getStyleClass().add("adb-error");
        saveError.setWrapText(true);
        saveError.setVisible(false);
        saveError.setManaged(false);

        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        // ⭐ 按钮栏只留「取消」。back/next/test/save 全部放在每一步的内容区：
        // DialogPane 会给按钮栏里每个按钮挂 setResultAndClose，点「下一步」
        // 曾经把整个向导关掉（4.5-12 的回归点）；而且按钮栏按钮无法在测试里稳定挂载。
        dialog.getDialogPane().getButtonTypes().setAll(cancel);

        dialog.setOnShown(event -> updateButtons());
        // 保存走 commit()：setResult + close，取消返回 null。
        dialog.setResultConverter(button -> button == cancel ? null : dialog.getResult());
        updateStep();
    }

    // ─────────────────── 导航与操作（按钮与测试共用） ───────────────────

    void goBack() {
        if (step == 3) {
            step = 2;
        } else if (step == 2) {
            step = 1;
        }
        updateStep();
    }

    void goNext() {
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
        clearStepError();
        updateStep();
    }

    void runTest() {
        testConnection();
    }

    /** 保存：校验通过才关窗，校验失败留在第 3 步并把原因写在常驻错误行。 */
    void commit() {
        if (!validateBasic() || !validateDetails()) {
            if (step != 3) {
                step = 3;
                updateStep();
            }
            saveError.setText(review.getText());
            saveError.setVisible(true);
            saveError.setManaged(true);
            return;
        }
        saveError.setVisible(false);
        saveError.setManaged(false);
        dialog.setResult(buildProfile());
        if (dialog.isShowing()) {
            dialog.hide();
        }
    }

    int step() {
        return step;
    }

    /** 当前步骤的内容节点（不含 Dialog 外壳）——测试直接挂它，绕开 DialogPane 的场景归属问题。 */
    Node stepContent() {
        return dialog.getDialogPane().getContent();
    }

    /** 保存结果（{@code commit()} 后非空）——测试与 {@code showAndWait()} 共用同一来源。 */
    Optional<ConnectionProfile> dialogResult() {
        return Optional.ofNullable(dialog.getResult());
    }

    private void updateStep() {
        if (step == 1) {
            dialog.setHeaderText(initial == null ? "第 1 步 / 共 3 步：类型与名称"
                    : "编辑连接 · 第 1 步 / 共 3 步");
            dialog.getDialogPane().setContent(step1());
        } else if (step == 2) {
            dialog.setHeaderText("第 2 步 / 共 3 步：连接参数");
            dialog.getDialogPane().setContent(step2());
        } else {
            dialog.setHeaderText("第 3 步 / 共 3 步：测试与保存");
            dialog.getDialogPane().setContent(step3());
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
        // 按钮都在内容区、随步骤重建；这里只维护第 3 步错误行的显隐。
        if (step != 3) {
            saveError.setVisible(false);
            saveError.setManaged(false);
        }
    }

    private Node step1() {
        GridPane grid = grid();
        grid.addRow(0, new Label("类型"), type);
        grid.addRow(1, new Label("名称"), name);
        Label hint = new Label("SQLite 用于本地文件；PostgreSQL/MySQL 用于服务器。");
        hint.getStyleClass().add("adb-muted");
        return withNav(new VBox(8, grid, hint),
                navButton("wizard-next", "下一步 →", this::goNext));
    }

    private Node step2() {
        if (type.getValue().dialect().usesFilePath()) {
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
            return withNav(grid,
                    navButton("wizard-back", "← 上一步", this::goBack),
                    navButton("wizard-next", "下一步 →", this::goNext));
        }

        GridPane grid = grid();
        grid.addRow(0, new Label("主机"), host);
        grid.addRow(1, new Label("端口"), port);
        grid.addRow(2, new Label("数据库"), database);
        grid.addRow(3, new Label("用户名"), username);
        grid.addRow(4, new Label("密码"), password);
        return withNav(grid,
                navButton("wizard-back", "← 上一步", this::goBack),
                navButton("wizard-next", "下一步 →", this::goNext));
    }

    /** 步骤内容 + 底部按钮行：导航按钮必须在内容区，不能进 Dialog 按钮栏（4.5-12）。 */
    private VBox withNav(Node content, Button... buttons) {
        HBox nav = new HBox(8, buttons);
        nav.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(8, content, saveError, nav);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private Node step3() {
        review.setText("类型：" + type.getValue().displayName() + "\n"
                + "名称：" + name.getText().trim() + "\n"
                + "目标：" + targetSummary() + "\n"
                + "口令：" + (password.getText().isEmpty() ? "（未设置）" : "（已设置）"));
        Button testButton = new Button("测试连接");
        testButton.setId("wizard-test");
        testButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        testButton.setOnAction(event -> testConnection());
        Button saveButton = new Button("保存");
        saveButton.setId("wizard-save");
        saveButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        saveButton.setOnAction(event -> commit());
        VBox box = new VBox(8, review, testResult, new Label("摘要"), summary,
                withNav(new Label(),
                        navButton("wizard-back", "← 上一步", this::goBack),
                        testButton, saveButton));
        summary.setText(targetSummary());
        return box;
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
            showStepError("请选择数据库类型");
            return false;
        }
        if (name.getText() == null || name.getText().isBlank()) {
            showStepError("连接名称不能为空");
            return false;
        }
        return true;
    }

    private boolean validateDetails() {
        if (type.getValue().dialect().usesFilePath()) {
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
            showStepError("数据库不能为空");
            return false;
        }
        return true;
    }

    /**
     * 常驻错误行：每一步的内容区都有它，校验失败时显示、成功前进时隐藏。
     * 早期版本把错误写进第 3 步才有的 {@code review}，第 1、2 步填错时用户
     * 什么都看不到（4.5-12）。
     */
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
        DialectKind kind = type.getValue();
        String id = initial == null ? UUID.randomUUID().toString() : initial.id();
        Map<String, String> options = initial == null ? Map.of() : initial.options();
        String connectionName = name.getText() == null ? kind.displayName() : name.getText().trim();
        if (kind.dialect().usesFilePath()) {
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
