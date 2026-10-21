package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ⭐ **对象首页** —— 标签栏最左边那个常驻的「对象」标签的内容。
 *
 * <p>Navicat 的标签栏第一个就是常驻的「对象」标签（实拍见
 * {@code docs/NAVICAT-COMPARE.md} §2.2 C1）：它是**浏览的起点**，
 * 点它回到对象总览，而不是回到一个空白的欢迎页。
 *
 * <p>这里按类型分组列出当前连接的全部对象（表 / 视图 / 过程 / 函数 / 触发器），
 * 点一下就打开对应的数据页。
 */
final class ObjectHomePage extends VBox {

    /** 对象类型 → 展示分组名。 */
    private static final List<SchemaService.ObjectKind> KINDS = List.of(
            SchemaService.ObjectKind.TABLE,
            SchemaService.ObjectKind.VIEW,
            SchemaService.ObjectKind.PROCEDURE,
            SchemaService.ObjectKind.FUNCTION,
            SchemaService.ObjectKind.TRIGGER);

    private final Supplier<DbSession> session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final Consumer<String> onOpen;
    private final Consumer<String> status;
    private final Label summary = new Label();
    private final VBox groups = new VBox(12);
    private final Button refresh = IconButtons.of(Icons.Icon.REFRESH, "刷新");

    ObjectHomePage(Supplier<DbSession> session,
                   SchemaService schemaService,
                   Executor dbExecutor,
                   Consumer<String> onOpen,
                   Consumer<String> status) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.onOpen = onOpen == null ? name -> { } : onOpen;
        this.status = status == null ? message -> { } : status;
        getStyleClass().addAll("adb-page", "adb-object-home");
        buildUi();
    }

    private void buildUi() {
        Label title = new Label("对象");
        title.getStyleClass().add("adb-page__title");
        Label badge = new Label("总览");
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        summary.getStyleClass().add("adb-muted");

        HBox header = new HBox(10, title, badge, spacer, summary);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        refresh.setId("object-home-refresh");
        refresh.getStyleClass().add("adb-button");
        refresh.setOnAction(event -> reload());

        groups.getStyleClass().add("adb-object-home__groups");

        ScrollPane scroll = new ScrollPane(groups);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("adb-object-home__scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox body = new VBox(8, scroll);
        body.getStyleClass().add("adb-page__body");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, body);
    }

    /** 重新读一遍对象列表。 */
    void reload() {
        DbSession current = session.get();
        if (current == null) {
            groups.getChildren().clear();
            summary.setText("未连接");
            return;
        }
        dbExecutor.execute(() -> {
            List<SchemaService.SchemaObject> objects;
            try {
                objects = schemaService.listObjects(current);
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    summary.setText("读取失败：" + e.getMessage());
                    groups.getChildren().clear();
                });
                return;
            }
            javafx.application.Platform.runLater(() -> render(objects));
        });
    }

    private void render(List<SchemaService.SchemaObject> objects) {
        groups.getChildren().clear();
        summary.setText("共 " + objects.size() + " 个对象");

        for (SchemaService.ObjectKind kind : KINDS) {
            List<SchemaService.SchemaObject> group = new ArrayList<>();
            for (SchemaService.SchemaObject object : objects) {
                if (object.kind() == kind) {
                    group.add(object);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            groups.getChildren().add(section(kind, group));
        }

        if (groups.getChildren().isEmpty()) {
            Label empty = new Label("这个连接还没有可浏览的对象");
            empty.getStyleClass().add("adb-muted");
            groups.getChildren().add(empty);
        }
    }

    /** 一个分组：标题 + 一排可点的对象。 */
    private VBox section(SchemaService.ObjectKind kind,
                         List<SchemaService.SchemaObject> objects) {
        Label title = new Label(labelOf(kind) + "（" + objects.size() + "）");
        title.getStyleClass().add("adb-section-title");

        FlowPane flow = new FlowPane();
        flow.getStyleClass().add("adb-object-home__flow");
        flow.setHgap(8);
        flow.setVgap(8);
        for (SchemaService.SchemaObject object : objects) {
            flow.getChildren().add(objectChip(kind, object));
        }

        VBox box = new VBox(6, title, flow);
        box.getStyleClass().add("adb-object-home__group");
        return box;
    }

    /** 一个对象的小卡片。 */
    private Button objectChip(SchemaService.ObjectKind kind,
                               SchemaService.SchemaObject object) {
        Button chip = new Button(object.name());
        chip.setGraphic(Icons.of(iconOf(kind), 14));
        chip.setGraphicTextGap(6);
        chip.getStyleClass().addAll("adb-button", "adb-object-home__chip");
        chip.setTooltip(new javafx.scene.control.Tooltip(
                labelOf(kind) + " " + object.name()
                        + (object.schema() == null || object.schema().isBlank()
                        ? "" : "（" + object.schema() + "）")));
        chip.setOnAction(event -> onOpen.accept(object.name()));
        return chip;
    }

    private static String labelOf(SchemaService.ObjectKind kind) {
        return switch (kind) {
            case TABLE -> "表";
            case VIEW -> "视图";
            case PROCEDURE -> "过程";
            case FUNCTION -> "函数";
            case TRIGGER -> "触发器";
        };
    }

    private static Icons.Icon iconOf(SchemaService.ObjectKind kind) {
        return switch (kind) {
            case TABLE -> Icons.Icon.TABLE;
            case VIEW -> Icons.Icon.VIEW_OBJECT;
            case PROCEDURE, FUNCTION -> Icons.Icon.FUNCTION;
            case TRIGGER -> Icons.Icon.TRIGGER;
        };
    }
}
