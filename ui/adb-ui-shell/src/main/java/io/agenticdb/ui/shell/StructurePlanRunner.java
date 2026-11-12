package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.ColumnProblem;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.Danger;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import java.util.List;

/**
 * 结构变更计划的执行与确认 —— 列级（结构页）和表级（T1–T4）共用一份。
 *
 * <h2>为什么抽出来</h2>
 * 结构页与表级生命周期共用 SQL 确认、事务边界与缓存失效逻辑。
 * DDL 的事务与回滚效果由数据库能力决定，不保证跨方言原子性；
 * 隐式提交方言的行为仍待真库矩阵验证。
 *
 * <h2>安全约定</h2>
 * <ol>
 *   <li>SQL <b>必须先展示</b>再执行（{@link #confirm}）。</li>
 *   <li>{@link StructurePlan#approvalRequired()} 为真时要<b>第二道</b>确认。</li>
 *   <li>破坏性按钮不是默认按钮 —— 回车落在「取消」上。</li>
 * </ol>
 */
public final class StructurePlanRunner {

    private StructurePlanRunner() {
    }

    /**
     * 执行整批语句；仅为自己开启的事务提交或尝试回滚。
     *
     * <p>SQLite 整表重建（{@link StructurePlan#foreignKeysOff()}）仅在原先开启外键时
     * 临时关闭，并在结束事务后恢复。已有事务时拒绝重建，因为事务内关闭外键无效。
     * 其它计划可以加入用户事务，提交与回滚仍由用户决定。
     */
    static void execute(DbSession session, StructurePlan plan) throws Exception {
        execute(session, plan, null);
    }

    /**
     * 执行结构变更计划，并统一作废该会话的 describe 缓存。
     *
     * <p>结构页与表级生命周期共用此失效挂点。计划中至少一条语句执行成功就作废，
     * 即使后续失败：用户事务中的 DDL 或数据库隐式提交可能已经改变结构。
     * 首条语句就失败且没有成功执行过语句时，保留缓存。
     *
     * @param schemas 该会话所属外壳的缓存；测试或无缓存的调用方传 {@code null}
     */
    static void execute(DbSession session, StructurePlan plan, SchemaService schemas)
            throws Exception {
        boolean ownTransaction = !session.inTransaction();
        if (plan.foreignKeysOff() && !ownTransaction) {
            throw new ColumnProblem("structure.error.rebuildInTransaction");
        }
        for (String sql : plan.statements()) {
            DbSession.requireDeclared(plan.danger(), sql);
        }
        boolean restoreForeignKeys = false;
        boolean executedStatement = false;
        Throwable failure = null;
        try {
            if (plan.foreignKeysOff()) {
                var state = session.query("PRAGMA foreign_keys", 1);
                restoreForeignKeys = ((Number) state.rows().getFirst().get("foreign_keys"))
                        .intValue() != 0;
                if (restoreForeignKeys) {
                    session.update("PRAGMA foreign_keys = OFF", List.of(), Danger.MUTATING);
                }
            }
            if (ownTransaction) {
                session.begin();
            }
            for (String sql : plan.statements()) {
                session.execute(sql, plan.danger());
                executedStatement = true;
            }
            if (ownTransaction) {
                session.commit();
            }
        } catch (Exception | Error e) {
            failure = e;
            if (ownTransaction) {
                try {
                    // begin 也可能在已切换事务状态后才报错。
                    if (session.inTransaction()) {
                        session.rollback();
                    }
                } catch (Exception | Error rollbackError) {
                    e.addSuppressed(rollbackError);
                }
            }
            throw e;
        } finally {
            if (executedStatement && schemas != null) {
                schemas.invalidate(session);
            }
            if (restoreForeignKeys) {
                try {
                    session.update("PRAGMA foreign_keys = ON", List.of(), Danger.MUTATING);
                } catch (Exception | Error restoreError) {
                    if (failure != null) {
                        failure.addSuppressed(restoreError);
                    } else {
                        throw restoreError;
                    }
                }
            }
        }
    }

    /**
     * 计划标题的渲染口 —— 模型层只交「键 + 参数」，句子在这里成。
     *
     * <p>状态栏、确认框、toast 三个出口都走这里：各写一份的话，
     * 总有一处会把中文漏进英文界面（R45 G3-a 的整件事就是这个口子）。
     */
    static String title(StructurePlan plan) {
        return I18n.t(plan.titleKey(), plan.titleArgs().toArray());
    }

    /**
     * 展示 SQL 并等用户确认。
     *
     * @param stylesheets 主窗口的样式表；弹窗要跟着主题走
     * @return 用户确实点了「继续 / 确认执行」才为真
     */
    static boolean confirm(Window owner, List<String> stylesheets, StructurePlan plan) {
        String title = title(plan);
        if (!showPlanAlert(owner, stylesheets, plan, title, I18n.t("grid.save.proceed"))) {
            return false;
        }
        if (!plan.approvalRequired()) {
            return true;
        }
        return showPlanAlert(owner, stylesheets, plan, I18n.t("plan.riskyTitle", title),
                I18n.t("query.confirmRunTitle"));
    }

    private static boolean showPlanAlert(Window owner, List<String> stylesheets,
                                         StructurePlan plan, String title, String okLabel) {
        // ⭐ OTHER 而不是 OK_DONE —— 破坏性按钮不能是默认按钮，回车不能落在它上面。
        ButtonType ok = new ButtonType(okLabel, ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("plan.title"));
        alert.setHeaderText(title);
        javafx.scene.control.Label warning = new javafx.scene.control.Label(I18n.t("plan.body"));
        warning.setId("plan-warning");
        warning.setWrapText(true);
        TextArea area = new TextArea(plan.sql());
        area.setEditable(false);
        area.setWrapText(false);
        area.setId("plan-sql");
        // +1：给最后一行留一行余量，SQL 恰好 N 行时不会因为滚动条把末行挤掉。
        area.setPrefRowCount((int) Math.min(18, Math.max(6, plan.sql().lines().count() + 1)));
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10, warning, area);
        javafx.scene.layout.VBox.setVgrow(area, javafx.scene.layout.Priority.ALWAYS);
        alert.getDialogPane().setPrefWidth(760);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().getButtonTypes().setAll(ok, cancel);
        alert.setResultConverter(type -> type);
        if (owner != null) {
            alert.initOwner(owner);
        }
        DialogStyles.apply(alert, owner);
        alert.setOnShown(event -> DestructiveConfirm.pinCancelAsDefault(
                alert.getDialogPane(), cancel, ok));
        return alert.showAndWait().orElse(cancel) == ok;
    }

    /** 把计划里第一条语句取出来给人看（门禁用）。 */
    static String firstStatement(StructurePlan plan) {
        return plan.statements().isEmpty() ? "" : plan.statements().getFirst();
    }

    static Button lookupButton(Alert alert, ButtonType type) {
        return (Button) alert.getDialogPane().lookupButton(type);
    }
}
