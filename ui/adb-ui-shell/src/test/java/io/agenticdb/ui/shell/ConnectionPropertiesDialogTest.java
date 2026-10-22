package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U3 门禁：连接属性页。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>形态</b>：必须是「**弹窗里套 TabPane**」（常规 / SSL / 高级）——
 *       做成标签页会占掉主工作区；做成一屏表单则十几个字段挤在一起
 *       （裁定见 {@code docs/UI-FORMS-AND-STATES.md} §4）；</li>
 *   <li><b>映射</b>：填的值要真的进 {@code ConnectionProfile.options()}，
 *       且「附加参数」框里的自由参数也要进去（那是这个页最有用的部分）。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class ConnectionPropertiesDialogTest {

    private static ConnectionProfile profile(Map<String, String> options) {
        return new ConnectionProfile("id", "shop", DialectKind.MYSQL, "db.local", 3306,
                "shop", "root", false, 0, options);
    }

    /** 弹窗必须在 FX 线程上创建（JavaFX 的硬性要求）。 */
    private static ConnectionPropertiesDialog create(Map<String, String> options) throws Exception {
        AtomicReference<ConnectionPropertiesDialog> holder = new AtomicReference<>();
        WaitForAsyncUtils.asyncFx(() ->
                holder.set(ConnectionPropertiesDialog.create(profile(options)))).get();
        return holder.get();
    }

    @Test
    void isADialogWithATabPaneInside() throws Exception {
        ConnectionPropertiesDialog dialog = create(Map.of());

        assertThat(dialog.dialog())
                .as("连接属性必须是**弹窗**（不是标签页、不是一屏表单）")
                .isNotNull();
        assertThat(dialog.dialog().getDialogPane().getContent())
                .as("弹窗内部要用 TabPane 分区")
                .isInstanceOf(TabPane.class);
        TabPane tabs = (TabPane) dialog.dialog().getDialogPane().getContent();
        assertThat(tabs.getTabs()).extracting(tab -> tab.getText())
                .containsExactly("常规", "SSL", "高级");
    }

    @Test
    void collectsTimeoutCharsetAndExtraParameters() throws Exception {
        ConnectionPropertiesDialog dialog = create(Map.of());

        WaitForAsyncUtils.asyncFx(() -> {
            dialog.queryTimeoutField().setText("45");
            dialog.charsetField().setText("UTF-8");
            dialog.extraField().setText("serverTimezone=Asia/Shanghai\nuseUnicode=true");
        }).get();

        Map<String, String> collected = dialog.collect();
        assertThat(collected)
                .as("语句超时由 JdbcSession 消费；其余作为 JDBC 属性传给驱动")
                .containsEntry(ConnectionPropertiesDialog.QUERY_TIMEOUT, "45")
                .containsEntry(ConnectionPropertiesDialog.CHARACTER_ENCODING, "UTF-8")
                .containsEntry("serverTimezone", "Asia/Shanghai")
                .containsEntry("useUnicode", "true");
    }

    @Test
    void loadsExistingOptionsBackIntoTheFields() throws Exception {
        ConnectionPropertiesDialog dialog = create(Map.of(
                ConnectionPropertiesDialog.QUERY_TIMEOUT, "15",
                ConnectionPropertiesDialog.CHARACTER_ENCODING, "GBK",
                "customKey", "customValue"));

        assertThat(dialog.queryTimeoutField().getText()).isEqualTo("15");
        assertThat(dialog.charsetField().getText()).isEqualTo("GBK");
        assertThat(dialog.extraField().getText())
                .as("自己管理的键不进「附加参数」框，其余原样回填")
                .contains("customKey=customValue")
                .doesNotContain("queryTimeout");
    }

    @Test
    void blankFieldsAreNotWritten() throws Exception {
        ConnectionPropertiesDialog dialog = create(Map.of());

        Map<String, String> collected = dialog.collect();

        assertThat(collected)
                .as("没填的项不该写进去（空字符串会变成 JDBC 属性）")
                .doesNotContainKey(ConnectionPropertiesDialog.QUERY_TIMEOUT)
                .doesNotContainKey(ConnectionPropertiesDialog.CHARACTER_ENCODING);
    }
}
