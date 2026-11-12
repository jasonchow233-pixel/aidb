package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ⭐ EXT（P3-4）：已打开标签的总览 —— 由命令面板的 {@code tabs} 命令唤起，
 * <b>不是</b>常驻标签（Q5：避免标签栏膨胀）。
 *
 * <p>按 {@code connectionId} 分组，支持搜索、点击激活、逐条关闭、按连接一键全关。
 * 所有关闭都回调到 {@code TabBar.close()}，因此自动复用既有的 {@code closeGuard}
 * 脏检查（规划 P3-5「关闭保护接入新页面」）—— 总览页<b>不绕过</b>关闭链路，
 * 否则「从总览里关」会变成绕过未保存确认的后门。
 *
 * <p>标签数据由调用方在打开面板时快照传入（{@code TabBar.openTabs()}），
 * 面板自己不订阅变化：它是一个一次性治理面板，不是常驻视图。
 */
final class TabOverviewPanel extends VBox {

    private final List<TabItem> tabs;
    private final Consumer<TabItem> onActivate;
    private final Consumer<TabItem> onClose;
    private final Consumer<String> onCloseGroup;
    private final TextField search = new TextField();
    private final VBox rows = new VBox(6);

    TabOverviewPanel(List<TabItem> tabs,
                     Consumer<TabItem> onActivate,
                     Consumer<TabItem> onClose,
                     Consumer<String> onCloseGroup) {
        this.tabs = tabs == null ? List.of() : List.copyOf(tabs);
        this.onActivate = onActivate == null ? t -> { } : onActivate;
        this.onClose = onClose == null ? t -> { } : onClose;
        this.onCloseGroup = onCloseGroup == null ? g -> { } : onCloseGroup;

        getStyleClass().add("adb-page");
        setSpacing(10);
        setPrefWidth(640);
        setMinHeight(320);

        Label title = new Label(I18n.t("tab.overview.title"));
        title.getStyleClass().add("adb-page__title");
        Label hint = new Label(I18n.t("tab.overview.hint"));
        hint.getStyleClass().add("adb-muted");

        search.setId("tab-overview-search");
        search.setPromptText(I18n.t("tab.overview.search"));
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((observable, before, after) -> render());

        getChildren().addAll(title, hint, search, rows);
        VBox.setVgrow(rows, Priority.ALWAYS);
        render();
    }

    private void render() {
        rows.getChildren().clear();
        String needle = search.getText() == null
                ? "" : search.getText().trim().toLowerCase(Locale.ROOT);

        Map<String, List<TabItem>> groups = new LinkedHashMap<>();
        for (TabItem tab : tabs) {
            String group = tab.connectionId() == null ? "" : tab.connectionId();
            if (!needle.isEmpty()
                    && !tab.title().toLowerCase(Locale.ROOT).contains(needle)
                    && !group.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            groups.computeIfAbsent(group, key -> new ArrayList<>()).add(tab);
        }

        if (groups.isEmpty()) {
            rows.getChildren().add(EmptyState.of(Icons.Icon.LIST,
                    I18n.t("tab.overview.empty"), I18n.t("tab.overview.hint")));
            return;
        }

        for (Map.Entry<String, List<TabItem>> group : groups.entrySet()) {
            rows.getChildren().add(groupHeader(group.getKey()));
            for (TabItem tab : group.getValue()) {
                rows.getChildren().add(row(tab));
            }
        }
    }

    private HBox groupHeader(String group) {
        Label label = new Label(I18n.t("tab.overview.group", group));
        label.getStyleClass().add("adb-muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeGroup = new Button(I18n.t("tab.overview.closeGroup"));
        closeGroup.getStyleClass().addAll("adb-button");
        closeGroup.setOnAction(event -> onCloseGroup.accept(group));
        HBox header = new HBox(8, label, spacer, closeGroup);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private HBox row(TabItem tab) {
        Label label = new Label(tab.title());
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);

        Button activate = new Button(I18n.t("tab.overview.activate"));
        activate.getStyleClass().addAll("adb-button", "adb-button--primary");
        activate.setOnAction(event -> onActivate.accept(tab));

        Button close = new Button(I18n.t("tab.overview.close"));
        close.getStyleClass().addAll("adb-button");
        close.setOnAction(event -> onClose.accept(tab));

        HBox line = new HBox(8, label, activate, close);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("adb-toolbar__group");
        return line;
    }
}
