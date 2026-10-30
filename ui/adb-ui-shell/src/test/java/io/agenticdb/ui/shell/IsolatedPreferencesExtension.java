package io.agenticdb.ui.shell;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;

/**
 * 每个测试方法跑在**自己的**偏好节点上。
 *
 * <p>为什么要有这个东西（第二十二轮实测出来的）：{@code java.util.prefs} 是
 * 进程级的共享可变状态，而 surefire 把一整个模块的测试类都塞进同一个 JVM。
 * 于是 {@code AppShell} 在构造时读到的 {@code ui.density} / {@code ui.details.visible}
 * 取决于**排在它前面跑过的测试留下过什么**：
 * <ul>
 *   <li>{@code DensityCoverageTest} 把四档都走一遍，最后一档停在 {@code dense}
 *       → 后面 {@code ActionRegistryConvergenceTest} 的「标准 28px」读到 22px；</li>
 *   <li>同一个类里前一个方法 {@code details.toggle} 打开详情面板没关
 *       → 后一个方法断言「默认关闭」读到「开着」。</li>
 * </ul>
 * 结果是三个门禁在 {@code clean verify} 里红、单独跑又绿 —— 而它们在记录
 * 「754 / 0 失败」的那台机器上是绿的，只因为那台机器的真实偏好里恰好存着
 * {@code ui.density=normal} 与 {@code ui.details.visible=false}。
 * **门禁绿不绿取决于跑它的人上一次用过什么**，这不是门禁，这是抽奖。
 *
 * <p>顺带修掉一件同样真的事：以前跑一次 {@code mvn verify} 会把开发机上的
 * 真实 AIDB 偏好（侧栏宽度 / 密度 / 主题 / 详情面板）改掉好几轮。
 *
 * <p>为什么是 {@code TestInstancePostProcessor} 而不是 {@code BeforeEachCallback}：
 * TestFX 的 {@code ApplicationExtension} 在 {@code beforeEach} 里才 {@code @Start}
 * 起应用，而应用一构造就把偏好读进内存了 —— 晚一步就等于没隔离。
 * {@code TestInstancePostProcessor} 严格早于所有 {@code beforeEach}，
 * 且**不受扩展注册顺序影响**（声明式的 TestFX 扩展先注册也无所谓）。
 */
public class IsolatedPreferencesExtension
        implements TestInstancePostProcessor, AfterEachCallback {

    /** 与 {@link SettingsStore} 读的那个系统属性同名。 */
    static final String PROPERTY = "adb.prefs.node";

    /** 测试节点统一放在这个前缀下，方便一眼看出来源。 */
    static final String PREFIX = "io/agenticdb/tests/";

    private static final String KEY_NODE = "adb.isolated.node";
    private static final String KEY_PREVIOUS = "adb.isolated.previous";
    private static final String ABSENT = "";
    private static final AtomicLong SEQ = new AtomicLong();

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(IsolatedPreferencesExtension.class);

    /**
     * 每次都给一个没用过的节点名。
     *
     * <p>复用名字就等于把上一个测试写进去的状态原封不动交给下一个测试 ——
     * 那正是要防的东西，所以这个函数本身也要单独守着（见
     * {@code PreferencesIsolationTest}）。
     */
    static String freshNodeName() {
        return PREFIX + "t" + SEQ.incrementAndGet();
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
        String node = freshNodeName();
        // null 不能进 ExtensionContext.Store，用空串代表「本来没设过」。
        context.getStore(NAMESPACE).put(KEY_NODE, node);
        context.getStore(NAMESPACE).put(KEY_PREVIOUS, System.getProperty(PROPERTY, ABSENT));
        System.setProperty(PROPERTY, node);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String node = context.getStore(NAMESPACE).get(KEY_NODE, String.class);
        String previous = context.getStore(NAMESPACE).get(KEY_PREVIOUS, String.class);
        if (ABSENT.equals(previous)) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previous);
        }
        if (node != null) {
            // 不删的话 102 个测试类会在磁盘上留 102 个垃圾节点。
            try {
                Preferences.userRoot().node(node).removeNode();
            } catch (Exception ignored) {
                // 清理失败不影响测试结论：隔离是靠节点名，不是靠删得干净。
            }
        }
    }
}
