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
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);
    private final IntegerProperty dirtyCount = new SimpleIntegerProperty(0);

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
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.target = target == null ? title : target;
        this.connectionId = connectionId;
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

    public void markDirty(int count) {
        dirtyCount.set(count);
        dirty.set(count > 0);
    }
}
