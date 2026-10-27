package io.agenticdb.ui.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U3（2026-10-26）门禁：桌面启动器的渲染标志。
 *
 * <p>JavaFX 默认 {@code prism.lcdtext=true}（LCD 子像素抗锯齿）。12–13px 的中文笔画
 * 因此带上橙/蓝子像素边 —— p2-users 列头、p5-connections 右上文案放大 4x 后满屏彩边。
 * 修法只有一行，且**必须在 {@code Application.launch} 之前**：Prism 管线在 launch
 * 那一刻定型，之后改属性没有用。这行落在测试照不到的 bootstrap 里，
 * 所以被人「整理启动参数」时删掉不会有任何门禁响 —— 本类就是补这个洞。
 */
@DisplayName("桌面启动器：灰度抗锯齿必须在 launch 之前定型")
class LaunchFlagsAuditTest {

    @Test
    void antialiasingIsSwitchedToGrayBeforeJavaFXStarts() throws IOException {
        Path launcher = repositoryRoot().resolve(
                "bootstrap/adb-desktop/src/main/java/io/agenticdb/desktop/Launcher.java");
        assertThat(launcher)
                .as("找不到桌面启动器 —— 静态审计扫到 0 个文件就是假绿")
                .exists();

        String source = Files.readString(launcher);
        int setFlag = source.indexOf("System.setProperty(\"prism.lcdtext\", \"false\")");
        int launch = source.indexOf("Application.launch(");

        assertThat(setFlag)
                .as("U3 的落点没了：Launcher 必须显式把 prism.lcdtext 设成 false，"
                        + "否则 12px 中文在 LCD 子像素下带橙蓝边")
                .isNotNegative();
        assertThat(launch)
                .as("Launcher 里找不到 Application.launch —— 启动链改了形态，本门禁的先后断言失效")
                .isNotNegative();
        assertThat(setFlag)
                .as("必须在 launch **之前**设：Prism 管线在 launch 时定型，之后设属性不生效")
                .isLessThan(launch);
        assertThat(source)
                .as("要保留外部覆盖（if (getProperty == null) 再设）—— "
                        + "-Dprism.lcdtext=true 是唯一的 A/B 对比手段，无条件 set 会把它吃掉")
                .contains("System.getProperty(\"prism.lcdtext\")");
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("bootstrap"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("从 " + System.getProperty("user.dir")
                + " 往上找不到仓库根（需要 pom.xml + bootstrap/）");
    }
}
