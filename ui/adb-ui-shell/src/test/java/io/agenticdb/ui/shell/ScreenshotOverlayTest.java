package io.agenticdb.ui.shell;

import io.agenticdb.ui.shell.ScreenshotSession.Overlay;
import javafx.scene.Group;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R57 画面债：浮层合成 —— 补全弹层不在主场景里，快门要把它叠回去。
 *
 * <h2>为什么单独钉</h2>
 * {@code ScreenshotSession} 原来只有两条出口：弹窗是独立 Stage 就改拍弹窗，否则拍主窗口。
 * 补全弹层两条都不对 —— 它是 PopupWindow，改拍它会得到一张只有一列候选的小图
 * （证不了「候选贴在光标下面」），拍主窗口则整个弹层不在图里。
 * 第三条出口是「主场景 + 按坐标合成的浮层」，而<b>坐标合成最容易出的一种坏是悄悄偏</b>：
 * 图有 1280×800、字节数正常、看着也像，只是浮层贴在错的位置。
 * 所以这里钉的是像素级的位置与 alpha，而不是「跑没跑起来」。
 */
class ScreenshotOverlayTest {

    private static BufferedImage flat(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        java.util.Arrays.fill(row, argb);
        for (int y = 0; y < height; y++) {
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    @Test
    void anOpaqueOverlayReplacesTheCanvasWhereItCovers() {
        BufferedImage canvas = flat(6, 4, 0xFF102030);
        ScreenshotSession.blend(canvas, flat(2, 2, 0xFFEE0000), 1, 1);

        assertThat(canvas.getRGB(1, 1)).as("覆盖区内应换成浮层的像素").isEqualTo(0xFFEE0000);
        assertThat(canvas.getRGB(2, 2)).isEqualTo(0xFFEE0000);
        assertThat(canvas.getRGB(0, 0)).as("覆盖区外一格都不该动").isEqualTo(0xFF102030);
        assertThat(canvas.getRGB(3, 1)).as("右边界之外一格都不该动").isEqualTo(0xFF102030);
        assertThat(canvas.getRGB(1, 3)).as("下边界之外一格都不该动").isEqualTo(0xFF102030);
    }

    @Test
    void fullyTransparentPixelsKeepTheCanvas() {
        BufferedImage canvas = flat(4, 4, 0xFF102030);
        // 弹层那颗节点是带圆角与阴影的：四角是纯透明，中间才是不透明。
        ScreenshotSession.blend(canvas, flat(2, 2, 0x00EE0000), 0, 0);

        assertThat(canvas.getRGB(0, 0)).as("alpha=0 不许把底色擦成黑").isEqualTo(0xFF102030);
        assertThat(canvas.getRGB(1, 1)).isEqualTo(0xFF102030);
    }

    @Test
    void semiTransparentOverlayBlendsInsteadOfGoingOpaque() {
        BufferedImage canvas = flat(3, 3, 0xFF00FF00);
        ScreenshotSession.blend(canvas, flat(1, 1, 0x80FF0000), 0, 0);

        int blended = canvas.getRGB(0, 0);
        assertThat(blended >>> 24).as("两层都有实体，结果仍是不透明").isEqualTo(255);
        assertThat(blended >> 16 & 0xFF).as("一半红压在绿底上：红通道取到中间值，不是顶满的 255")
                .isBetween(120, 136);
        assertThat(blended >> 8 & 0xFF).as("绿通道被稀释一半左右").isBetween(120, 136);
        assertThat(blended & 0xFF).as("蓝两头都没有，不该被合成凭空抬起来").isZero();
    }

    @Test
    void anOverlayHangingOffTheCanvasIsClippedNotThrown() {
        BufferedImage canvas = flat(4, 4, 0xFF102030);

        // 光标快贴到编辑器右缘 / 下缘时都会出现：负坐标与超出画布的宽高。
        ScreenshotSession.blend(canvas, flat(4, 4, 0xFFFF0000), -2, -2);
        ScreenshotSession.blend(canvas, flat(4, 4, 0xFFFF0000), 2, 2);
        assertThat(canvas.getRGB(0, 0)).isEqualTo(0xFFFF0000);
        assertThat(canvas.getRGB(3, 3)).isEqualTo(0xFFFF0000);
        assertThat(canvas.getRGB(0, 3)).as("没被任何一层盖到的角落保持原样").isEqualTo(0xFF102030);
    }

    @Test
    void theOverlayRecordCarriesANodeAndSceneCoordinates() {
        Group node = new Group();
        Overlay overlay = new Overlay(node, 128.4, 240.6);

        assertThat(overlay.node()).isSameAs(node);
        // 坐标是「主场景里的左上角」，不是屏幕绝对值 —— 快门那侧按它取整合成。
        assertThat(overlay.x()).isEqualTo(128.4);
        assertThat(overlay.y()).isEqualTo(240.6);
    }
}
