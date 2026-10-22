package io.agenticdb.introspect;

/**
 * CHECK 约束。
 *
 * <p>⭐ 以前**根本没有这个概念** —— 内省层不采、建表 SQL 不带、
 * 结构页不显示。后果是：SQLite 改列要整表重建，重建的 {@code CREATE TABLE}
 * 只搬运「列 / 主键 / UNIQUE / DEFAULT / 外键」，**CHECK 约束静默消失** ——
 * 用户没改错任何东西，约束却没了（UI-AUDIT / TODO D3）。
 *
 * @param name       约束名；SQLite 允许匿名 CHECK（没有名字）
 * @param expression 约束表达式，例如 {@code age >= 0}
 */
public record CheckDef(String name, String expression) {

    public CheckDef {
        name = name == null ? "" : name.trim();
        expression = expression == null ? "" : expression.trim();
    }

    /** 有名字显示名字，没名字显示表达式。 */
    public String label() {
        return name.isBlank() ? expression : name;
    }

    /** 是否是命名约束。 */
    public boolean named() {
        return !name.isBlank();
    }
}
