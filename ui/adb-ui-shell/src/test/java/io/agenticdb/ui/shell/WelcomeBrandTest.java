package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ S-5 门禁：**欢迎页门面必须挂 AIDB 自己的 mark**。
 *
 * <h2>为什么单独一条</h2>
 * 品牌改造（§28.9）把 Logo 做出来了、也注册到了 OS 窗口图标上，但**界面里一处都没用** ——
 * 欢迎页那张图还是 {@code Icons.Icon.DATABASE}，一个所有数据库工具都在画的通用圆柱。
 * 「做了 Logo 但界面看不见」这类缺口，光看代码提交记录发现不了，只能靠断言钉住。
 *
 * <h2>守三件事</h2>
 * <ol>
 *   <li>门面上那张图是<b>品牌 PNG</b>（{@code ImageView} + {@code app-icon-*}），
 *       不是通用矢量图标（{@code SVGPath}）—— 换回通用图标这里就红。</li>
 *   <li>资源真的在 classpath 上 —— 缺图时 {@code getResource} 返回 null，
 *       而 {@code brandMark()} 是<b>抛异常</b>不是静默降级（第十八轮数过五个
 *       「失败被设计成静默降级」的坑），这条保证它不该抛的时候不抛。</li>
 *   <li>尺寸按 {@code Icons.SIZE_BRAND} 定成正方 —— 拉变形同样一眼看得出来。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class WelcomeBrandTest {

    @Test
    void welcomePageShowsTheBrandMarkNotAGenericDatabaseIcon() {
        WorkspaceView workspace = onFx(() -> new WorkspaceView(
                new ActionRegistry(), "演示库",
                tab -> null, tab -> null, tab -> null, tab -> null, tab -> null));

        Node logo = onFx(() -> workspace.lookup(".adb-welcome__logo"));

        assertThat(logo)
                .as("欢迎页必须有一张门面图")
                .isNotNull();
        assertThat(logo)
                .as("门面要用品牌 PNG —— 通用矢量圆柱（SVGPath）看一眼认不出这是 AIDB")
                .isNotInstanceOf(SVGPath.class)
                .isInstanceOf(ImageView.class);

        ImageView mark = (ImageView) logo;
        assertThat(mark.getImage())
                .as("图必须真的加载出来（url 断掉时 JavaFX 不报错，只是空白）")
                .isNotNull();
        assertThat(mark.getImage().getUrl())
                .as("用的是品牌 PNG，不是随便一张图")
                .contains("app-icon");
        awaitImageLoaded(mark.getImage());
        assertThat(mark.getImage().isError())
                .as("品牌 PNG 解码失败（文件在，但是坏的）—— 界面上只是一块空白")
                .isFalse();
        assertThat(mark.getImage().getWidth())
                .as("解码出来的宽度不能是 0")
                .isGreaterThan(0);
        assertThat(logo.getAccessibleText())
                .as("读屏软件念出来的是产品名，不是「连接」")
                .isEqualTo("AIDB");
    }

    @Test
    void theBrandMarkResourceIsOnTheClasspath() {
        assertThat(WorkspaceView.class.getResource(WorkspaceView.BRAND_MARK))
                .as("品牌 mark 资源缺失 —— brandMark() 会抛异常而不是拿通用图标顶替，"
                        + "所以这个文件必须一直在（改名前先看这里）")
                .isNotNull();
    }

    @Test
    void theBrandMarkIsSquareAtTheBrandSize() {
        Node mark = onFx(WorkspaceView::brandMark);

        assertThat(mark).isInstanceOf(ImageView.class);
        ImageView view = (ImageView) mark;
        assertThat(view.getFitWidth())
                .as("门面图按品牌尺寸定宽（与侧栏/工具栏那套尺寸同源）")
                .isEqualTo(Icons.SIZE_BRAND);
        assertThat(view.getFitHeight()).isEqualTo(Icons.SIZE_BRAND);
        assertThat(view.isPreserveRatio())
                .as("品牌 mark 是方的 —— 关掉等比会被拉变形")
                .isTrue();
    }

    /**
     * 等图片真正解码完（JavaFX 是后台加载的，上来就断言会取到半成品）。
     *
     * <p>headless 下 Image 的后台线程照样跑，这里给 5 秒；
     * 真的加载不出来（坏文件）时 {@code progress} 停在 0 或 {@code isError} 变 true，
     * 由调用方的断言去说清楚，而不是在这里超时糊过去。
     */
    private static void awaitImageLoaded(javafx.scene.image.Image image) {
        try {
            WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS,
                    () -> image.isError() || image.getProgress() >= 1.0);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("品牌 PNG 5 秒还没加载完 —— headless 环境下"
                    + " Image 的后台线程可能没起来，这条要先查环境再查代码", e);
        }
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
