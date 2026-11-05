package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ H5-a（R66 余账）：输入框的提示语必须**整句读得完**。
 *
 * <p>帧证（{@code p5-connections}，2026-11-05 20:29 从当前树重拍）：连接管理页那颗搜索框
 * 上屏的是 {@code Search name, host, databa} —— 句子在中间被切断，而且 TextField 的
 * 提示语裁剪**不带省略号**（不像 Label 会补「…」），用户读到的是半句话，
 * 没有任何线索告诉他「后面还有字」。
 *
 * <p>⚠️ 口径一：宽度类断言读**上屏后的计算值**，CSS 里写了多宽不算证据
 * （同族先例 D-50 / UX-5 / D-60）。这里量的是节点实际宽度，
 * 与「同一颗字体把整句提示语排出来要多宽」相比 —— 字体从节点自己读，不另配一份。
 *
 * <p>⚠️ 口径二：**换语言必须重建页面**。提示语是构造期一次 {@code setPromptText} 写进去的，
 * 构造完再 {@code I18n.setLocale} 不会回头改那颗节点 —— 第一版就这么循环两种语言，
 * 量到的是同一句英文排了两遍，第二条断言完全是装饰（[[aidb-mutation-proofs-expose-fixture-blindspots]]
 * 的「遍历口径」那一族）。现在每个语言各建一次页。
 */
@ExtendWith(ApplicationExtension.class)
class FieldPromptFitTest {

    private StackPane root;
    private Scene scene;

    @Start
    void start(Stage stage) {
        root = new StackPane();
        scene = new Scene(root, 1280, 800);
        URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        assertThat(theme).as("theme-light.css").isNotNull();
        assertThat(appCss).as("app.css").isNotNull();
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        // ⚠️ 主题类必须挂在根节点，否则 -adb-* 令牌与字体档位都解析不出来，
        //    量到的宽度是「Modena 默认」而不是界面上的真实宽度（假绿）。
        root.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * 两种语言都得读完：英文是帧上那一档，中文是另一档（字数更少但每字更宽）。
     * 一次跑完再断言，这样两个数都能进失败信息，不会被第一条红遮住第二条。
     */
    @Test
    void theConnectionsSearchPromptFitsInEveryLocale() {
        List<String> tooNarrow = new ArrayList<>();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
            I18n.setLocale(locale);
            try {
                Object[] read = onFx(() -> {
                    root.getChildren().setAll(buildPage());
                    root.applyCss();
                    root.layout();
                    TextField search = (TextField) root.lookup("#connections-search");
                    assertThat(search).as("搜索框必须存在").isNotNull();
                    String prompt = search.getPromptText();
                    Text probe = new Text(prompt);
                    probe.setFont(search.getFont());
                    Insets padding = search.getPadding();
                    return new Object[]{search.getWidth(),
                            probe.getLayoutBounds().getWidth() + padding.getLeft() + padding.getRight(),
                            prompt};
                });
                double width = (Double) read[0];
                double needed = (Double) read[1];
                String prompt = (String) read[2];
                assertThat(width)
                        .as("%s 档提示语「%s」要整句放得下（需 %.0fpx，实得 %.0fpx）",
                                locale, prompt, needed, width)
                        .isGreaterThanOrEqualTo(needed);
            } catch (AssertionError e) {
                tooNarrow.add(e.getMessage());
            } finally {
                I18n.setLocale(I18n.DEFAULT_LOCALE);
            }
        }
        assertThat(tooNarrow)
                .as("TextField 的提示语裁剪不补省略号 = 用户读到半句话（H5-a）")
                .isEmpty();
    }

    /** 每个语言各建一次页 —— 提示语在构造期写死，换语言不重建就量不到新句子。 */
    private ConnectionsPage buildPage() {
        ObservableList<ConnectionProfile> profiles = FXCollections.observableArrayList(
                ConnectionProfile.newProfile("Demo", DialectKind.SQLITE, "", 0, "demo.db", ""));
        return new ConnectionsPage(profiles,
                () -> { }, profile -> { }, profile -> { }, profile -> { },
                profiles::remove, profile -> false, new DataGridPage.Feedback() {
                    @Override
                    public void status(String message) {
                    }

                    @Override
                    public void range(String text) {
                    }

                    @Override
                    public void duration(long millis) {
                    }
                });
    }

    private <T> T onFx(Callable<T> action) {
        try {
            return WaitForAsyncUtils.asyncFx(action).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
