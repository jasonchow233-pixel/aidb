package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * ⭐ EXT（P3-1）：首次启动引导浮层 —— 叠加在 demo 自动连接<b>之上</b>，关后即焚。
 *
 * <p>v4 修过一次「欢迎页被对象首页盖掉」：这里的浮层不替代任何工作区页面，
 * 只是盖在最上层的 {@code StackPane} overlay；关掉后下面的 demo 库原样可见。
 *
 * <p>「看过」写进 {@link SettingsStore}（{@code ui.onboarding.seen}）；
 * Help 菜单的「重新打开欢迎引导」走 {@link #reopenGuide()}，不看这个标志。
 *
 * <p>样式复用命令面板那套浮层件（{@code adb-palette} / {@code adb-palette__scrim} / 通用
 * 排版类）—— 引导页和 ⌘K 是同一种「临时盖住工作区」的表面，不另造一套视觉。
 *
 * <p>⚠️ 外层容器刻意<b>不</b>叫 {@code adb-palette-overlay}：命令面板的门禁按
 * {@code .adb-palette-overlay} 单点查询节点，两个浮层共用同一个类会让查询歧义。
 * 这里用 {@code adb-onboarding-overlay} —— 只是一条同名规则的别名，视觉完全一致。
 */
final class OnboardingOverlay extends StackPane {

    private static final String SEEN_KEY = "ui.onboarding.seen";

    private final SettingsStore settings;
    private final Runnable onOpenDemo;

    OnboardingOverlay(SettingsStore settings, Runnable onOpenDemo) {
        this.settings = settings;
        this.onOpenDemo = onOpenDemo == null ? () -> { } : onOpenDemo;

        setId("onboarding-overlay");
        getStyleClass().add("adb-onboarding-overlay");
        setVisible(false);
        setManaged(false);

        Region scrim = new Region();
        scrim.getStyleClass().add("adb-palette__scrim");
        scrim.setOnMouseClicked(event -> dismiss());

        Label title = new Label(I18n.t("onboarding.title"));
        title.getStyleClass().add("adb-empty__title");
        Label subtitle = new Label(I18n.t("onboarding.subtitle"));
        subtitle.getStyleClass().add("adb-muted");
        subtitle.setWrapText(true);

        HBox cards = new HBox(12,
                card(Icons.Icon.DATABASE, "onboarding.card.sql", "onboarding.card.sqlHint"),
                card(Icons.Icon.LIST, "onboarding.card.redis", "onboarding.card.redisHint"),
                card(Icons.Icon.FOLDER, "onboarding.card.objectstore",
                        "onboarding.card.objectstoreHint"));
        cards.setAlignment(Pos.CENTER);

        Button demo = new Button(I18n.t("onboarding.openDemo"));
        demo.setId("onboarding-open-demo");
        demo.getStyleClass().addAll("adb-button", "adb-button--primary");
        demo.setOnAction(event -> {
            onOpenDemo.run();
            dismiss();
        });

        Button close = new Button(I18n.t("onboarding.close"));
        close.setId("onboarding-close");
        close.getStyleClass().addAll("adb-button");
        close.setOnAction(event -> dismiss());

        Label shortcut = new Label(I18n.t("onboarding.shortcutHint"));
        shortcut.getStyleClass().add("adb-muted");

        HBox actions = new HBox(8, demo, close);
        actions.setAlignment(Pos.CENTER);
        VBox panel = new VBox(16, title, subtitle, cards, actions, shortcut);
        panel.setId("onboarding-panel");
        panel.getStyleClass().add("adb-palette");
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(28));
        panel.setMaxWidth(720);

        getChildren().addAll(scrim, panel);
    }

    private static Node card(Icons.Icon icon, String titleKey, String hintKey) {
        Node glyph = Icons.of(icon, Icons.SIZE_EMPTY);
        Label title = new Label(I18n.t(titleKey));
        title.getStyleClass().add("adb-page__title");
        Label hint = new Label(I18n.t(hintKey));
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);
        hint.setMaxWidth(190);
        VBox box = new VBox(8, glyph, title, hint);
        box.getStyleClass().add("adb-type-tile");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(14));
        box.setMinSize(200, 150);
        return box;
    }

    /** 首启调用：没看过才显示。 */
    void maybeShow() {
        if (!settings.getBoolean(SEEN_KEY, false)) {
            showGuide();
        }
    }

    /** Help 菜单「重新打开欢迎引导」：无论看过与否都显示。 */
    void reopenGuide() {
        showGuide();
    }

    private void showGuide() {
        setVisible(true);
        setManaged(true);
        toFront();
    }

    /** 关后即焚：写入「看过」并撤出布局。包内可见是给行为门禁直接驱动用的。 */
    void dismiss() {
        settings.setBoolean(SEEN_KEY, true);
        setVisible(false);
        setManaged(false);
    }
}
