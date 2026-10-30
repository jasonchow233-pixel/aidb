package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试隔离门禁：偏好必须是**每个测试一份**，且不能碰到真实用户偏好。
 *
 * <p>守的是 {@link IsolatedPreferencesExtension}。它不是「顺手加的卫生习惯」：
 * 在它之前，三个门禁（密度行高 / 密度菜单勾选 / 详情面板默认关闭）在
 * {@code clean verify} 里红、单独跑却绿，而全绿的那几轮是靠开发机真实偏好里
 * 恰好存着 {@code ui.density=normal} —— 门禁的结果取决于机器历史。
 *
 * <p>三条门禁分工：
 * <ol>
 *   <li>{@link #aTestRunsOnItsOwnPreferencesNode}：节点真的被隔离了（守「扩展没注册」）；</li>
 *   <li>{@link #whatATestWritesNeverReachesTheRealUserPreferences}：真的不落进
 *       真实偏好（守「隔离只做了半截」）；</li>
 *   <li>{@link #consecutiveTestsGetFreshNodeNames}：节点名每次都不同
 *       （守「复用名字 = 把上一个测试的状态带过来」）；</li>
 *   <li>{@link #theExtensionIsRegisteredForEveryTestInTheModule}：注册文件在
 *       （守「类写了但没挂上，等于没写」）。</li>
 * </ol>
 */
class PreferencesIsolationTest {

    @Test
    void aTestRunsOnItsOwnPreferencesNode() {
        assertThat(System.getProperty(IsolatedPreferencesExtension.PROPERTY))
                .as("测试必须跑在独立偏好节点上 —— 否则读到的默认值取决于上一次跑过什么")
                .isNotNull()
                .startsWith(IsolatedPreferencesExtension.PREFIX);
    }

    @Test
    void whatATestWritesNeverReachesTheRealUserPreferences() {
        String probe = "ui.isolation.probe." + System.nanoTime();
        new SettingsStore().set(probe, "written");

        Preferences real = Preferences.userNodeForPackage(SettingsStore.class);
        assertThat(real.get(probe, null))
                .as("跑测试不能把开发机上的真实 AIDB 偏好改掉")
                .isNull();
    }

    @Test
    void consecutiveTestsGetFreshNodeNames() {
        String first = IsolatedPreferencesExtension.freshNodeName();
        String second = IsolatedPreferencesExtension.freshNodeName();

        assertThat(first)
                .as("复用同一个节点名 = 上一个测试写的东西被下一个测试原样读到")
                .isNotEqualTo(second);
    }

    /**
     * 类写了但没注册，等于没写 —— ServiceLoader 不会报错，只会安静地什么都不做。
     * 这正是第十八轮那五个「扫到 0 个文件却显示绿」的另一种形态。
     */
    @Test
    void theExtensionIsRegisteredForEveryTestInTheModule() throws Exception {
        String file = "META-INF/services/org.junit.jupiter.api.extension.Extension";
        Path path = Path.of("src/test/resources", file);

        assertThat(path)
                .as("缺少 %s，JUnit 不会加载隔离扩展（它不会报错，只是不生效）", file)
                .exists();
        assertThat(Files.readString(path))
                .contains(IsolatedPreferencesExtension.class.getName());
    }

    /**
     * ⭐ 上面那条还不够：本仓库里 Jupiter 的扩展自动探测**默认是关着的**
     * （surefire 3.5.4 下的实测：写了 services 文件、ServiceLoader 也能枚举到它，
     * 但 {@code postProcessTestInstance} 一次都不触发）。要靠
     * {@code junit-platform.properties} 显式打开 —— 少了这一行，整套隔离
     * 静默失效，而所有门禁照样绿。
     */
    @Test
    void autoDetectionIsSwitchedOnExplicitly() throws Exception {
        Path path = Path.of("src/test/resources/junit-platform.properties");

        assertThat(path)
                .as("缺少 junit-platform.properties，扩展自动探测在本构建里默认是关的")
                .exists();
        assertThat(Files.readString(path))
                .contains("junit.jupiter.extensions.autodetection.enabled=true");
    }
}
