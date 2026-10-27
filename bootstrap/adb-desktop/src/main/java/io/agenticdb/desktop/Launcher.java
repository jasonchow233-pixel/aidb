package io.agenticdb.desktop;

import io.agenticdb.ui.App;
import javafx.application.Application;

/**
 * 打包入口。与 JavaFX 的 Application 子类分开，
 * 这样 jpackage / 无模块启动时不会踩「JavaFX runtime components are missing」。
 *
 * <p>⭐ U3（2026-10-26）：{@code main} 必须在 launch 之前把 {@code prism.lcdtext}
 * 设成 false。JavaFX 默认走 LCD 子像素抗锯齿，12–13px 的中文笔画会带上橙/蓝子像素边，
 * 实拍放大 4x 后满屏彩边（p2-users 列头、p5-connections 右上文案）。Prism 管线在
 * {@code Application.launch} 那一刻定型，之后改属性没有用；保留外部覆盖，
 * {@code -Dprism.lcdtext=true} 仍可临时打开做 A/B 对比。
 * 这一行由 {@code LaunchFlagsAuditTest} 钉住（它读源码文本，断言先后顺序）。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (System.getProperty("prism.lcdtext") == null) {
            System.setProperty("prism.lcdtext", "false");
        }
        Application.launch(App.class, args);
    }
}
