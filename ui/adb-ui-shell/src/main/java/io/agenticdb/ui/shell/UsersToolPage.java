package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ⭐ P2 User 只读视图（H8）：列出当前库暴露的账号 / 角色，纯只读。
 *
 * <p>不同引擎的「用户目录」藏在不同系统表里，且大多要权限才能读 —— 所以这一页
 * 按方言选查询，读不到就给<b>如实说明</b>（要权限 / 该引擎不暴露），绝不装作成功。
 * SQLite 没有账号概念，直接说明；其余引擎走各自的系统视图。
 */
public final class UsersToolPage extends VBox {

    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final VBox body = new VBox(8);

    public UsersToolPage(DbSession session, SchemaService schemaService, Executor dbExecutor) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        setSpacing(12);
        setPadding(new Insets(16));
        getStyleClass().add("adb-page__body");

        title.setId("users-title");
        title.getStyleClass().add("adb-page__title");
        title.setText(I18n.t("tool.users.title"));

        subtitle.setId("users-subtitle");
        subtitle.getStyleClass().add("adb-muted");
        subtitle.setText(I18n.t("tool.users.subtitle",
                session == null ? "—" : session.dialect().displayName()));

        spinner.setMaxSize(18, 18);
        spinner.setVisible(false);

        body.setId("users-body");
        getChildren().addAll(title, subtitle, spinner, body);

        if (session == null) {
            message(I18n.t("tool.backup.noSession"));
            return;
        }
        load();
    }

    private void load() {
        spinner.setVisible(true);
        body.getChildren().clear();
        dbExecutor.execute(() -> {
            String sql = queryFor(session.dialect().kind());
            if (sql == null) {
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    message(I18n.t("tool.users.notSupported"));
                });
                return;
            }
            try (var statement = session.connection().createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                List<String> headers = new ArrayList<>();
                for (int c = 1; c <= columns; c++) {
                    headers.add(meta.getColumnLabel(c));
                }
                List<ObservableList<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    ObservableList<String> row = FXCollections.observableArrayList();
                    for (int c = 1; c <= columns; c++) {
                        String value = rs.getString(c);
                        row.add(value == null ? "NULL" : value);
                    }
                    rows.add(row);
                }
                javafx.application.Platform.runLater(() -> renderGrid(headers, rows));
            } catch (SQLException e) {
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    message(I18n.t("tool.users.noPrivilege", messageOf(e)));
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    message(I18n.t("tool.users.failed", messageOf(e)));
                });
            }
        });
    }

    private void renderGrid(List<String> headers, List<ObservableList<String>> rows) {
        spinner.setVisible(false);
        body.getChildren().clear();
        if (rows.isEmpty()) {
            message(I18n.t("tool.users.empty"));
            return;
        }
        TableView<ObservableList<String>> grid = new TableView<>();
        grid.getStyleClass().add("adb-grid");
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        for (int c = 0; c < headers.size(); c++) {
            final int index = c;
            TableColumn<ObservableList<String>, String> column =
                    new TableColumn<>(headers.get(c));
            column.setCellValueFactory(data ->
                    new SimpleStringProperty(data.getValue().get(index)));
            grid.getColumns().add(column);
        }
        grid.getItems().setAll(rows);
        Label count = new Label(I18n.tPlural("tool.users.count", rows.size(), rows.size()));
        count.getStyleClass().add("adb-muted");
        body.getChildren().addAll(count, grid);
    }

    private void message(String text) {
        spinner.setVisible(false);
        body.getChildren().clear();
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("adb-muted");
        body.getChildren().add(label);
    }

    /** 返回该方言的账号查询；不支持则返回 {@code null}。 */
    /**
     * 各方言的「账号目录」查询。
     *
     * <p>包级可见（不是 private）：这条映射是这一页的<b>全部业务</b> ——
     * 选错系统表 = 查错对象。门禁要能直接盯住它，不必起场景图。
     *
     * <p>返回 {@code null} 表示该方言不暴露账号目录（SQLite 等），
     * 调用方据此如实说明，而不是拿一条猜的 SQL 去撞库。
     */
    static String queryFor(DialectKind kind) {
        return switch (kind) {
            case MYSQL, MARIADB -> "SELECT User, Host, account_locked AS locked FROM mysql.user";
            case POSTGRESQL -> "SELECT rolname AS role, rolcanlogin AS can_login, rolsuper AS is_super "
                    + "FROM pg_roles";
            case SQLSERVER -> "SELECT name, type_desc FROM sys.database_principals "
                    + "WHERE type IN ('S','U')";
            case ORACLE -> "SELECT username, account_status FROM all_users";
            default -> null; // SQLite 等没有账号目录
        };
    }

    private static String messageOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
