package io.agenticdb.introspect;

/**
 * 触发器。{@code timing}/{@code event} 在 SQLite 的 PRAGMA 里不可拆分时留空，
 * {@code statement} 保留完整 CREATE TRIGGER，可在重建表后原样恢复。
 */
public record TriggerDef(String name, String timing, String event, String statement) {

    public TriggerDef {
        name = name == null ? "" : name;
        timing = timing == null ? "" : timing;
        event = event == null ? "" : event;
        statement = statement == null ? "" : statement;
    }

    public String timingLabel() {
        return timing.isBlank() ? "—" : timing;
    }

    public String eventLabel() {
        return event.isBlank() ? "—" : event;
    }
}
