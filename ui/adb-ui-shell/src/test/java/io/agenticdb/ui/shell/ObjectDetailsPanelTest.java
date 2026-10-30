package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ §6 右侧对象详情面板的门禁。
 *
 * <h2>守什么</h2>
 * 这个面板的全部价值在于「**看一眼就知道这表怎么回事**」，
 * 而它最容易出的两种事故都是**静默**的：
 * <ol>
 *   <li><b>编造数据</b> —— 拿不到引擎 / 行数就填个像真的值，
 *       用户拿它做判断，比空着危险得多；</li>
 *   <li><b>串对象</b> —— 从 A 表切到 B 表，A 的字段摘要还挂在屏幕上。</li>
 * </ol>
 * 所以这里的断言几乎全是「这里<b>不</b>该出现什么」。
 *
 * <p>⚠️ 场景图靠 {@link #withClass} 手工遍历，不用 {@code lookupAll}：
 * 后者要求节点已经挂进 Scene，而这个面板的测试故意不建窗口
 * （建了反而慢，而且测的是面板不是外壳）。
 */
@ExtendWith(ApplicationExtension.class)
class ObjectDetailsPanelTest {

    private static final ObjectDetailsPanel.Subject USERS =
            new ObjectDetailsPanel.Subject("demo", "agenticdb-demo.db", "users",
                    ObjectKind.TABLE, "");

    private static final ObjectDetailsPanel.Subject ORDERS =
            new ObjectDetailsPanel.Subject("demo", "agenticdb-demo.db", "orders",
                    ObjectKind.TABLE, "");

    /** 直接同步喂数据的 loader —— 面板不该知道线程模型。 */
    private static ObjectDetailsPanel.Loader immediate(ObjectDetailsPanel.Snapshot snapshot) {
        return (subject, onLoaded, onError) -> onLoaded.accept(snapshot);
    }

    private static ObjectDetailsPanel.Loader failing(String message) {
        return (subject, onLoaded, onError) -> onError.accept(message);
    }

    private static ObjectDetailsPanel.Snapshot snapshot(
            ObjectDetailsPanel.Subject subject, List<ObjectDetailsPanel.Fact> facts,
            List<String> columns, List<String> notes) {
        return new ObjectDetailsPanel.Snapshot(subject, facts, columns,
                List.of(), List.of(), List.of(), List.of(), notes);
    }

    private static List<javafx.scene.Node> withClass(javafx.scene.Parent root, String styleClass) {
        List<javafx.scene.Node> found = new ArrayList<>();
        collect(root, styleClass, found);
        return found;
    }

    private static void collect(javafx.scene.Node node, String styleClass,
                                List<javafx.scene.Node> found) {
        if (node.getStyleClass().contains(styleClass)) {
            found.add(node);
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            // ⚠️ ScrollPane 的内容不在 getChildren() 里（归 skin 管）——
            //    不特判就会把整栏内容漏掉，然后误以为面板什么都没渲染。
            collect(scroll.getContent(), styleClass, found);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collect(child, styleClass, found);
            }
        }
    }

    private static List<String> textsOf(List<javafx.scene.Node> nodes) {
        return nodes.stream()
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .toList();
    }

    private static String firstText(ObjectDetailsPanel panel, String styleClass) {
        List<javafx.scene.Node> nodes = withClass(panel, styleClass);
        assertThat(nodes).as("找不到样式类 %s", styleClass).isNotEmpty();
        return ((javafx.scene.control.Labeled) nodes.getFirst()).getText();
    }

    // ─────────────────────────── 空态 ───────────────────────────

    @Test
    void startsOnAnEmptyStateWithActionsDisabled() {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        assertThat(panel.subject()).isNull();
        assertThat(withClass(panel, "adb-details-panel__empty")).isNotEmpty();
        assertThat(buttons(panel).stream()
                .filter(button -> button.getText() != null && !button.getText().isBlank())
                .allMatch(javafx.scene.control.Button::isDisable)).isTrue();
    }

    @Test
    void closeButtonHidesThePanel() {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        buttons(panel).stream()
                .filter(button -> button.getStyleClass().contains("adb-details-panel__close"))
                .findFirst()
                .ifPresent(javafx.scene.control.Button::fire);
        assertThat(panel.isVisible()).isFalse();
    }

    private static List<javafx.scene.control.Button> buttons(ObjectDetailsPanel panel) {
        List<javafx.scene.control.Button> found = new ArrayList<>();
        collectButtons(panel, found);
        return found;
    }

    private static void collectButtons(javafx.scene.Node node,
                                       List<javafx.scene.control.Button> found) {
        if (node instanceof javafx.scene.control.Button button) {
            found.add(button);
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            collectButtons(scroll.getContent(), found);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectButtons(child, found);
            }
        }
    }

    // ─────────────────────────── 真实数据 ───────────────────────────

    @Test
    void showsRealFactsAndNeverInventedOnes() throws Exception {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        panel.setLoader(immediate(snapshot(USERS,
                List.of(new ObjectDetailsPanel.Fact("名称", "users"),
                        new ObjectDetailsPanel.Fact("类型", "表"),
                        // ⭐ 这一行就是「不编造」的判据：引擎为 null。
                        new ObjectDetailsPanel.Fact("引擎", null),
                        new ObjectDetailsPanel.Fact("行数", "200")),
                List.of("id INTEGER · 主键", "name TEXT · 非空"),
                List.of())));

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(USERS)).get();
        WaitForAsyncUtils.waitForFxEvents();

        List<String> values = new ArrayList<>();
        for (javafx.scene.Node line : withClass(panel, "adb-details-panel__fact")) {
            values.add(((javafx.scene.control.Labeled)
                    ((javafx.scene.layout.Pane) line).getChildren().get(1)).getText());
        }
        assertThat(values).containsExactly("users", "表", "—", "200");
        assertThat(values).doesNotContain("InnoDB", "utf8mb4_general_ci", "未知");
        // 字段摘要用的是真实列名，不是占位文本。
        assertThat(textsOf(withClass(panel, "adb-details-panel__list-item")))
                .startsWith("id INTEGER · 主键", "name TEXT · 非空");
    }

    @Test
    void unsupportedMetadataIsExplainedNotFaked() throws Exception {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        panel.setLoader(immediate(snapshot(USERS, List.of(), List.of(),
                List.of("该数据库不提供「数据长度 / 引擎 / 排序规则 / 创建时间」，已省略对应项。"))));

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(USERS)).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(textsOf(withClass(panel, "adb-details-panel__note")))
                .anyMatch(text -> text.contains("不提供"));
    }

    // ─────────────────────────── 最近操作（P2-3） ───────────────────────────

    @Test
    void recentQueriesForTheObjectAreListedOrExplainedAsEmpty() throws Exception {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        panel.setLoader(immediate(new ObjectDetailsPanel.Snapshot(USERS,
                List.of(new ObjectDetailsPanel.Fact("名称", "users")), List.of(), List.of(),
                List.of(), List.of(), List.of("11:20 · SELECT * FROM users;"), List.of())));

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(USERS)).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(textsOf(withClass(panel, "adb-details-panel__section-title")))
                .contains(I18n.t("grid.overview.recent") + " 1");
        assertThat(textsOf(withClass(panel, "adb-details-panel__list-item")))
                .contains("11:20 · SELECT * FROM users;");

        // 没有记录时也要说明「还没有」，而不是整节消失。
        ObjectDetailsPanel fresh = new ObjectDetailsPanel();
        fresh.setLoader(immediate(snapshot(USERS,
                List.of(new ObjectDetailsPanel.Fact("名称", "users")), List.of(), List.of())));
        WaitForAsyncUtils.asyncFx(() -> fresh.showObject(USERS)).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(textsOf(withClass(fresh, "adb-details-panel__section-title")))
                .contains(I18n.t("grid.overview.recent"));
        assertThat(textsOf(withClass(fresh, "adb-details-panel__list-item")))
                .anyMatch(text -> text.equals(I18n.t("details.recent.none")));
    }

    // ─────────────────────────── 切换对象不串内容 ───────────────────────────    @Test
    void switchingObjectsLeavesNoStaleContent() throws Exception {
        AtomicReference<ObjectDetailsPanel.Subject> asked = new AtomicReference<>();
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        panel.setLoader((subject, onLoaded, onError) -> {
            asked.set(subject);
            onLoaded.accept(snapshot(subject,
                    List.of(new ObjectDetailsPanel.Fact("名称", subject.name())),
                    List.of(subject.name() + ".col"), List.of()));
        });

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(USERS)).get();
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.asyncFx(() -> panel.showObject(ORDERS)).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(asked.get()).isEqualTo(ORDERS);
        assertThat(firstText(panel, "adb-details-panel__title")).isEqualTo("orders");
        // 上一张表的字段摘要不许残留。
        assertThat(textsOf(withClass(panel, "adb-details-panel__list-item")))
                .containsExactly("orders.col");
    }

    /** 加载失败的回复必须落到被问的那个对象上，不能落到后来选中的对象上。 */
    @Test
    void aLateErrorDoesNotOverwriteTheCurrentObject() throws Exception {
        List<Consumer<ObjectDetailsPanel.Snapshot>> pending = new ArrayList<>();
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        panel.setLoader((subject, onLoaded, onError) -> pending.add(onLoaded));

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(USERS)).get();
        WaitForAsyncUtils.asyncFx(() -> panel.showObject(ORDERS)).get();

        // users 的读取才回来 —— 这时屏幕上已经是 orders 了。
        WaitForAsyncUtils.asyncFx(() -> pending.getFirst().accept(
                snapshot(USERS, List.of(new ObjectDetailsPanel.Fact("名称", "users")),
                        List.of(), List.of()))).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(firstText(panel, "adb-details-panel__title")).isEqualTo("orders");
    }

    @Test
    void loadFailureShowsTheRealReason() throws Exception {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        panel.setLoader(failing("连接未建立，读不到 users 的元数据"));

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(USERS)).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(firstText(panel, "adb-error")).contains("连接未建立");
    }

    // ─────────────────────────── 连接一级（§6.2） ───────────────────────────

    @Test
    void connectionSubjectHasNoObjectActions() throws Exception {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        ObjectDetailsPanel.Subject connection =
                ObjectDetailsPanel.Subject.connection("demo", "agenticdb-demo.db");
        panel.setLoader(immediate(snapshot(connection,
                List.of(new ObjectDetailsPanel.Fact("数据库", "agenticdb-demo.db")),
                List.of(), List.of())));

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(connection)).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(connection.typeLabel()).isEqualTo(I18n.t("category.database"));
        assertThat(withClass(panel, "adb-details-panel__empty")).isEmpty();
        // 连接没有「打开数据 / 打开结构」—— 这两个按钮必须是禁用的。
        assertThat(buttons(panel).stream()
                .filter(button -> I18n.t("action.object.open-data").equals(button.getText())
                        || I18n.t("action.object.open-structure").equals(button.getText()))
                .allMatch(javafx.scene.control.Button::isDisable)).isTrue();
    }

    // ─────────────────────────── 纯函数 ───────────────────────────

    @Test
    void columnLineMarksPrimaryKeyAndNotNull() {
        assertThat(ObjectDetailsPanel.columnLine("id", "INTEGER", true, false))
                .isEqualTo("id INTEGER · " + I18n.t("detail.primaryKey")
                        + " / " + I18n.t("grid.head.notNull"));
        assertThat(ObjectDetailsPanel.columnLine("bio", "TEXT", false, true))
                .isEqualTo("bio TEXT");
        // 可空、无类型的列 → 只有名字，不补任何装饰。
        assertThat(ObjectDetailsPanel.columnLine("email", null, false, true))
                .isEqualTo("email");
    }

    @Test
    void widthBoundsMatchTheSpec() {
        assertThat(ObjectDetailsPanel.WIDTH_DEFAULT).isBetween(260, 320);
        assertThat(ObjectDetailsPanel.WIDTH_MIN).isEqualTo(220);
        assertThat(ObjectDetailsPanel.WIDTH_MAX).isEqualTo(420);
    }

    @Test
    void everyObjectKindHasALabel() {
        for (ObjectKind kind : ObjectKind.values()) {
            ObjectDetailsPanel.Subject subject = new ObjectDetailsPanel.Subject(
                    "demo", "db", "x", kind, "");
            assertThat(subject.typeLabel()).isNotBlank();
        }
        assertThat(ObjectDetailsPanel.Subject.connection("demo", "db").typeLabel())
                .isEqualTo(I18n.t("category.database"));
    }

    @Test
    void snapshotCopiesDefensively() {
        List<String> columns = new ArrayList<>(List.of("a"));
        ObjectDetailsPanel.Snapshot snapshot = new ObjectDetailsPanel.Snapshot(
                USERS, List.of(), columns, List.of(), List.of(), List.of(), List.of(), List.of());
        columns.add("b");
        assertThat(snapshot.columns()).containsExactly("a");
    }

    @Test
    void kindIsOnlyNullForDatabaseLevel() {
        assertThat(ObjectDetailsPanel.Subject.connection("demo", "db").isObject()).isFalse();
        assertThat(new ObjectDetailsPanel.Subject("demo", "db", "users",
                ObjectKind.TABLE, "").isObject()).isTrue();
        assertThat(new ObjectDetailsPanel.Subject("demo", "db", "v",
                SchemaService.ObjectKind.VIEW, "").isObject()).isTrue();
    }

    @Test
    void nullSnapshotFallsBackToEmptyState() throws Exception {
        ObjectDetailsPanel panel = new ObjectDetailsPanel();
        panel.setLoader(immediate(null));

        WaitForAsyncUtils.asyncFx(() -> panel.showObject(USERS)).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(withClass(panel, "adb-details-panel__empty")).isNotEmpty();
    }
}
