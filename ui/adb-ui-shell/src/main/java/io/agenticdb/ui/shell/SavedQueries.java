package io.agenticdb.ui.shell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 已保存的查询（命名 + SQL）。
 *
 * <p>侧栏的「已保存查询」以前是空壳页 —— 现在它真的能用：
 * 把当前查询存个名字，之后从列表里一键打开。
 *
 * <p>落 {@code java.util.prefs}（和 {@link QueryHistory} 同一套）。
 * 换机器不会同步 —— 首版不做跨设备同步。
 */
final class SavedQueries {

    private static final String KEY = "query.saved";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Entry>> LIST_TYPE = new TypeReference<>() { };

    /** 一条已保存的查询。 */
    record Entry(String name, String sql, long savedAt) {

        Entry {
            name = name == null ? "" : name.trim();
            sql = sql == null ? "" : sql;
        }
    }

    private final SettingsStore settings;
    private final String key;

    SavedQueries(SettingsStore settings) {
        this(settings, KEY);
    }

    /** 指定存储键（门禁用：让每个用例互不干扰）。 */
    SavedQueries(SettingsStore settings, String key) {
        this.settings = settings;
        this.key = key == null ? KEY : key;
    }

    /** 全部已保存的查询（按保存时间倒序）。 */
    List<Entry> all() {
        List<Entry> items = load();
        List<Entry> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Long.compare(b.savedAt(), a.savedAt()));
        return List.copyOf(sorted);
    }

    /** 存一条。同名的覆盖（不产生重复项）。 */
    void save(String name, String sql) {
        if (name == null || name.isBlank() || sql == null || sql.isBlank()) {
            return;
        }
        List<Entry> items = new ArrayList<>(load());
        items.removeIf(entry -> entry.name().equalsIgnoreCase(name.trim()));
        items.add(new Entry(name.trim(), sql, System.currentTimeMillis()));
        write(items);
    }

    void delete(String name) {
        List<Entry> items = new ArrayList<>(load());
        items.removeIf(entry -> entry.name().equalsIgnoreCase(name));
        write(items);
    }

    void rename(String oldName, String newName) {
        if (newName == null || newName.isBlank()) {
            return;
        }
        List<Entry> items = new ArrayList<>(load());
        for (int i = 0; i < items.size(); i++) {
            Entry entry = items.get(i);
            if (entry.name().equalsIgnoreCase(oldName)) {
                items.set(i, new Entry(newName.trim(), entry.sql(), entry.savedAt()));
            }
        }
        write(items);
    }

    private List<Entry> load() {
        if (settings == null) {
            return List.of();
        }
        String raw = settings.get(key, "");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<Entry> items = JSON.readValue(raw, LIST_TYPE);
            return items == null ? List.of() : items;
        } catch (Exception ignored) {
            // 存坏了就当没有 —— 不能让一条坏记录挡住整个应用启动。
            return List.of();
        }
    }

    private void write(List<Entry> items) {
        if (settings == null) {
            return;
        }
        try {
            settings.set(key, JSON.writeValueAsString(items));
        } catch (Exception ignored) {
            // 存不进去不能影响别的功能。
        }
    }
}
