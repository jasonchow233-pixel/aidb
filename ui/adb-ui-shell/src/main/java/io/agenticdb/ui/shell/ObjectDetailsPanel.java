package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ⭐ 右侧「对象详情」面板（规格书 §6）。
 *
 * <h2>它解决什么问题</h2>
 * 以前 AgenticDB 只有左右两栏：左边一棵对象树，中间一张对象清单。
 * 用户在清单里选中一张表，想知道「这表几列、几个索引、多少行」，
 * 必须**再双击打开一次**才能看到 —— 只是「看一眼概况」这个动作，
 * 代价是一次完整的页面跳转。
 *
 * <p>Navicat 的解法是右侧一列窄面板：选中对象，概览就在手边，
 * 但不抢主工作区的空间。本类照这个思路实现。
 *
 * <h2>三条硬规则</h2>
 * <ol>
 *   <li><b>不编造数据</b>：拿不到的元数据显示「—」并说明原因，
 *       绝不放一个看起来像真的占位值。SQLite 本来就没有
 *       「引擎」「排序规则」「创建时间」，这里就老老实实没有。</li>
 *   <li><b>默认关闭</b>：它是有用的补充，不是应该常驻的第三栏。
 *       用户按需打开（⌥⌘D / 视图菜单），宽度可拖、可整体隐藏。</li>
 *   <li><b>只做展示</b>：这个类不认识 {@code DbSession}、不认识
 *       {@code SchemaService} —— 数据由 {@link Loader} 从外面喂进来，
 *       于是它可以在无数据库的单测里跑。</li>
 * </ol>
 */
public final class ObjectDetailsPanel extends VBox {

    /** 面板宽度的三个档位（规格 §6：260–320，夹在 220–420 之间）。 */
    public static final int WIDTH_DEFAULT = 300;
    public static final int WIDTH_MIN = 220;
    public static final int WIDTH_MAX = 420;

    /** 当前在看哪个对象。{@code kind == null} 表示看的是连接 / 库整体。 */
    public record Subject(String connectionId, String database, String name,
                          ObjectKind kind, String schema) {

        /** 连接 / 库一级的主题：右侧栏按 §6.2 展示数据库概览。 */
        public static Subject connection(String connectionId, String database) {
            return new Subject(connectionId, database, database, null, "");
        }

        public boolean isObject() {
            return kind != null;
        }

        public String typeLabel() {
            return kind == null ? I18n.t("category.database") : kindLabel(kind);
        }

        private static String kindLabel(ObjectKind kind) {
            return switch (kind) {
                case TABLE -> I18n.t("detail.kind.table");
                case VIEW -> I18n.t("detail.kind.view");
                case TRIGGER -> I18n.t("object.kind.trigger");
                case FUNCTION -> I18n.t("object.kind.function");
                case PROCEDURE -> I18n.t("details.kind.procedure");
                case EVENT -> I18n.t("object.kind.event");
            };
        }
    }

    /** 一行「标签 : 值」。值可以是 {@code null} —— 显示成「—」。 */
    public record Fact(String label, String value) {
        public String valueText() {
            return value == null || value.isBlank() ? "—" : value;
        }
    }

    /**
     * 面板要显示的全部内容。
     *
     * @param facts       基本信息（表名 / 类型 / 所属库 / 行数 / 字段数…）
     * @param columns     字段摘要，每行一列；空列表 = 这一节不显示
     * @param indexes     索引摘要
     * @param foreignKeys 外键摘要
     * @param triggers    触发器摘要
     * @param recent      该对象最近被执行的 SQL（P2-3），最新在前
     * @param notes       备注 / 警告（例如「SQLite 不提供引擎信息」）
     */
    public record Snapshot(Subject subject, List<Fact> facts, List<String> columns,
                           List<String> indexes, List<String> foreignKeys,
                           List<String> triggers, List<String> recent, List<String> notes) {

        public Snapshot {
            facts = facts == null ? List.of() : List.copyOf(facts);
            columns = columns == null ? List.of() : List.copyOf(columns);
            indexes = indexes == null ? List.of() : List.copyOf(indexes);
            foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
            recent = recent == null ? List.of() : List.copyOf(recent);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * 数据提供者。
     *
     * <p>实现方负责把读元数据丢到后台线程，然后在 FX 线程回调
     * {@code onLoaded} / {@code onError}。面板自己不知道线程模型。
     */
    public interface Loader {
        void load(Subject subject, Consumer<Snapshot> onLoaded, Consumer<String> onError);
    }

    /** 面板里的操作入口。 */
    public interface Actions {
        void openData(Subject subject);

        void openStructure(Subject subject);

        void openDdl(Subject subject);

        void refresh(Subject subject);
    }

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Button close = new Button();
    private final VBox facts = new VBox(4);
    private final VBox sections = new VBox(12);
    private final VBox notes = new VBox(4);
    // U15：动词与代码、与其他入口统一成「打开」——「查看数据 / 打开表」说的是同一件事，
    // 却给了用户两个词，让人怀疑是两个去处。
    private final Button openData = new Button(I18n.t("action.object.open-data"));
    private final Button openStructure = new Button(I18n.t("action.object.open-structure"));
    private final Button openDdl = new Button(I18n.t("details.openDdl"));
    private final Button refresh = new Button();
    private final Label empty = new Label(I18n.t("details.empty"));
    private final Label loading = new Label(I18n.t("details.loading"));
    private final Label error = new Label();

    private Loader loader = (subject, onLoaded, onError) -> onLoaded.accept(null);
    private Actions actions = new Actions() {
        @Override
        public void openData(Subject subject) {
        }

        @Override
        public void openStructure(Subject subject) {
        }

        @Override
        public void openDdl(Subject subject) {
        }

        @Override
        public void refresh(Subject subject) {
        }
    };
    /** 当前主题；空态 / 加载态也按它决定要不要显示操作按钮。 */
    private Subject subject;

    public ObjectDetailsPanel() {
        getStyleClass().add("adb-details-panel");
        setMinWidth(WIDTH_MIN);
        setPrefWidth(WIDTH_DEFAULT);
        setMaxWidth(WIDTH_MAX);

        title.getStyleClass().add("adb-details-panel__title");
        subtitle.getStyleClass().add("adb-details-panel__subtitle");
        subtitle.setWrapText(true);

        close.setGraphic(Icons.of(Icons.Icon.CLOSE, Icons.SIZE_LABEL));
        close.getStyleClass().addAll("adb-icon-button", "adb-details-panel__close");
        close.setTooltip(new javafx.scene.control.Tooltip(I18n.t("details.closeTip")));
        close.setAccessibleText(I18n.t("details.closeTip"));
        close.setOnAction(event -> setVisible(false));

        HBox header = new HBox(8, title, subtitle, close);
        header.getStyleClass().add("adb-details-panel__header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(subtitle, Priority.ALWAYS);
        // 标题不许被挤压 —— 表名是这一栏唯一「我在看谁」的线索。
        title.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        subtitle.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        facts.getStyleClass().add("adb-details-panel__facts");
        sections.getStyleClass().add("adb-details-panel__sections");
        notes.getStyleClass().add("adb-details-panel__notes");

        openData.getStyleClass().addAll("adb-button", "adb-button--ghost");
        openData.setOnAction(event -> actions.openData(subject));
        openStructure.getStyleClass().addAll("adb-button", "adb-button--ghost");
        openStructure.setOnAction(event -> actions.openStructure(subject));
        openDdl.getStyleClass().addAll("adb-button", "adb-button--ghost");
        openDdl.setOnAction(event -> actions.openDdl(subject));
        refresh.setGraphic(Icons.of(Icons.Icon.REFRESH, Icons.SIZE_LABEL));
        refresh.getStyleClass().addAll("adb-icon-button", "adb-details-panel__refresh");
        refresh.setTooltip(new javafx.scene.control.Tooltip(I18n.t("details.refreshTip")));
        refresh.setAccessibleText(I18n.t("details.refreshAccessible"));
        refresh.setOnAction(event -> reload());

        // ⭐ FlowPane 不是 HBox：英文标签比中文长一截（Open Structure vs 打开结构），
        //    三等分的窄栏会把三颗全截成「Open D…」—— p2-details 实拍抓到。
        //    装不下就换行，字必须读得完。
        FlowPane footer = new FlowPane(4, 4, openData, openStructure, openDdl, refresh);
        footer.getStyleClass().add("adb-details-panel__footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        empty.getStyleClass().addAll("adb-details-panel__empty", "adb-muted");
        empty.setWrapText(true);
        loading.getStyleClass().addAll("adb-details-panel__loading", "adb-muted");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        ScrollPane scroll = new ScrollPane(new VBox(facts, sections));
        scroll.getStyleClass().add("adb-details-panel__scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll, notes, error, footer);
        showEmpty();
    }

    // ─────────────────────────── 配置 ───────────────────────────

    public void setLoader(Loader loader) {
        this.loader = loader == null ? this.loader : loader;
    }

    public void setActions(Actions actions) {
        this.actions = actions == null ? this.actions : actions;
    }

    public Subject subject() {
        return subject;
    }

    // ─────────────────────────── 状态 ───────────────────────────

    /** 没有选中对象：空态，操作按钮全禁用。 */
    public void showEmpty() {
        this.subject = null;
        title.setText(I18n.t("details.title"));
        subtitle.setText("");
        facts.getChildren().clear();
        sections.getChildren().clear();
        notes.getChildren().clear();
        hideError();
        sections.getChildren().add(empty);
        setActionsEnabled(false);
    }

    /** 开始读某个对象：先给轻量加载态，读完后由 {@link #show} 落地。 */
    public void showObject(Subject target) {
        if (target == null) {
            showEmpty();
            return;
        }
        this.subject = target;
        title.setText(target.name());
        subtitle.setText(target.typeLabel());
        facts.getChildren().clear();
        sections.getChildren().clear();
        notes.getChildren().clear();
        hideError();
        sections.getChildren().add(loading);
        setActionsEnabled(target.isObject());
        loader.load(target, snapshot -> {
            if (target.equals(this.subject)) {
                show(snapshot == null ? emptySnapshot(target) : snapshot);
            }
        }, message -> {
            if (target.equals(this.subject)) {
                showError(message);
            }
        });
    }

    /** 什么都没读到的快照 —— 只留对象身份，不填任何假值。 */
    private static Snapshot emptySnapshot(Subject subject) {
        return new Snapshot(subject, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    /** 把读到的内容渲染出来。 */
    public void show(Snapshot snapshot) {
        if (snapshot == null) {
            showEmpty();
            return;
        }
        this.subject = snapshot.subject();
        title.setText(snapshot.subject().name());
        subtitle.setText(snapshot.subject().typeLabel());
        hideError();

        facts.getChildren().clear();
        for (Fact fact : snapshot.facts()) {
            Label key = new Label(fact.label());
            key.getStyleClass().add("adb-details-panel__fact-key");
            Label value = new Label(fact.valueText());
            value.getStyleClass().add("adb-details-panel__fact-value");
            // ⭐ 值要能选中、能复制 —— 用户经常要把库名 / 路径复制到别处。
            value.setWrapText(true);
            HBox line = new HBox(8, key, value);
            line.getStyleClass().add("adb-details-panel__fact");
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(value, Priority.ALWAYS);
            facts.getChildren().add(line);
        }

        sections.getChildren().clear();
        // 什么都没读到（加载失败 / 驱动不返回）时保持空态 ——
        // 这时挂一节「最近操作」会假装对象有内容，反而误导。
        boolean hasContent = !snapshot.facts().isEmpty() || !snapshot.columns().isEmpty()
                || !snapshot.indexes().isEmpty() || !snapshot.foreignKeys().isEmpty()
                || !snapshot.triggers().isEmpty() || !snapshot.recent().isEmpty();
        if (hasContent) {
            addSection(I18n.t("newtable.tab.fields"), snapshot.columns());
            addSection(I18n.t("detail.indexes"), snapshot.indexes());
            addSection(I18n.t("detail.foreignKeys"), snapshot.foreignKeys());
            addSection(I18n.t("detail.triggers"), snapshot.triggers());
            // ⭐ P2-3：对象一级始终给「最近操作」一节 —— 没有记录时说明「还没有」，
            //    而不是整节消失，否则用户永远不知道这里能看按表的执行历史。
            if (snapshot.subject().isObject()) {
                addRecentSection(snapshot.recent());
            }
        } else {
            sections.getChildren().add(empty);
        }

        notes.getChildren().clear();
        for (String note : snapshot.notes()) {
            Label label = new Label(note);
            label.getStyleClass().add("adb-details-panel__note");
            label.setWrapText(true);
            notes.getChildren().add(label);
        }
        setActionsEnabled(snapshot.subject().isObject());
    }

    public void showError(String message) {
        error.setText(message == null || message.isBlank() ? I18n.t("details.readFailed") : message);
        error.setVisible(true);
        error.setManaged(true);
    }

    public void hideError() {
        error.setVisible(false);
        error.setManaged(false);
    }

    /** 重新读当前对象。 */
    public void reload() {
        if (subject != null) {
            showObject(subject);
        }
    }

    private void setActionsEnabled(boolean enabled) {
        openData.setDisable(!enabled);
        openStructure.setDisable(!enabled);
        openDdl.setDisable(!enabled);
    }

    private void addSection(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Label heading = new Label(title + " " + items.size());
        heading.getStyleClass().add("adb-details-panel__section-title");
        VBox list = new VBox(2);
        list.getStyleClass().add("adb-details-panel__list");
        for (String item : items) {
            Label label = new Label(item);
            label.getStyleClass().add("adb-details-panel__list-item");
            label.setWrapText(true);
            list.getChildren().add(label);
        }
        VBox section = new VBox(4, heading, list);
        section.getStyleClass().add("adb-details-panel__section");
        sections.getChildren().add(section);
    }

    private void addRecentSection(List<String> items) {
        String recentTitle = I18n.t("grid.overview.recent");
        Label heading = new Label(items.isEmpty() ? recentTitle : recentTitle + " " + items.size());
        heading.getStyleClass().add("adb-details-panel__section-title");
        VBox list = new VBox(2);
        list.getStyleClass().add("adb-details-panel__list");
        if (items.isEmpty()) {
            Label none = new Label(I18n.t("details.recent.none"));
            none.getStyleClass().addAll("adb-details-panel__list-item", "adb-muted");
            none.setWrapText(true);
            list.getChildren().add(none);
        } else {
            for (String item : items) {
                Label label = new Label(item);
                label.getStyleClass().add("adb-details-panel__list-item");
                label.setWrapText(true);
                list.getChildren().add(label);
            }
        }
        VBox section = new VBox(4, heading, list);
        section.getStyleClass().add("adb-details-panel__section");
        sections.getChildren().add(section);
    }

    /** 供测试与调用方：字段摘要行的格式。 */
    static String columnLine(String name, String type, boolean primaryKey, boolean nullable) {
        StringBuilder line = new StringBuilder(name);
        if (type != null && !type.isBlank()) {
            line.append(' ').append(type);
        }
        List<String> marks = new ArrayList<>();
        if (primaryKey) {
            marks.add(I18n.t("detail.primaryKey"));
        }
        if (!nullable) {
            marks.add(I18n.t("grid.head.notNull"));
        }
        if (!marks.isEmpty()) {
            line.append(" · ").append(String.join(" / ", marks));
        }
        return line.toString();
    }
}
