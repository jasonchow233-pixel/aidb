package io.agenticdb.declarations;

import java.util.List;
import java.util.Optional;

/**
 * 声明层的完整加载结果。
 *
 * <p>加载成功后这个对象是不可变的，UI 与传输规划全程共享同一份。
 *
 * @param issues 加载过程中的提示（非致命）。语义错误会抛异常而不是进这里。
 */
public record DeclaredModel(
        List<ConnectionDescriptor> connections,
        List<TransferFamily> families,
        List<Dialect> dialects,
        List<Issue> issues) {

    public DeclaredModel {
        connections = List.copyOf(connections);
        families = List.copyOf(families);
        dialects = List.copyOf(dialects);
        issues = List.copyOf(issues);
    }

    public Optional<ConnectionDescriptor> byId(String id) {
        return connections.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Optional<TransferFamily> familyOf(String connectionId) {
        return families.stream().filter(f -> f.contains(connectionId)).findFirst();
    }

    /** 已实现驱动、可以真正连上去的连接。第一批是 sqlite / postgresql / mysql。 */
    public List<ConnectionDescriptor> jdbcConnections() {
        return connections.stream().filter(ConnectionDescriptor::isJdbc).toList();
    }

    /**
     * 每个连接的 UI 警告 —— ⭐ 全部由 traits 推导。
     *
     * <p>前端不允许再写 {@code if (conn.equals("mysql"))} 去猜提示文案。
     * 加一种数据库时提示必须自动跟着声明走，而不是等改 UI 代码。
     */
    public List<UiWarning> warningsOf(ConnectionDescriptor c) {
        var out = new java.util.ArrayList<UiWarning>();
        var t = c.traits();

        if (!t.transactionalDdl()) {
            out.add(new UiWarning(
                    "ddl-implicit-commit", "warning", "structureEditor",
                    c.label() + " 的 DDL 会隐式提交，无法用事务回滚",
                    "执行结构变更前请先手动备份"));
        }
        if ("autoIncrement".equals(t.identityKind())) {
            out.add(new UiWarning(
                    "identity-not-autoincrement", "info", "structureEditor",
                    "该库的自增关键字不是 IDENTITY，导出 DDL 时需要转换",
                    "导出前检查自增属性是否保留"));
        }
        if ("none".equals(t.identityKind()) && !t.sequenceObjects()) {
            out.add(new UiWarning(
                    "no-identity", "info", "structureEditor",
                    "该库既无自增也无序列，插入时必须显式提供主键",
                    null));
        }
        if (t.explainFormats().size() == 1) {
            out.add(new UiWarning(
                    "explain-single-format", "info", "queryEditor",
                    c.label() + " 的执行计划只有 " + t.explainFormats().get(0) + " 一种格式",
                    null));
        }
        return List.copyOf(out);
    }

    /**
     * UI 警告。
     *
     * @param id            稳定标识，便于门禁按 id 断言
     * @param level         info | warning | critical
     * @param whereToShow   sidebar | structureEditor | transfer | queryEditor | dataGrid
     */
    public record UiWarning(
            String id, String level, String whereToShow, String message, String suggestedAction) {
    }
}