package io.agenticdb.desktop;

import io.agenticdb.ui.App;
import javafx.application.Application;

/**
 * 打包入口。与 JavaFX 的 Application 子类分开，
 * 这样 jpackage / 无模块启动时不会踩「JavaFX runtime components are missing」。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
