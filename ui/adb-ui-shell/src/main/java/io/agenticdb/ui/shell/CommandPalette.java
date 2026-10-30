package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 命令面板。菜单、工具栏、右键菜单的动作都从这里搜得到。
 *
 * <p>P0 只做「执行已注册动作」；对象搜索（表/列/注释）在 P1 接入。
 */
public final class CommandPalette extends StackPane {

    private final ActionRegistry actions;
    private final TextField query = new TextField();
    private final ListView<Action> list = new ListView<>();
    private final VBox panel = new VBox(8);
    private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> sceneKeys = this::handleKey;

    public CommandPalette(ActionRegistry actions) {
        this.actions = actions;
        setId("command-palette-overlay");
        getStyleClass().add("adb-palette-overlay");
        setVisible(false);
        setManaged(false);

        Region scrim = new Region();
        scrim.getStyleClass().add("adb-palette__scrim");
        scrim.setOnMouseClicked(event -> hidePalette());

        Label hint = new Label(I18n.t("palette.hint"));
        hint.getStyleClass().add("adb-palette__hint");

        query.getStyleClass().add("adb-palette__input");
        query.setPromptText(I18n.t("palette.prompt"));
        query.textProperty().addListener((observable, oldValue, newValue) -> filter(newValue));
        query.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hidePalette();
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                runSelected();
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN) {
                list.getSelectionModel().selectNext();
                event.consume();
            } else if (event.getCode() == KeyCode.UP) {
                list.getSelectionModel().selectPrevious();
                event.consume();
            }
        });

        list.getStyleClass().add("adb-palette__list");
        list.setPrefHeight(320);
        list.setFocusTraversable(false);
        list.setCellFactory(view -> new ActionCell());
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                runSelected();
            }
        });

        panel.getStyleClass().add("adb-palette");
        panel.setMaxWidth(620);
        panel.setMaxHeight(420);
        panel.setPadding(new Insets(12));
        panel.getChildren().addAll(hint, query, list);
        panel.setOnMouseClicked(event -> event.consume());

        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && isVisible()) {
                hidePalette();
                event.consume();
            }
        });

        StackPane.setAlignment(panel, Pos.TOP_CENTER);
        StackPane.setMargin(panel, new Insets(96, 0, 0, 0));

        getChildren().addAll(scrim, panel);
    }

    public void showPalette() {
        setVisible(true);
        setManaged(true);
        query.clear();
        filter("");
        list.getSelectionModel().selectFirst();
        query.requestFocus();
        if (getScene() != null) {
            getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, sceneKeys);
        }
        Platform.runLater(query::requestFocus);
    }

    public void hidePalette() {
        setVisible(false);
        setManaged(false);
        if (getScene() != null) {
            getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, sceneKeys);
        }
    }

    /** 场景级键盘处理：不依赖输入框是否拿到焦点。 */
    private void handleKey(javafx.scene.input.KeyEvent event) {
        if (!isVisible()) {
            return;
        }
        switch (event.getCode()) {
            case ESCAPE -> {
                hidePalette();
                event.consume();
            }
            case ENTER -> {
                runSelected();
                event.consume();
            }
            case DOWN -> {
                list.getSelectionModel().selectNext();
                event.consume();
            }
            case UP -> {
                list.getSelectionModel().selectPrevious();
                event.consume();
            }
            default -> { }
        }
    }

    public boolean isShowing() {
        return isVisible();
    }

    private void filter(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        // ⭐ X5：**不要**把禁用的动作过滤掉。
        //    以前只显示 enabled 的动作 → 「复制 SELECT」这类只在选中对象时才有意义的动作
        //    **在 ⌘K 里根本搜不到** —— 用户不知道这个能力存在。
        //    现在：禁用的也显示（灰掉），点它会给出解释（Action.onDisabled）。
        List<Action> matches = actions.all().stream()
                .filter(action -> needle.isEmpty()
                        || action.label().toLowerCase(Locale.ROOT).contains(needle)
                        || action.id().toLowerCase(Locale.ROOT).contains(needle)
                        || action.category().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Action::category).thenComparing(Action::label))
                .toList();
        list.setItems(FXCollections.observableArrayList(matches));
        if (!matches.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
    }

    private void runSelected() {
        Action action = list.getSelectionModel().getSelectedItem();
        if (action == null) {
            return;
        }
        hidePalette();
        action.run();
    }

    private final class ActionCell extends ListCell<Action> {
        @Override
        protected void updateItem(Action action, boolean empty) {
            super.updateItem(action, empty);
            if (empty || action == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            Label category = new Label(action.category());
            category.getStyleClass().add("adb-palette__category");
            category.setMinWidth(64);

            Label label = new Label(action.label());
            label.getStyleClass().add("adb-palette__label");
            if (!action.isEnabled()) {
                // ⭐ 禁用项在面板里**灰掉但仍可见**（可发现性 > 好看）。
                label.getStyleClass().add("adb-palette__label--disabled");
            }

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label accelerator = new Label(action.accelerator() == null
                    ? "" : action.accelerator().getDisplayText());
            accelerator.getStyleClass().add("adb-palette__accelerator");

            HBox box = new HBox(10, category, label, spacer, accelerator);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(4, 6, 4, 6));

            setText(null);
            setGraphic(box);
        }
    }
}
