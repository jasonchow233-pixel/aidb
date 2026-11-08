package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ D-85 第一族：快门对「摆过浮层却拿不到」的帧必须弃帧，不许静默出图。
 *
 * <p>定性时量出来的两个事实各钉一条：摆态登记过取数口 = 这一帧「该有浮层」
 * （行为侧，直接可测）；快门本体在拍摄会话里 {@code System.exit}，进不了单元测试，
 * 所以守门的两条结构性事实（先弃帧后落盘、没摆过的帧零等待）按 D-71/D-74 先例
 * 用源码扫描钉住 —— 锚点一律用判断式本身，不写裸开关名
 * （注释里有同名词，按裸名找位置会恒过，D-98 那轮量过的自证条款族）。
 */
class ScreenshotOverlayGuardTest {

    @Test
    void settingAnOverlayProviderMarksTheFrameAsOwningAnOverlay() {
        try {
            ScreenshotSession.setOverlayProvider(() -> null);
            assertThat(ScreenshotSession.isOverlayPosedForTest())
                    .as("登记过取数口（哪怕它此刻返回 null）= 这一帧该有浮层，快门要守")
                    .isTrue();
            ScreenshotSession.setOverlayProvider(null);
            assertThat(ScreenshotSession.isOverlayPosedForTest())
                    .as("清掉取数口 = 不该有浮层的帧，快门不该为它等、也不该为它弃")
                    .isFalse();
        } finally {
            ScreenshotSession.setOverlayProvider(null);
        }
    }

    @Test
    void theShutterDiscardsAPosedFrameWhoseOverlayVanished() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ScreenshotSession.java"));

        int guard = source.indexOf("overlayPosed && overlay == null");
        assertThat(guard)
                .as("快门必须先问「该有浮层吗 + 拿到了吗」—— 这是 D-37 口径的第五个出口："
                        + "文件名写着补全、画面里却没有弹层，比缺帧更贵")
                .isPositive();
        int abort = source.indexOf("abortMatrixFrame", guard);
        int write = source.indexOf("ImageIo.write", guard);
        assertThat(abort)
                .as("拿不到摆过的浮层必须走弃帧出口，不是照常 targetScene 出图")
                .isPositive();
        assertThat(abort)
                .as("弃帧必须发生在落盘之前 —— 先写再弃等于没弃")
                .isLessThan(write);
    }

    @Test
    void theWorkerWaitsForAPosedOverlayBeforeTheShutter() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/ScreenshotSession.java"));

        int awaitCall = source.indexOf("awaitPosedOverlay();");
        assertThat(awaitCall)
                .as("worker 在按快门前要先等浮层登记得上（PopOver 的 skin 是异步挂的，"
                        + "满负载下同一拍问可能是 null）")
                .isPositive();
        int capture = source.indexOf("runOnFx(() -> capture(stage");
        assertThat(awaitCall)
                .as("等待必须排在按快门之前")
                .isLessThan(capture);

        int definition = source.indexOf("private static void awaitPosedOverlay()");
        assertThat(definition).isPositive();
        int earlyReturn = source.indexOf("if (!overlayPosed)", definition);
        int loop = source.indexOf("for (int attempt", definition);
        assertThat(earlyReturn)
                .as("没摆过浮层的帧必须零等待直通 —— 其余帧不该为这条门多花时间")
                .isPositive();
        assertThat(loop).isPositive();
        assertThat(earlyReturn)
                .as("早退必须在等待循环之前，否则每张帧都白等")
                .isLessThan(loop);
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("core"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}