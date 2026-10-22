package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DbSession;
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
 * 「先给用户看 SQL、再在<b>同一个事务</b>里执行、失败回滚」这套语义，
 * 结构页原本写在自己的私有方法里。表级生命周期（建表 / 清空 / 重命名 / 删表）
 * 需要<b>完全相同</b>的语义 —— 抄一份就会变成两份慢慢跑偏的实现：
 * 将来改了事务边界，只改一处，另一处就成了静默丢数据的口子。
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
     * 在单个事务里执行整批语句，失败回滚。
     *
     * <p>SQLite 整表重建期间要临时关掉外键（{@link StructurePlan#foreignKeysOff()}），
     * 否则复制数据时外键会先炸。
     */
    static void execute(DbSession session, StructurePlan plan) throws Exception {
        boolean foreignKeysOff = plan.foreignKeysOff();
        if (foreignKeysOff) {
            session.update("PRAGMA foreign_keys = OFF");
        }
        session.begin();
        try {
            for (String sql : plan.statements()) {
                session.execute(sql);
            }
            session.commit();
        } catch (Exception e) {
            try {
                session.rollback();
            } catch (Exception ignored) {
                // 回滚失败也要把原始 SQL 错误交给用户。
            }
            throw e;
        } finally {
            if (foreignKeysOff) {
                session.update("PRAGMA foreign_keys = ON");
            }
        }
    }

    /**
     * 展示 SQL 并等用户确认。
     *
     * @param stylesheets 主窗口的样式表；弹窗要跟着主题走
     * @return 用户确实点了「继续 / 确认执行」才为真
     */
    static boolean confirm(Window owner, List<String> stylesheets, StructurePlan plan) {
        if (!showPlanAlert(owner, stylesheets, plan, plan.title(), "继续")) {
            return false;
        }
        if (!plan.approvalRequired()) {
            return true;
        }
        return showPlanAlert(owner, stylesheets, plan, "高风险结构变更：" + plan.title(),
                "确认执行");
    }

    private static boolean showPlanAlert(Window owner, List<String> stylesheets,
                                         StructurePlan plan, String title, String okLabel) {
        // ⭐ OTHER 而不是 OK_DONE —— 破坏性按钮不能是默认按钮，回车不能落在它上面。
        ButtonType ok = new ButtonType(okLabel, ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("结构变更确认");
        alert.setHeaderText(title);
        alert.setContentText("将按顺序执行以下 SQL（单事务，失败回滚）：");
        TextArea area = new TextArea(plan.sql());
        area.setEditable(false);
        area.setWrapText(false);
        area.setId("plan-sql");
        area.setPrefRowCount(Math.min(18, Math.max(4, plan.statements().size() + 2)));
        alert.getDialogPane().setContent(area);
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
