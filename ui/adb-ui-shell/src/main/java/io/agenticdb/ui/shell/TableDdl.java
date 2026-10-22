package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.jdbc.DbSession;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * T1–T4 表级生命周期的协调者：建表 / 清空 / 重命名 / 删表。
 *
 * <p>这里是<b>唯一</b>知道「怎么把一次表级变更跑完」的地方：
 * 收输入 → 生成计划 → 给用户看 SQL → 确认 → 后台事务执行 → 刷新对象树。
 * 侧栏右键、数据库菜单、命令面板三条入口都进同一个方法，
 * 于是「从菜单点」和「从右键点」不会有两套行为。
 */
final class TableDdl {

    private final Supplier<DbSession> session;
    private final Executor dbExecutor;
    private final Consumer<String> status;
    private final Supplier<List<String>> existingTables;
    private final Runnable onChanged;
    private final Supplier<Window> owner;
    private final Supplier<List<String>> stylesheets;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Predicate<StructurePlan> confirmation;

    TableDdl(Supplier<DbSession> session,
             Executor dbExecutor,
             Consumer<String> status,
             Supplier<List<String>> existingTables,
             Runnable onChanged,
             Supplier<Window> owner,
             Supplier<List<String>> stylesheets) {
        this.session = session;
        this.dbExecutor = dbExecutor;
        this.status = status == null ? message -> { } : status;
        this.existingTables = existingTables == null ? List::of : existingTables;
        this.onChanged = onChanged == null ? () -> { } : onChanged;
        this.owner = owner == null ? () -> null : owner;
        this.stylesheets = stylesheets == null ? List::of : stylesheets;
        this.confirmation = plan -> StructurePlanRunner.confirm(
                this.owner.get(), this.stylesheets.get(), plan);
    }

    /**
     * 换掉确认流程（单测用）。
     *
     * <p>默认的实现会弹模态框 —— 单测按不住模态框，但「取消就不执行」这条规则
     * 必须能测，所以确认这一步是可替换的（与 {@code StructurePage.setConfirmationHandler} 同理）。
     */
    void setConfirmation(Predicate<StructurePlan> confirmation) {
        this.confirmation = confirmation == null
                ? plan -> StructurePlanRunner.confirm(owner.get(), stylesheets.get(), plan)
                : confirmation;
    }

    /** T1 新建表。 */
    void createTable() {
        DbSession current = session.get();
        if (current == null) {
            status.accept("请先连上一个数据库");
            return;
        }
        NewTableDialog dialog = NewTableDialog.create(current, existingTables.get());
        Optional<StructurePlan> plan = dialog.show(owner.get(), stylesheets.get());
        if (plan.isEmpty()) {
            status.accept("已取消新建表");
            return;
        }
        run(plan.get());
    }

    /** T2 清空表。 */
    void truncateTable(String table) {
        DbSession current = session.get();
        if (current == null || blank(table)) {
            status.accept("请先连上数据库并选中一张表");
            return;
        }
        run(StructureSqlBuilder.truncateTable(current, table));
    }

    /** T3 重命名表 —— 弹输入框问新名字，然后走 {@link #renameTable(String, String)}。 */
    void renameTable(String table) {
        DbSession current = session.get();
        if (current == null || blank(table)) {
            status.accept("请先连上数据库并选中一张表");
            return;
        }
        TextInputDialog dialog = new TextInputDialog(table);
        dialog.setTitle("重命名表");
        dialog.setHeaderText("把「" + table + "」改名为");
        dialog.getEditor().setId("rename-table-input");
        if (owner.get() != null) {
            dialog.initOwner(owner.get());
        }
        Optional<String> answer = dialog.showAndWait();
        if (answer.isEmpty()) {
            status.accept("已取消重命名");
            return;
        }
        String next = answer.get() == null ? "" : answer.get().trim();
        String problem = tableNameProblem(next, current, table);
        if (problem != null) {
            status.accept(problem);
            Alert alert = new Alert(Alert.AlertType.ERROR, problem, ButtonType.OK);
            alert.setHeaderText("改不了这个表名");
            if (owner.get() != null) {
                alert.initOwner(owner.get());
            }
            alert.showAndWait();
            return;
        }
        renameTable(table, next);
    }

    /**
     * T3 重命名表（不弹框的版本 —— 名字已经确定）。
     *
     * <p>拆出来是为了让「校验 + 执行」这段能被单测覆盖：
     * 弹框那层没法在单测里按住，但规则必须能守住。
     */
    void renameTable(String table, String next) {
        DbSession current = session.get();
        if (current == null || blank(table)) {
            status.accept("请先连上数据库并选中一张表");
            return;
        }
        String problem = tableNameProblem(next, current, table);
        if (problem != null) {
            status.accept(problem);
            return;
        }
        run(StructureSqlBuilder.renameTable(current, table, next.trim(), existingTables.get()));
    }

    /** T4 删除表。 */
    void dropTable(String table) {
        DbSession current = session.get();
        if (current == null || blank(table)) {
            status.accept("请先连上数据库并选中一张表");
            return;
        }
        run(StructureSqlBuilder.dropTable(current, table));
    }

    /**
     * ⭐ N3：批量删表 —— 一张汇总确认页 + 逐表执行 + 失败行报告。
     *
     * <p>为什么不是「一个事务里 DROP 全部」：
     * <ul>
     *   <li>用户多选删除时，最想要的是「能删的都删掉，删不掉的告诉我为什么」；</li>
     *   <li>一张表被外键依赖就整批回滚，会让另外 9 张本来能删的表也留着；</li>
     *   <li>逐表执行仍然各自走单事务，失败的那张回滚，不会留下半张表。</li>
     * </ul>
     */
    void dropTables(List<String> tables) {
        DbSession current = session.get();
        if (current == null) {
            status.accept("请先连上数据库并选中一张表");
            return;
        }
        List<String> names = tables == null ? List.of() : tables.stream()
                .filter(name -> !blank(name))
                .map(String::trim)
                .distinct()
                .toList();
        if (names.isEmpty()) {
            status.accept("请先选中要删除的表");
            return;
        }
        StructurePlan plan = StructureSqlBuilder.dropTables(current, names);
        if (!confirmation.test(plan)) {
            status.accept("已取消：" + plan.title());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            status.accept("已有结构变更正在执行");
            return;
        }
        status.accept("正在删除 " + names.size() + " 张表…");
        dbExecutor.execute(() -> {
            List<String> failures = new ArrayList<>();
            for (String name : names) {
                try {
                    StructurePlanRunner.execute(current, StructureSqlBuilder.dropTable(current, name));
                } catch (Exception e) {
                    failures.add(name + "：" + readable(e));
                }
            }
            Platform.runLater(() -> {
                running.set(false);
                onChanged.run();
                int deleted = names.size() - failures.size();
                if (failures.isEmpty()) {
                    status.accept("已删除 " + deleted + " 张表");
                } else {
                    status.accept("已删除 " + deleted + "/" + names.size()
                            + " 张表；失败：" + String.join("、", failures));
                    showFailureReport(names.size(), deleted, failures);
                }
            });
        });
    }

    /** 批量删除的失败明细 —— 状态栏一行放不下，给一份可复制的报告。 */
    private void showFailureReport(int total, int deleted, List<String> failures) {
        // 单测 / 无窗口环境没有 owner —— 不弹模态框（否则测试会一直等）。
        if (owner.get() == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("删除表");
        alert.setHeaderText("已删除 " + deleted + "/" + total + " 张表，"
                + failures.size() + " 张失败");
        TextArea area = new TextArea(String.join("\n", failures));
        area.setEditable(false);
        area.setWrapText(true);
        area.setId("batch-drop-failures");
        area.setPrefRowCount(Math.min(12, Math.max(3, failures.size() + 1)));
        alert.getDialogPane().setContent(area);
        alert.getDialogPane().getButtonTypes().setAll(ButtonType.OK);
        if (owner.get() != null) {
            alert.initOwner(owner.get());
        }
        DialogStyles.apply(alert, owner.get());
        alert.showAndWait();
    }

    /** 表名的校验（重命名时用；建表走 {@link StructureSqlBuilder#createTable}）。 */
    private String tableNameProblem(String next, DbSession current, String oldName) {
        List<String> others = new java.util.ArrayList<>(existingTables.get());
        others.removeIf(name -> name.equalsIgnoreCase(oldName));
        try {
            StructureSqlBuilder.renameTable(current, oldName, next, others);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    boolean isRunning() {
        return running.get();
    }

    private void run(StructurePlan plan) {
        DbSession current = session.get();
        if (current == null) {
            status.accept("连接已断开");
            return;
        }
        if (!confirmation.test(plan)) {
            status.accept("已取消：" + plan.title());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            status.accept("已有结构变更正在执行");
            return;
        }
        status.accept("正在执行：" + plan.title());
        dbExecutor.execute(() -> {
            try {
                StructurePlanRunner.execute(current, plan);
                Platform.runLater(() -> {
                    running.set(false);
                    status.accept(plan.title() + " 已完成");
                    onChanged.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    running.set(false);
                    status.accept(plan.title() + " 失败：" + readable(e));
                });
            }
        });
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String readable(Throwable error) {
        // ⭐ V-12：统一走错误归类 —— 给出「发生了什么 + 建议怎么办」，
        //    而不是把驱动的英文原文直接甩给用户。
        return io.agenticdb.safety.ErrorClassifier.classify(error).message();
    }
}
