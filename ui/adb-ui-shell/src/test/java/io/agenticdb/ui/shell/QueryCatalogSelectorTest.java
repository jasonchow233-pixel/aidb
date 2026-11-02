package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.Node;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ G1（R44）：查询页的库选择器 —— 「查询跟着库走」的行为门禁。
 *
 * <p>钉四件事：
 * ① 没有库列表时保持 R39 的只展示形态（禁用项在、没有库条目）；
 * ② 灌入库列表后禁用项**消失**、换成真条目，当前库打勾；
 * ③ 选中库条目必须把库名交给外壳（不是死菜单）；
 * ④ 显示名可以换成当前库（Navicat 行 2 显示的是库，不是连接）。
 */
@ExtendWith(ApplicationExtension.class)
class QueryCatalogSelectorTest {

    private JdbcSession session;
    private ExecutorService executor;
    private QueryPage page;
    private final List<String> picked = new ArrayList<>();

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-catalog");
            thread.setDaemon(true);
            return thread;
        });
        page = new QueryPage(session, "demo-conn", "", executor, null,
                new DataGridPage.Feedback() {
                    @Override
                    public void status(String message) {
                    }

                    @Override
                    public void range(String text) {
                    }

                    @Override
                    public void duration(long millis) {
                    }
                });
        page.setOnSelectCatalog(picked::add);
        stage.setScene(new Scene(page, 900, 700));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            page.close();
        }
        executor.shutdownNow();
        session.close();
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private MenuButton connectionMenu() {
        return (MenuButton) page.lookup("#query-connection");
    }

    private MenuItem itemWithText(String text) {
        return connectionMenu().getItems().stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void withoutCatalogsTheMenuStaysDisplayOnly() {
        MenuButton menu = connectionMenu();
        assertThat(menu.getText()).isEqualTo("demo-conn");
        MenuItem bound = itemWithText(I18n.t("query.connection.bound"));
        assertThat(bound).as("无库层方言保持只展示的禁用项").isNotNull();
        assertThat(bound.isDisable()).isTrue();
        assertThat(page.catalogsForTest()).isEmpty();
    }

    @Test
    void settingCatalogsReplacesTheBoundItemWithRealEntries() {
        interact(() -> page.setCatalogs(List.of("orders", "audit"), "orders"));

        assertThat(itemWithText(I18n.t("query.connection.bound")))
                .as("库条目上线后禁用项必须消失，不留双语义并存")
                .isNull();
        MenuItem orders = itemWithText("orders");
        MenuItem audit = itemWithText("audit");
        assertThat(orders).isNotNull();
        assertThat(audit).isNotNull();
        assertThat(orders.isDisable()).isFalse();
        assertThat(orders.getGraphic()).as("当前库打勾").isNotNull();
        assertThat(audit.getGraphic()).as("非当前库不打勾").isNull();
        MenuItem header = itemWithText(I18n.t("query.catalog.switchTo"));
        assertThat(header).as("分组标题只是说明，不可点").isNotNull();
        assertThat(header.isDisable()).isTrue();
    }

    @Test
    void pickingACatalogHandsItToTheShell() {
        interact(() -> {
            page.setCatalogs(List.of("orders", "audit"), "orders");
            page.selectCatalogForTest("audit");
        });
        assertThat(picked).containsExactly("audit");
    }

    @Test
    void emptyCatalogListFallsBackToDisplayOnly() {
        interact(() -> {
            page.setCatalogs(List.of("orders", "audit"), "orders");
            page.setCatalogs(List.of(), null);
        });
        assertThat(itemWithText(I18n.t("query.connection.bound")))
                .as("灌回空列表必须退回只展示形态（读库失败不许留半截下拉）")
                .isNotNull();
        assertThat(itemWithText("orders")).isNull();
    }

    @Test
    void labelSwitchesToCurrentDatabase() {
        interact(() -> page.setConnectionLabel("loanmarket"));
        assertThat(connectionMenu().getText()).isEqualTo("loanmarket");
        assertThat(page.connectionLabelForTest()).isEqualTo("loanmarket");
        interact(() -> page.setConnectionLabel("  "));
        assertThat(connectionMenu().getText())
                .as("空白标签不许把菜单名清掉")
                .isEqualTo("loanmarket");
    }

    @Test
    void newQueryItemSurvivesBothForms() {
        MenuItem newQuery = itemWithText(I18n.t("action.new-query"));
        assertThat(newQuery).isNotNull();
        interact(() -> page.setCatalogs(List.of("orders"), "orders"));
        MenuItem again = itemWithText(I18n.t("action.new-query"));
        assertThat(again).as("换成库下拉后「新建查询」不能被重建掉").isNotNull();
    }
}
