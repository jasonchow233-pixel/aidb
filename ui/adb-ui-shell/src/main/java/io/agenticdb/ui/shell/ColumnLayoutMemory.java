package io.agenticdb.ui.shell;

import javafx.animation.PauseTransition;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.Duration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 清单页的列宽记忆（表清单 / 对象首页共用）。
 *
 * <p>Navicat 的表清单会记住用户拖过的列宽。没有记忆时，每次切回清单都要
 * 重新把「注释 / 排序规则」拖宽 —— 而这两列恰恰最需要宽一点。
 *
 * <p>宽度按 {@code scope}（连接 id）分开存：同一个连接的不同库可能列宽习惯
 * 不同，也不该把 A 库的布局带到 B 库。列的身份来自 {@code TableColumn.userData}，
 * 不能用列标题 —— 标题会改，列身份不会。
 *
 * <p>⚠️ 只保存**布局完成后的宽度**（{@code width > 0}）；持久化本身有 400ms
 * 防抖，拖列时不会每帧写一次偏好。
 */
final class ColumnLayoutMemory {

    private static final Duration SAVE_DELAY = Duration.millis(400);

    /** 用户把列拖得再宽，也不该让一列吃掉整屏。 */
    private static final double MAX_WIDTH = 1600;

    private final TableView<?> table;
    private final SettingsStore settings;
    private final Supplier<String> scope;
    private final String prefix;
    /** 构造时的列宽 = 这个连接的默认值；切到没有记忆的连接时要还原它们。 */
    private final Map<String, Double> defaults = new LinkedHashMap<>();

    private PauseTransition saveTimer;
    private String restoredScope;
    private String pendingScope;
    private Map<String, Integer> pendingWidths = Map.of();

    /**
     * @param prefix 偏好键前缀（如 {@code ui.tablelist}），两页各用各的命名空间
     * @param scope  连接 id 的供应器；{@code null} / 空白 = 不持久化
     */
    ColumnLayoutMemory(TableView<?> table, SettingsStore settings,
                       Supplier<String> scope, String prefix) {
        this.table = table;
        this.settings = settings;
        this.scope = scope;
        this.prefix = prefix;
        for (TableColumn<?, ?> column : table.getColumns()) {
            String key = columnKey(column);
            if (key != null) {
                defaults.put(key, column.getPrefWidth());
            }
        }
        track();
        restoreIfScopeChanged();
    }

    /**
     * 当前连接的列宽恢复一次。
     *
     * <p>对象首页是一个**常驻实例**，切连接时页面不重建 —— 所以不能只在
     * 构造时恢复；每次 render 都要问一句「连接变了吗」。同一连接内的重载
     * 不重复覆盖，否则用户刚拖好的宽度会被旧偏好顶回去。
     */
    void restoreIfScopeChanged() {
        String current = currentScope();
        if (current == null || current.equals(restoredScope)) {
            return;
        }
        restoredScope = current;
        for (TableColumn<?, ?> column : table.getColumns()) {
            String key = columnKey(column);
            if (key == null) {
                continue;
            }
            int saved = settings.getInt(key(current, key), -1);
            double width = saved > 0
                    ? Math.min(saved, MAX_WIDTH)
                    : defaults.getOrDefault(key, column.getPrefWidth());
            // 没有记忆的连接要回到默认宽 —— 否则 A 库拖宽的列会漏进 B 库。
            column.setPrefWidth(Math.max(column.getMinWidth(), width));
        }
    }

    /** 立即把当前宽度写盘（门禁与显式保存用；正常路径走防抖）。 */
    void persist() {
        persistFor(currentScope(), snapshot());
    }

    private void track() {
        for (TableColumn<?, ?> column : table.getColumns()) {
            if (columnKey(column) == null) {
                continue;
            }
            column.widthProperty().addListener((observable, oldWidth, newWidth) -> schedule());
        }
    }

    private void schedule() {
        if (settings == null || currentScope() == null) {
            return;
        }
        Map<String, Integer> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        // 防抖窗口里可能切了连接 —— 把落盘目标钉在「用户拖列时」的作用域上，
        // 否则 A 库的宽度会被写进 B 库。
        pendingScope = currentScope();
        pendingWidths = snapshot;
        if (saveTimer == null) {
            saveTimer = new PauseTransition(SAVE_DELAY);
            saveTimer.setOnFinished(event -> persistFor(pendingScope, pendingWidths));
        }
        saveTimer.playFromStart();
    }

    private void persistFor(String targetScope, Map<String, Integer> widths) {
        if (settings == null || targetScope == null || widths.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : widths.entrySet()) {
            settings.setInt(key(targetScope, entry.getKey()), entry.getValue());
        }
    }

    private Map<String, Integer> snapshot() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        for (TableColumn<?, ?> column : table.getColumns()) {
            String key = columnKey(column);
            if (key == null) {
                continue;
            }
            // 用 prefWidth 而不是 width：拖列改的是「想要多宽」，
            // width 只是当前布局算出来的结果（窗口一缩就全变）。
            int width = (int) Math.round(column.getPrefWidth());
            if (width > 0) {
                widths.put(key, width);
            }
        }
        return widths;
    }

    private String currentScope() {
        if (settings == null || scope == null) {
            return null;
        }
        String value = scope.get();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String columnKey(TableColumn<?, ?> column) {
        Object key = column.getUserData();
        if (key instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private String key(String scopeName, String columnKey) {
        // Preferences 的 key 不允许 '/'；连接 id 里的分隔符统一折成 '_'。
        return prefix + "." + scopeName.replace('/', '_').replace('\\', '_')
                + ".width." + columnKey;
    }
}
