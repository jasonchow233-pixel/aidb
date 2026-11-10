package io.agenticdb.ui.shell;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;

/** 一个标签的独立状态。⛔ 绝不放全局单例（否则标题与内容会串台）。 */
public final class TabItem {

    private final String id;
    private final String title;
    private final TabKind kind;
    private final String target;
    private final String connectionId;
    /**
     * 已经构建好的页面（表清单这类「自己带内容」的标签页）。
     *
     * <p>平时是 {@code null} —— 由 {@code WorkspaceView} 按 {@code target} 现建。
     */
    private final javafx.scene.Node view;
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);
    private final IntegerProperty dirtyCount = new SimpleIntegerProperty(0);
    /**
     * ⭐ Q4：固定标签。与 {@link #dirty} 一样是<b>每个标签自己的状态</b>，不放全局集合 ——
     * 全局集合会和 {@code TabBar} 的 {@code items} 顺序各说一套，重排时必然串台。
     *
     * <p>固定 = 不参与「关闭 / 关闭其他 / 关闭全部」，且排在常驻标签之后、普通标签之前。
     * 这不是「不可关闭」的另一种写法：常驻标签是系统给的入口，固定标签是用户自己钉住的。
     */
    private final BooleanProperty pinned = new SimpleBooleanProperty(false);

    public TabItem(String id, String title, TabKind kind) {
        this(id, title, kind, title);
    }

    /**
     * @param target 页面真正操作的对象（表名等）。标题可以被改写，target 不行 ——
     *               页面工厂必须靠它取数据，不能从标题反解析。
     */
    public TabItem(String id, String title, TabKind kind, String target) {
        this(id, title, kind, target, null);
    }

    /**
     * @param connectionId 该标签属于哪个连接；{@code null} 表示设置/工具等全局标签。
     *                     多连接下页面工厂按它取会话，避免不同连接的页共享一个全局 session。
     */
    public TabItem(String id, String title, TabKind kind, String target, String connectionId) {
        this(id, title, kind, target, connectionId, null);
    }

    /** 带现成页面的标签页（内容不由 WorkspaceView 构建）。 */
    public TabItem(String id, String title, TabKind kind, String target, String connectionId,
                   javafx.scene.Node view) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.target = target == null ? title : target;
        this.connectionId = connectionId;
        this.view = view;
    }

    /** 现成的页面；没有就是 {@code null}。 */
    public javafx.scene.Node view() {
        return view;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public TabKind kind() {
        return kind;
    }

    public String target() {
        return target;
    }

    public String connectionId() {
        return connectionId;
    }

    public BooleanProperty dirtyProperty() {
        return dirty;
    }

    public IntegerProperty dirtyCountProperty() {
        return dirtyCount;
    }

    /** ⭐ Q4：固定状态（供关闭钮的可见性直接绑定，切状态不用重建标签节点）。 */
    public BooleanProperty pinnedProperty() {
        return pinned;
    }

    public boolean isPinned() {
        return pinned.get();
    }

    public void setPinned(boolean value) {
        pinned.set(value);
    }

    public void markDirty(int count) {
        dirtyCount.set(count);
        dirty.set(count > 0);
    }
}
