package io.agenticdb.ui.shell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** 最近执行过的 SQL。首版落 java.util.prefs，P5 再迁到 config.db。 */
final class QueryHistory {

    private static final String KEY = "query.history";
    private static final int LIMIT = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() { };

    private final SettingsStore settings;
    private final List<String> memory = new ArrayList<>();

    QueryHistory(SettingsStore settings) {
        this.settings = settings;
    }

    List<String> recent() {
        return List.copyOf(load());
    }

    void add(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        List<String> items = new ArrayList<>(load());
        String value = sql.strip();
        items.remove(value);
        items.add(0, value);
        if (items.size() > LIMIT) {
            items = new ArrayList<>(items.subList(0, LIMIT));
        }
        save(items);
    }

    void clear() {
        memory.clear();
        if (settings != null) {
            settings.set(KEY, "[]");
        }
    }

    private List<String> load() {
        if (settings == null) {
            return List.copyOf(memory);
        }
        String raw = settings.get(KEY, "");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> items = JSON.readValue(raw, LIST_TYPE);
            return items == null ? List.of() : items;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void save(List<String> items) {
        if (settings == null) {
            memory.clear();
            memory.addAll(items);
            return;
        }
        try {
            settings.set(KEY, JSON.writeValueAsString(items));
        } catch (Exception ignored) {
            // 历史记录失败不能影响查询本身。
        }
    }
}
