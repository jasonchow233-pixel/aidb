package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.introspect.TableOptions;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.ui.App;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class StructurePageTest {

    private JdbcSession session;
    private ExecutorService executor;
    private StructurePage page;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, "
                + "email TEXT NOT NULL DEFAULT 'x')");
        session.execute("CREATE INDEX idx_users_name ON users(name)");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-structure");
            thread.setDaemon(true);
            return thread;
        });
        page = new StructurePage(session, new io.agenticdb.introspect.SchemaService(), "users",
                "test", new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        }, executor, () -> { });
        page.setConfirmationHandler(plan -> true);
        stage.setScene(new Scene(page, 1000, 700));
        stage.show();
        WaitForAsyncUtils.waitForFxEvents();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            page.close();
        }
        executor.shutdownNow();
        session.close();
    }

    /**
     * ⭐ 第七批补的适配闸：结构页每一排内联工具条，在**验收宽度 1280** 下
     * 整排需要的宽度必须放得进预算。
     *
     * <p>为什么值得为一条「现在还没坏」的排立闸：D-5（命令带）与 D-8（查询页分页条）
     * 是连续两批在同一类形状上翻的车 —— 英文比中文宽，一排文字按钮就装不下。
     * 结构页这一排是 Add Field / Edit Field / Rename / Delete Field / Table Comment，
     * 形状一模一样，而后面还有 44 个文件要抽。
     *
     * <p>两个坑按 D-8 的教训绕开：
     * ① <b>不去改窗口尺寸</b> —— {@code stage.setWidth()} 在这套 harness 里不会同步落到场景上，
     *    所以改成**推导预算**：此刻「场景宽 − 这一排的实得宽」就是这一排以外的固定开销，
     *    1280 下的预算 = 1280 − 那个开销。
     * ② 必须**挂上样式表**再量：本类的 {@code @Start} 建的是裸场景，字号与内边距全来自
     *    {@code app.css}，不挂就量出另一套宽度，这条闸会绿得毫无意义。
     * 外加一条"先确认量到的是真宽度"的自证（{@code getWidth() > 0} 且开销不为负），
     * 否则扫描失效时它同样会绿。
     */
    @Test
    void structureToolbarsFitTheAcceptanceWidth(FxRobot robot) throws Exception {
        waitForStructure();
        // 喂布局与读几何必须在同一条 FX 任务里（第四批的教训：分两次调用，
        // 中间能插入布局事件，读到自相矛盾的帧）。
        List<String> bad = WaitForAsyncUtils.asyncFx(() -> {
            Scene scene = page.getScene();
            if (scene.getStylesheets().isEmpty()) {
                scene.getStylesheets().add(getClass()
                        .getResource("/io/agenticdb/ui/theme-light.css").toExternalForm());
                scene.getStylesheets().add(getClass()
                        .getResource("/io/agenticdb/ui/app.css").toExternalForm());
                scene.getRoot().getStyleClass().add("theme-light");
            }
            page.applyCss();
            page.layout();
            List<String> out = new java.util.ArrayList<>();
            java.util.Set<javafx.scene.Node> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            // ⚠️ 必须逐页签切一遍再量：TabPane 只把**选中**页签的内容挂进场景图，
            //    直接 lookupAll 只能看见「列」那一排 —— 一条声称"量每一排"、
            //    实际只量到一排的门禁比没有门禁更危险（同一个坑见 I18nAuditTest 的
            //    ACTION_REGISTRATION 注释）。
            javafx.scene.control.TabPane tabs =
                    (javafx.scene.control.TabPane) page.lookup("#structure-tabs");
            for (int i = -1; i < tabs.getTabs().size(); i++) {
                if (i >= 0) {
                    tabs.getSelectionModel().select(i);
                }
                page.applyCss();
                page.layout();
                for (javafx.scene.Node node : page.lookupAll(".adb-inline-toolbar")) {
                    if (!(node instanceof javafx.scene.layout.HBox bar) || !seen.add(bar)) {
                        continue;
                    }
                    double overhead = scene.getWidth() - bar.getWidth();
                    assertThat(bar.getWidth())
                            .as("量之前先确认这一排真的布局过了（宽度 0 = 这条闸在空转）")
                            .isGreaterThan(0.0);
                    assertThat(overhead)
                            .as("开销为负说明量到的不是同一帧，预算就不成立")
                            .isGreaterThanOrEqualTo(0.0);
                    double budget = App.SHOT_SCENE_WIDTH - overhead;
                    if (bar.prefWidth(-1) > budget + 0.5) {
                        out.add(who(bar) + " 在 " + (int) App.SHOT_SCENE_WIDTH + " 下需要 "
                                + Math.round(bar.prefWidth(-1)) + "px > 预算 "
                                + Math.round(budget) + "px");
                    }
                }
            }
            assertThat(seen)
                    .as("结构页有四排内联工具条（列 / 索引 / 外键 / 触发器），少一排就是漏量")
                    .hasSizeGreaterThanOrEqualTo(4);
            return out;
        }).get();
        assertThat(bad).isEmpty();
    }

    @Test
    void loadsColumnsIndexesAndDdl(FxRobot robot) throws Exception {
        waitForStructure();

        TableStructure structure = page.structure();
        assertThat(structure.columns()).extracting("name")
                .containsExactly("id", "name", "email");
        assertThat(structure.indexes()).extracting(IndexDef::name)
                .containsExactly("idx_users_name");
        assertThat(structure.ddl()).contains("CREATE TABLE users", "PRIMARY KEY");
        assertThat(robot.lookup("#structure-columns").queryAs(javafx.scene.control.TableView.class)
                .getItems()).hasSize(3);
        assertThat(robot.lookup(".adb-structure__ddl .adb-sql-editor")
                .queryAs(org.fxmisc.richtext.CodeArea.class)
                .getText()).contains("CREATE TABLE users");
    }

    /**
     * 规格 §11.2：DDL 页复用 SqlEditor（等宽 + 行号 + 高亮），且只读。
     *
     * <p>以前这里是个灰底 TextArea —— 没有行号、没有高亮，
     * 长 DDL 读起来要一行行数字母。现在和查询页、新建表预览同一套编辑器。
     */
    @Test
    void ddlTabIsAReadOnlyEditorWithLineNumbers(FxRobot robot) throws Exception {
        waitForStructure();

        org.fxmisc.richtext.CodeArea area = robot.lookup(".adb-structure__ddl .adb-sql-editor")
                .queryAs(org.fxmisc.richtext.CodeArea.class);

        assertThat(area.isEditable())
                .as("DDL 是内省/生成出来的，只读")
                .isFalse();
        assertThat(area.getParagraphGraphicFactory())
                .as("行号来自 ParagraphGraphicFactory —— 与查询页同一套编辑器")
                .isNotNull();
        assertThat(area.getText()).contains("CREATE TABLE users");
    }

    @Test
    void addsColumnThroughDialogAndRefreshes(FxRobot robot) throws Exception {
        waitForStructure();
        int before = page.structure().columns().size();

        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        TextInputControl name = robot.lookup("#structure-dialog-name").queryTextInputControl();
        robot.clickOn(name);
        robot.write("nickname");
        Button ok = robot.lookup(node -> node instanceof Button button
                        && I18n.t("common.ok").equals(button.getText()))
                .queryAs(Button.class);
        robot.clickOn(ok);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                page.structure() != null && page.structure().columns().size() == before + 1);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.structure().column("nickname")).isNotNull();
    }

    @Test
    void rejectsInvalidColumnNameWithoutClosingDialog(FxRobot robot) throws Exception {
        waitForStructure();
        int before = page.structure().columns().size();

        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        Button ok = okButton(robot);
        // 1) 空列名：点了「确定」对话框必须还在
        robot.clickOn(ok);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#structure-dialog-name").queryTextInputControl()).isNotNull();
        assertThat(robot.lookup("#structure-dialog-error").queryAs(Label.class).isVisible())
                .as("非法列名必须给出可见的错误，而不是默默关掉对话框")
                .isTrue();
        assertThat(page.structure().columns()).hasSize(before);

        // 2) 非法列名（数字开头）
        TextInputControl name = robot.lookup("#structure-dialog-name").queryTextInputControl();
        robot.clickOn(name);
        robot.write("1bad");
        robot.clickOn(ok);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#structure-dialog-name").queryTextInputControl()).isNotNull();
        assertThat(page.structure().columns()).hasSize(before);

        // 3) 改成合法列名后能正常提交
        name = robot.lookup("#structure-dialog-name").queryTextInputControl();
        robot.clickOn(name);
        robot.eraseText(4);
        robot.write("nickname");
        robot.clickOn(ok);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                page.structure() != null && page.structure().columns().size() == before + 1);
        assertThat(page.structure().column("nickname")).isNotNull();
    }

    @Test
    void columnNameRulesAreExplicit() {
        // 断言一律走 I18n.t(...)，不写死中文：测试 JVM 的默认语言是英文
        // （I18n.DEFAULT_LOCALE），写死中文等于把这条门禁钉死在一种语言上 ——
        // 抽干净之后它会「因为比较对象变了」而红，而不是因为行为错了。
        assertThat(StructurePage.columnSpecProblem("", "TEXT", null, List.of()))
                .isEqualTo(I18n.t("structure.error.emptyName"));
        assertThat(StructurePage.columnSpecProblem(null, "TEXT", null, List.of()))
                .isEqualTo(I18n.t("structure.error.emptyName"));
        assertThat(StructurePage.columnSpecProblem("1bad", "TEXT", null, List.of()))
                .isEqualTo(I18n.t("structure.error.badName"));
        assertThat(StructurePage.columnSpecProblem("bad name", "TEXT", null, List.of()))
                .isEqualTo(I18n.t("structure.error.badName"));
        assertThat(StructurePage.columnSpecProblem("good_name1", "", null, List.of()))
                .isEqualTo(I18n.t("structure.error.emptyType"));
        assertThat(StructurePage.columnSpecProblem("nickname", "TEXT", null, List.of()))
                .isNull();
    }

    @Test
    void commentColumnIsHiddenWhenDialectHasNoComment(FxRobot robot) throws Exception {
        // 4.5-22：SQLite 没有列注释，列清单若显示「注释」就是一列永远为空的白骨。
        waitForStructure();

        assertThat(io.agenticdb.jdbc.DialectKind.SQLITE.supportsColumnComment()).isFalse();
        assertThat(io.agenticdb.jdbc.DialectKind.MYSQL.supportsColumnComment()).isTrue();
        assertThat(io.agenticdb.jdbc.DialectKind.POSTGRESQL.supportsColumnComment()).isTrue();

        @SuppressWarnings("unchecked")
        var columns = (javafx.scene.control.TableView<io.agenticdb.model.ColumnDef>) robot
                .lookup("#structure-columns").queryAs(javafx.scene.control.TableView.class);
        assertThat(columns.getColumns().stream()
                .map(column -> String.valueOf(column.getText())))
                .doesNotContain(I18n.t("column.comment"))
                .contains(I18n.t("column.name"), I18n.t("detail.type"),
                        I18n.t("structure.col.key"));
    }

    @Test
    void commentFieldIsDisabledWhenDialectHasNoComment(FxRobot robot) throws Exception {
        // 同一个道理用在对话框上：输入框可打字但存不住，是数据丢失幻觉。
        waitForStructure();
        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        TextInputControl comment = robot.lookup("#structure-dialog-comment").queryTextInputControl();
        assertThat(comment.isDisabled()).isTrue();
        assertThat(comment.getPromptText())
                .isEqualTo(I18n.t("structure.dialog.commentUnsupported", "SQLite"));
    }

    // ─────────────────────── ⭐ D-71：Add Field 与建表网格同能力 ───────────────────────

    /**
     * ⭐ D-71（一）：同一件事两条通道的能力必须一样。
     *
     * <p>立案事实：新帧 {@code p7-new-column} 里这个弹窗只有 name / type / default / comment /
     * Allow NULL / Unique 六项，而 {@code p6-new-table-fields} 的建表网格有
     * Length / Decimals / Virtual / Key —— 建表能表达的四种参数，加一列时摆不出来，
     * 用户只能先删表再建，或者去写裸 SQL。
     *
     * <p>这一条只钉「格子在不在」；能力对不对由下面四条分别钉。
     */
    @Test
    void addFieldDialogExposesTheSameFourParametersAsTheGrid(FxRobot robot) throws Exception {
        waitForStructure();
        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup("#structure-dialog-length").queryTextInputControl())
                .as("Length：与建表网格同一格").isNotNull();
        assertThat(robot.lookup("#structure-dialog-decimals").queryTextInputControl())
                .as("Decimals：与建表网格同一格").isNotNull();
        assertThat(robot.lookup("#structure-dialog-virtual").queryAs(CheckBox.class))
                .as("Virtual：生成列开关").isNotNull();
        assertThat(robot.lookup("#structure-dialog-expression").queryTextInputControl())
                .as("Virtual 的表达式格").isNotNull();
        assertThat(robot.lookup("#structure-dialog-key").queryAs(ComboBox.class))
                .as("Key：主键 / 唯一 / 无三选").isNotNull();
    }

    /**
     * ⭐ D-71（二）：长度 / 小数要真的拼进 DDL，而不是停在格子里。
     *
     * <p>读的是**生成的那条语句**（确认处理器截下来、返回 false 不执行），
     * 所以这条测的是「弹窗 → ColumnSpec → 方言」整条链，不只是控件存在。
     */
    @Test
    void addFieldLengthAndDecimalsReachTheGeneratedDdl(FxRobot robot) throws Exception {
        waitForStructure();
        AtomicReference<StructurePlan> plan = capturePlan(page);
        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        write(robot, "#structure-dialog-name", "amount");
        setType(robot, "DECIMAL");
        write(robot, "#structure-dialog-length", "10");
        write(robot, "#structure-dialog-decimals", "2");
        robot.clickOn(okButton(robot));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(sql(plan.get()))
                .as("DECIMAL(10,2)：长度与小数位拼进类型，与建表网格同一个出口")
                .contains("DECIMAL(10,2)");
    }

    /**
     * ⭐ D-71（三）：类型不吃参数时格子必须置灰，换类型时旧参数必须清掉。
     *
     * <p>这条钉的是「不对称的另一半」：给了 {@code INTEGER(10)} 的话，格子看着能填、
     * 填了会被库拒绝。规则与建表网格同一份（{@code ColumnTypeOption.length()/decimals()}）。
     */
    @Test
    void addFieldParameterFieldsFollowTheType(FxRobot robot) throws Exception {
        waitForStructure();
        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        setType(robot, "VARCHAR");
        TextInputControl length = robot.lookup("#structure-dialog-length").queryTextInputControl();
        TextInputControl decimals = robot.lookup("#structure-dialog-decimals").queryTextInputControl();
        assertThat(length.isDisabled()).as("VARCHAR 吃长度").isFalse();
        assertThat(decimals.isDisabled()).as("VARCHAR 不吃小数位（只有定点数吃）").isTrue();

        write(robot, "#structure-dialog-length", "32");
        setType(robot, "INTEGER");
        assertThat(length.isDisabled()).as("INTEGER 两个参数都不吃").isTrue();
        assertThat(length.getText())
                .as("换类型要把旧参数清掉 —— DECIMAL 的 10 不能跟着走进 INT")
                .isEmpty();
    }

    /**
     * ⭐ D-71（四）：勾了 Virtual 却没写表达式，不能悄悄降级成普通列。
     *
     * <p>与建表网格同一句报错（{@code newtable.error.virtualNoExpression}），
     * 且报错留在弹窗里：关掉重开等于把刚才填的六七个格子全丢。
     */
    @Test
    void addFieldVirtualNeedsAnExpressionAndReachesTheDdl(FxRobot robot) throws Exception {
        waitForStructure();
        AtomicReference<StructurePlan> plan = capturePlan(page);
        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        write(robot, "#structure-dialog-name", "name_lower");
        setType(robot, "TEXT");
        robot.clickOn("#structure-dialog-virtual");
        WaitForAsyncUtils.waitForFxEvents();

        robot.clickOn(okButton(robot));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#structure-dialog-virtual").queryAs(CheckBox.class))
                .as("没写表达式时对话框必须还开着")
                .isNotNull();
        assertThat(plan.get()).as("没写表达式不能生成一条普通列的计划").isNull();

        write(robot, "#structure-dialog-expression", "lower(name)");
        robot.clickOn(okButton(robot));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(sql(plan.get()))
                .as("表达式走 GENERATED ALWAYS AS，与建表网格同一个 ColumnSpec 字段")
                .contains("GENERATED ALWAYS AS");
    }

    /**
     * ⭐ D-71（五）：Key 选主键要带出「不为空」，并且这个选择必须真的进计划。
     *
     * <p>以前这个弹窗把 {@code primaryKey} 从 initial 一路**原样带走**，用户点不到它。
     * 「勾主键自动非空」这条规则是建表网格定的（{@code ColumnRow} 的 key 监听），
     * 这里复用同一个模型而不是再抄一遍。
     *
     * <p>⚠️ 断言停在 {@code NOT NULL} 是**量出来**的，不是保守：实测这条语句是
     * {@code ALTER TABLE "users" ADD COLUMN "code" INTEGER NOT NULL} —— 六种方言的
     * {@code addColumnSql} 都不看 {@code primaryKey()}（只有建表语句的
     * {@code createTableColumnDefinition} 会带 PRIMARY KEY），所以「给已有表加一列主键」
     * 在 DDL 层根本落不了地。把 {@code doesNotContain("PRIMARY KEY")} 写进门禁等于把这个
     * 缺口供成规范，这里不写；缺口另立条目（见台账），本条只钉「弹窗不再丢用户的选择」。
     */
    @Test
    void addFieldKeyPrimaryReachesTheDdlAndForcesNotNull(FxRobot robot) throws Exception {
        waitForStructure();
        AtomicReference<StructurePlan> plan = capturePlan(page);
        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        write(robot, "#structure-dialog-name", "code");
        setType(robot, "INTEGER");
        CheckBox nullable = robot.lookup("#structure-dialog-nullable").queryAs(CheckBox.class);
        assertThat(nullable.isSelected()).as("新列默认可空").isTrue();

        setKey(robot, NewTableDialog.KeyChoice.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(nullable.isSelected())
                .as("选了主键就自动不是可空 —— 与建表网格同一规则")
                .isFalse();

        robot.clickOn(okButton(robot));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(plan.get()).as("Key 的选择不能被「没有改动」或校验吞掉").isNotNull();
        assertThat(sql(plan.get()))
                .as("主键带出的非空必须写进语句 —— 这是这一格当下唯一能落地的部分")
                .contains("NOT NULL");
    }

    /**
     * ⭐ D-71（六）：只改 Key / 只改 Virtual 也算改动。
     *
     * <p>「没有改动」那段比较以前只看 name / type / nullable / default / comment 五项，
     * 于是**只**把 Unique 勾上会被判成「原样返回」，界面报一句「没有改动」，
     * 用户以为已经改好了。补齐四列时必须把新字段一起纳入比较。
     */
    @Test
    void changingOnlyTheKeyIsNotReportedUnchanged(FxRobot robot) throws Exception {
        waitForStructure();
        AtomicReference<StructurePlan> plan = capturePlan(page);
        selectColumn(robot, "email");
        robot.clickOn("#structure-edit");
        WaitForAsyncUtils.waitForFxEvents();

        setKey(robot, NewTableDialog.KeyChoice.UNIQUE);
        robot.clickOn(okButton(robot));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(plan.get())
                .as("只动 Key 也要生成计划，不能被「没有改动」吞掉")
                .isNotNull();
        assertThat(sql(plan.get())).contains("UNIQUE");
    }

    /**
     * ⭐ D-93（D-71 撞出来的新条目）：工具条的可用态必须跟着选择走。
     *
     * <p>写上面那条「只改 Key」的门禁时，弹窗根本打不开 —— 一查：
     * {@code updateButtons()} 只在加载 / 执行前后调用，选中一列不刷新它，于是
     * 用户点中一行之后 Edit / 重命名 / 删列<b>仍然是灰的</b>，挂在按钮上的 tooltip
     * 还在说「先选中一列」。同一个弹窗从双击和右键菜单都进得去，唯独工具条那颗点不动。
     */
    @Test
    void toolbarButtonsFollowTheColumnSelection(FxRobot robot) throws Exception {
        waitForStructure();
        Button edit = robot.lookup("#structure-edit").queryAs(Button.class);
        Button rename = robot.lookup("#structure-rename").queryAs(Button.class);
        Button drop = robot.lookup("#structure-drop").queryAs(Button.class);
        assertThat(edit.isDisabled()).as("没选列时 Edit 置灰").isTrue();
        assertThat(rename.isDisabled()).as("没选列时重命名置灰").isTrue();
        assertThat(drop.isDisabled()).as("没选列时删列置灰").isTrue();

        selectColumn(robot, "email");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(edit.isDisabled())
                .as("选中一列后 Edit 必须可用 —— 否则工具条是死按钮，tooltip 还在说假话")
                .isFalse();
        assertThat(rename.isDisabled()).as("重命名同样要跟着选择走").isFalse();
        assertThat(drop.isDisabled()).as("删列同样要跟着选择走").isFalse();
    }

    /**
     * ⭐ D-71（七）：「这一列吃不吃长度 / 小数」只许有一个出口。
     *
     * <p>建表网格和结构页的 Add Field 弹窗是同一条能力的两条通道。这两处都不许自己去
     * 遍历方言的类型候选表 —— 一旦有第二份匹配代码，改一处漏一处，弹窗里就会带上
     * {@code INT(10)} 这种库根本不认的东西，而且两边各红各的门禁，谁也对不上。
     *
     * <p>扫的是源码而不是行为：行为上「两份规则刚好一致」是测不出来的，
     * 只有位置能钉住「只有一个地方在回答这个问题」。
     */
    @Test
    void typeParameterQuestionHasExactlyOneAnswerPoint() throws Exception {
        String marker = "candidate.name().equalsIgnoreCase(";
        Path root = repositoryRoot();
        for (String relative : List.of(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/StructurePage.java",
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/NewTableDialog.java")) {
            String source = Files.readString(root.resolve(relative));
            assertThat(source)
                    .as(relative + " 不许自己再遍历一遍类型候选表 —— 问 ColumnTypeOption.option")
                    .doesNotContain(marker);
        }
        // 出口自己必须还在，否则上面两句是恒真的空门禁。
        assertThat(Files.readString(root.resolve(
                "workbench/adb-jdbc/src/main/java/io/agenticdb/jdbc/ColumnTypeOption.java")))
                .as("匹配类型候选表这件事必须仍然只有一个地方在做")
                .contains(marker);
    }

    /** 从模块目录往上找到仓库根 —— 与其余源码扫描型门禁同一个找法。 */
    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("core"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }

    /** 截住计划并取消执行：断言生成出来的 SQL，不需要真的改库。 */
    private static AtomicReference<StructurePlan> capturePlan(StructurePage page) {
        AtomicReference<StructurePlan> captured = new AtomicReference<>();
        page.setConfirmationHandler(candidate -> {
            captured.set(candidate);
            return false;
        });
        return captured;
    }

    private static String sql(StructurePlan plan) {
        return plan == null ? "" : String.join(";\n", plan.statements());
    }

    private void write(FxRobot robot, String id, String value) {
        TextInputControl field = robot.lookup(id).queryTextInputControl();
        robot.clickOn(field);
        robot.write(value);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void setType(FxRobot robot, String value) {
        @SuppressWarnings("unchecked")
        ComboBox<String> type = robot.lookup("#structure-dialog-type").queryAs(ComboBox.class);
        robot.interact(() -> type.setValue(value));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void setKey(FxRobot robot, NewTableDialog.KeyChoice choice) {
        @SuppressWarnings("unchecked")
        ComboBox<NewTableDialog.KeyChoice> key =
                (ComboBox<NewTableDialog.KeyChoice>) (ComboBox<?>) robot
                        .lookup("#structure-dialog-key").queryAs(ComboBox.class);
        robot.interact(() -> key.setValue(choice));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void selectColumn(FxRobot robot, String name) {
        @SuppressWarnings("unchecked")
        var table = (javafx.scene.control.TableView<io.agenticdb.model.ColumnDef>) robot
                .lookup("#structure-columns").queryAs(javafx.scene.control.TableView.class);
        robot.interact(() -> table.getSelectionModel().select(
                table.getItems().stream()
                        .filter(column -> column.name().equalsIgnoreCase(name))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("没有这一列：" + name))));
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ─────────────────────── UX-6 选项页签 ───────────────────────

    /**
     * SQLite 没有表级引擎 / 排序规则 / 行格式 —— 四项必须显示「—」，
     * 并且说明为什么是「—」，而不是给每条连接都编一个 InnoDB。
     */
    @Test
    void optionsTabIsHonestWhenTheDialectHasNoTableOptions(FxRobot robot) throws Exception {
        waitForStructure();
        selectTab(robot, I18n.t("structure.tab.options"));

        assertThat(robot.lookup("#structure-options-engine").queryAs(Label.class).getText())
                .isEqualTo("—");
        assertThat(robot.lookup("#structure-options-collation").queryAs(Label.class).getText())
                .isEqualTo("—");
        assertThat(robot.lookup("#structure-options-auto-increment")
                .queryAs(Label.class).getText()).isEqualTo("—");
        assertThat(robot.lookup("#structure-options-row-format").queryAs(Label.class).getText())
                .isEqualTo("—");
        assertThat(robot.lookup("#structure-options-hint").queryAs(Label.class).getText())
                .as("「—」要给出来源，否则用户以为功能坏了")
                .isEqualTo(I18n.t("structure.options.dialectNone", "SQLite"))
                .contains("SQLite");
    }

    /** MySQL 形态的四个值要能如实落到页面上（真实取值由真库契约守）。 */
    @Test
    void optionsTabShowsEngineCollationAutoIncrementAndRowFormat(FxRobot robot) throws Exception {
        waitForStructure();
        selectTab(robot, I18n.t("structure.tab.options"));

        robot.interact(() -> page.renderOptions(
                new TableOptions("InnoDB", "utf8mb4_0900_ai_ci", 42L, "Dynamic")));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup("#structure-options-engine").queryAs(Label.class).getText())
                .isEqualTo("InnoDB");
        assertThat(robot.lookup("#structure-options-collation").queryAs(Label.class).getText())
                .isEqualTo("utf8mb4_0900_ai_ci");
        assertThat(robot.lookup("#structure-options-auto-increment")
                .queryAs(Label.class).getText()).isEqualTo("42");
        assertThat(robot.lookup("#structure-options-row-format").queryAs(Label.class).getText())
                .isEqualTo("Dynamic");
        assertThat(robot.lookup("#structure-options-hint").queryAs(Label.class).getText())
                .contains("information_schema");

        // 没有自增列时不能显示 0（0 是一个看起来像真的值）。
        robot.interact(() -> page.renderOptions(
                new TableOptions("InnoDB", "utf8mb4_0900_ai_ci", null, "Dynamic")));
        assertThat(robot.lookup("#structure-options-auto-increment")
                .queryAs(Label.class).getText()).isEqualTo("—");
    }

    private static void selectTab(FxRobot robot, String title) {
        javafx.scene.control.TabPane tabs =
                robot.lookup("#structure-tabs").queryAs(javafx.scene.control.TabPane.class);
        robot.interact(() -> tabs.getSelectionModel().select(
                tabs.getTabs().stream()
                        .filter(tab -> title.equals(tab.getText()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("没有页签：" + title))));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 报"哪一排"：这些内联工具条没有 id，用排里第一个文字项的内容代指。 */
    private static String who(javafx.scene.Node node) {
        if (node instanceof javafx.scene.layout.Pane pane) {
            for (javafx.scene.Node child : pane.getChildren()) {
                if (child instanceof javafx.scene.control.Labeled labeled
                        && !labeled.getText().isEmpty()) {
                    return "「" + labeled.getText() + "」那一排";
                }
            }
        }
        return node.getStyleClass().toString();
    }

    private static Button okButton(FxRobot robot) {
        return robot.lookup(node -> node instanceof Button button
                        && I18n.t("common.ok").equals(button.getText()))
                .queryAs(Button.class);
    }

    @Test
    void constraintColumnsUseGlyphGrammarNotText(FxRobot robot) throws Exception {
        // U9 补漏（p4-structure 第三拍）：可空/主键以前渲染文本 YES/NO/PK，
        // 与同一张表里「唯一」列的图标语法、以及数据网格/查询结果的布尔语法都不是一套。
        waitForStructure();

        @SuppressWarnings("unchecked")
        var table = (javafx.scene.control.TableView<io.agenticdb.model.ColumnDef>) robot
                .lookup("#structure-columns").queryAs(javafx.scene.control.TableView.class);

        // id/name/email 三格「可空」都有字形（x 或 ✓），id 另有「主键」✓ → ≥4 个 .adb-check。
        assertThat(robot.from(table).lookup(".adb-check").queryAll())
                .hasSizeGreaterThanOrEqualTo(4);
        var cellTexts = new java.util.ArrayList<String>();
        for (javafx.scene.Node node : robot.from(table).lookup(".table-cell").queryAll()) {
            cellTexts.add(((javafx.scene.control.TableCell<?, ?>) node).getText());
        }
        assertThat(cellTexts).doesNotContain("YES", "NO", "PK");
    }

    @Test
    void cannotRunTwoStructureChangesAtOnce(FxRobot robot) throws Exception {
        waitForStructure();
        robot.clickOn("#structure-refresh");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#structure-add").queryAs(Button.class).isDisabled()).isFalse();
    }

    private void waitForStructure() throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.structure() != null);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
