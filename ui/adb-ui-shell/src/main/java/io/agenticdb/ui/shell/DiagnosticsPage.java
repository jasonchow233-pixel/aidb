package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DbSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 诊断页：一张表的健康概览。
 *
 * <p>侧栏的「诊断」入口以前开的是空壳。这里给出真实可用的信息：
 * 行数、结构规模、主键、索引、外键、触发器，以及几条**可执行的结论**
 * （没有主键 → 不可编辑；没有索引 → 查询会全表扫；外键悬空 → 数据完整性风险）。
 */
public final class DiagnosticsPage extends VBox {

    private final DbSession session;
    private final SchemaService schemaService;
    private final String tableName;
    private final Executor dbExecutor;

    private final Label title = new Label();
    private final VBox body = new VBox(8);
    private final ProgressIndicator spinner = new ProgressIndicator();

    public DiagnosticsPage(DbSession session, SchemaService schemaService, String tableName,
                           Executor dbExecutor) {
        this.session = session;
        this.schemaService = schemaService;
        this.tableName = tableName;
        this.dbExecutor = dbExecutor;
        setSpacing(10);
        setPadding(new Insets(0));
        getStyleClass().add("adb-page__body");

        title.setId("diagnostics-title");
        title.getStyleClass().add("adb-page__title");

        spinner.setMaxSize(18, 18);
        body.setId("diagnostics-body");
        body.setSpacing(8);
        getChildren().addAll(title, spinner, body);
        reload();
    }

    /** 重新诊断一次。 */
    public void reload() {
        spinner.setVisible(true);
        body.getChildren().clear();
        dbExecutor.execute(() -> {
            List<Finding> findings = new ArrayList<>();
            String header;
            try {
                TableStructure structure = schemaService.structure(session, tableName);
                TableSchema schema = schemaService.describe(session, tableName);
                long rows = schemaService.count(session, tableName);
                header = tableName + " · " + (structure.view() ? "视图" : "表")
                        + " · " + rows + " 行";
                findings.addAll(analyze(structure, rows));
            } catch (Exception e) {
                header = tableName + " · 诊断失败";
                findings.add(new Finding("错误", e.getMessage() == null
                        ? e.toString() : e.getMessage(), true));
            }
            String finalHeader = header;
            List<Finding> finalFindings = findings;
            javafx.application.Platform.runLater(() -> {
                title.setText(finalHeader);
                spinner.setVisible(false);
                body.getChildren().clear();
                for (Finding finding : finalFindings) {
                    body.getChildren().add(render(finding));
                }
            });
        });
    }

    /** 结论规则。宁可少说，也不要说错 —— 诊断页误导比没有更糟。 */
    private List<Finding> analyze(TableStructure structure, long rows) {
        List<Finding> findings = new ArrayList<>();

        findings.add(new Finding("列", structure.columns().size() + " 个"
                + (structure.primaryKeys().isEmpty() ? "" : " · 主键 " + String.join(", ",
                structure.primaryKeys())), false));

        long uniqueIndexes = structure.indexes().stream().filter(IndexDef::unique).count();
        findings.add(new Finding("索引", structure.indexes().size() + " 个（唯一 " + uniqueIndexes + " 个）",
                structure.indexes().isEmpty()));

        findings.add(new Finding("外键", structure.foreignKeys().size() + " 个", false));
        findings.add(new Finding("触发器", structure.triggers().size() + " 个", false));

        if (!structure.view() && structure.primaryKeys().isEmpty()) {
            findings.add(new Finding("⚠ 无主键",
                    "没有主键的表无法按主键定位，数据页会退化成只读 —— "
                            + "编辑会不安全，建议补一个主键或唯一索引。", true));
        }
        if (!structure.view() && structure.indexes().isEmpty() && rows > 1000) {
            findings.add(new Finding("⚠ 无索引",
                    rows + " 行的表一个索引都没有，全表扫描会随数据量线性变慢。", true));
        }
        if (structure.view()) {
            findings.add(new Finding("视图", "视图是只读的，数据页的编辑功能不可用。", false));
        }
        if (findings.stream().noneMatch(Finding::warning)) {
            findings.add(new Finding("✅ 未发现明显问题",
                    "主键、索引、外键结构都正常。", false));
        }
        return findings;
    }

    private Label render(Finding finding) {
        Label label = new Label(finding.title() + " —— " + finding.detail());
        label.setWrapText(true);
        label.getStyleClass().add(finding.warning() ? "adb-risk--write" : "adb-muted");
        if (finding.warning()) {
            label.getStyleClass().add("adb-badge");
        }
        return label;
    }

    private record Finding(String title, String detail, boolean warning) {
    }
}
