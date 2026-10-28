package io.agenticdb.ui.shell;

import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ⭐ 对象功能标签条（§6.2）。
 *
 * <p>一个表对象在 IDE 里不只有「数据」一面：结构、索引、外键、触发器、SQL
 * 都是同一个对象的不同切面。旧版把它们拆成**不同的标签页**（结构是另一个
 * 标签），用户在标签栏里来回跳，看不出这几个页面说的是同一个表。
 *
 * <p>这里给出的是对象内部的切面条：当前切面用**文字变主色 + 底部 2px 指示线**
 * 标出，其余降到二级色 —— 克制，但一眼可辨。
 *
 * <p>切面是**动态**的：调用方只注册当前对象真的有的切面。
 */
public final class ObjectTabBar extends HBox {

    private final Map<String, Label> tabs = new LinkedHashMap<>();
    private Consumer<String> onSelect = id -> { };
    private String selected;

    public ObjectTabBar() {
        getStyleClass().add("adb-object-tabs");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(2);
    }

    /**
     * 注册一个切面。
     *
     * @param id    内部标识
     * @param label 显示文案
     */
    public void addTab(String id, String label) {
        Label tab = new Label(label);
        tab.setId("object-tab-" + id);
        tab.getStyleClass().add("adb-object-tab");
        tab.setAccessibleText(label);
        // ⭐ 切面名是整个对象的主导航，**不许被压缩成「触…」**。空间不够时
        //    由标题栏去缩写辅助信息（连接名），而不是让每一个切面都只剩一个字。
        tab.setMinWidth(Region.USE_PREF_SIZE);
        // ⭐ P7：切面以前只有鼠标能点（Label 默认不可聚焦）—— 键盘用户进不了
        //    结构 / 概览 / 数据这些内部切面。补 Tab 停靠点、TAB_ITEM 角色、
        //    Enter/Space 选中、左右方向键切换。
        tab.setFocusTraversable(true);
        tab.setAccessibleRole(AccessibleRole.TAB_ITEM);
        tab.setOnMouseClicked(event -> {
            tab.requestFocus();
            select(id);
        });
        tab.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                select(id);
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT) {
                stepFocus(id, event.getCode() == KeyCode.LEFT ? -1 : 1);
                event.consume();
            }
        });
        tabs.put(id, tab);
        getChildren().add(tab);
    }

    /** 方向键切换切面：焦点与选中一起走（Tab 控件的惯例）。 */
    private void stepFocus(String id, int delta) {
        List<String> order = new ArrayList<>(tabs.keySet());
        int index = order.indexOf(id);
        if (index < 0) {
            return;
        }
        String next = order.get(Math.floorMod(index + delta, order.size()));
        tabs.get(next).requestFocus();
        select(next);
    }

    /** 切面切换回调。 */
    public void setOnSelect(Consumer<String> listener) {
        onSelect = listener == null ? id -> { } : listener;
    }

    /** 选中某个切面（不触发回调 —— 供程序化同步用）。 */
    public void setSelected(String id) {
        if (id == null || !tabs.containsKey(id)) {
            return;
        }
        selected = id;
        tabs.forEach((key, tab) -> {
            boolean active = key.equals(id);
            tab.getStyleClass().removeAll("adb-object-tab--active");
            if (active) {
                tab.getStyleClass().add("adb-object-tab--active");
            }
        });
    }

    /** 当前选中的切面 id。 */
    public String selected() {
        return selected;
    }

    /** 用户点了某个切面。 */
    public void select(String id) {
        if (!tabs.containsKey(id)) {
            return;
        }
        setSelected(id);
        onSelect.accept(id);
    }
}
