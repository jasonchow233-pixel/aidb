package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 新建表对话框的门禁。
 *
 * <p>除了「生成的 SQL 对不对」，这里还要钉住一件事：
 * <b>建表是弹窗，不是标签页</b>（见 {@code docs/UI-FORMS-AND-STATES.md} §4）。
 * 做成标签页会留下一个关不掉的「空表」标签 —— 表还没建呢，标签已经在那儿了。
 */
@ExtendWith(ApplicationExtension.class)
class NewTableDialogTest {

    private FakeDbSession session;

    @Start
    void start(Stage stage) {
        session = FakeDbSession.of(DialectKind.MYSQL);
        stage.show();
    }

    @Test
    void startsWithOneIntegerPrimaryKeyRow() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        assertThat(dialog.rows()).hasSize(1);
        NewTableDialog.ColumnRow first = dialog.rows().getFirst();
        assertThat(first.name.get()).isEqualTo("id");
        assertThat(first.primaryKey()).isTrue();
        assertThat(first.nullable.get())
                .as("D-88：主键列不能为 NULL —— 默认就该是不可空，别等用户填完再报错")
                .isFalse();
        assertThat(first.autoIncrement.get())
                .as("R31/G15：默认的 id 主键要带自增，否则建完还得以手填 id")
                .isTrue();
    }

    @Test
    void previewShowsTheSqlBeforeTheUserCommits() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("CREATE TABLE `orders`")
                .contains("`id` INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY");
    }

    @Test
    void duplicateTableNameIsRefusedWithAReadableReason() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of("users")));
        onFxRun(() -> dialog.nameField().setText("users"));

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(NewTableDialog.problem(session, "users",
                java.util.List.of(row("id", true)), List.of("users")))
                .contains(I18n.t("table.error.exists", "users"))
                .doesNotContain("⚠");
    }

    @Test
    void aTableWithoutColumnsCannotBeCreated() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));
        onFxRun(() -> dialog.rows().clear());
        // 清空行数会触发 rows 监听 → refreshPreview 自己把预览清成空，
        // 不需要（也不能）再手动 setText —— CodeArea 没有 setText。

        assertThat(onFx(dialog::buildPlan)).isNull();
    }

    /**
     * ⭐ D-88：极性翻正后主键联动的是「可空」—— 选上主键把可空勾掉，
     * 而不是旧世界的「勾上不为空」。行为等价，但共享同一颗属性语义后，
     * 新建表网格与 Add Field 弹窗（StructurePage 桥接）不再有正反两套。
     */
    @Test
    void checkingPrimaryKeyTurnsOffNullable() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        NewTableDialog.ColumnRow row = dialog.rows().getFirst();
        onFxRun(() -> {
            row.key.set("");
            row.nullable.set(true);
            row.key.set(NewTableDialog.ColumnRow.KEY_PRIMARY);
        });

        assertThat(row.nullable.get()).isFalse();
    }

    /**
     * ⭐ D-88 的核心行为门：可空复选框直接驱动 DDL 的 NOT NULL 子句 ——
     * 默认勾着（可空）→ 没有 NOT NULL；勾掉 → NOT NULL 进定义。
     * 旧世界这颗格子是反着绑的「不为空」，谁也说不清它勾上到底建出什么。
     */
    @Test
    void nullableCheckboxDrivesTheNotNullClause() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("orders");
            NewTableDialog.ColumnRow plain = typedRow(dialog, "memo", "TEXT");
            dialog.rows().add(plain);
        });

        String nullableDdl = onFx(() -> dialog.previewArea().getText());
        assertThat(nullableDdl).contains("`memo` TEXT").doesNotContain("`memo` TEXT NOT NULL");

        onFxRun(() -> dialog.rows().getLast().nullable.set(false));
        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("`memo` TEXT NOT NULL");
    }

    @Test
    void aValidFormProducesAPlan() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));

        StructurePlan plan = onFx(dialog::buildPlan);

        assertThat(plan).isNotNull();
        assertThat(plan.statements()).isNotEmpty();
        // R45 G3：计划只带「键 + 参数」，文字在渲染口才长出来。
        // 这里走一次口，顺带证明资源包里确实有这个键 —— 掉字会露出 ⚠。
        assertThat(plan.titleKey()).isEqualTo("plan.title.createTable");
        assertThat(plan.titleArgs()).containsExactly("orders");
        assertThat(StructurePlanRunner.title(plan)).contains("orders").doesNotContain("⚠");
    }

    /**
     * 形态门禁：建表必须是<b>弹窗</b>，哪怕 R30-D 把弹窗内部做成了多标签设计器。
     *
     * <p>直接断言「它是个 Dialog 而不是 Tab」在编译期就定了，测不出回退；
     * 真正会回退的是<b>后来有人把它搬进工作台的标签页</b> —— 所以这里扫源码：
     * 不许出现 {@code TabKind} / {@code openTab}（工作台标签 API）。
     *
     * <p>⚠️ 「不许出现 {@code new Tab(}」这条在 R30-D 之后不成立了：设计器的七个页
     * 本来就是 {@link javafx.scene.control.Tab}。所以这里同时钉住<b>正向</b>证据 ——
     * 页签是弹窗<b>内部</b>的 TabPane，外壳仍是 Dialog。
     */
    @Test
    void newTableIsADialogNotATab() throws Exception {
        Path source = Path.of("src/main/java/io/agenticdb/ui/shell/NewTableDialog.java");
        assertThat(Files.exists(source)).isTrue();
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(text).contains("new Dialog<");
        assertThat(text)
                .as("设计器的七个页签是弹窗内部的 TabPane（R30-D），不是工作台标签")
                .contains("new TabPane(");
        assertThat(text)
                .as("建表不能做成工作台标签页 —— 见 docs/UI-FORMS-AND-STATES.md §4")
                .doesNotContain("TabKind")
                .doesNotContain("openTab");
    }

    // ─────────────────────── §13 布局与规格 ───────────────────────

    /**
     * 规格 §13.1：弹窗 850–1000px。
     *
     * <p>720px 是实测出来的下限 —— 再窄，「默认值」和「注释」两列
     * 会被压得只剩一个省略号，用户改一个默认值先得横向滚。
     */
    @Test
    void dialogIsWideEnoughForTheColumnGrid() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        double width = onFx(() -> dialog.dialog().getDialogPane().getPrefWidth());
        assertThat(width).isBetween(850.0, 1000.0);
    }

    /**
     * 规格 §13.2 + N26：标题走系统标题栏 —— 「新建表」+ <b>真实库名</b>，内容区不再有自绘抬头条。
     *
     * <p>库名来自会话标签，不是写死的示例串 —— 这条断言同时防
     * 「标题退化成一句通用文案」和「拿假库名充数」两种回退。
     */
    @Test
    void headerNamesTheRealDatabaseAndOffersClose() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        String label = onFx(session::label);

        String title = onFx(() -> dialog.dialog().getTitle());
        assertThat(title)
                .as("系统标题栏 = 「新建表」+ 真实库名")
                .startsWith(I18n.t("ribbon.newTable"))
                .contains(DialogStyles.shortenContext(label, DialogStyles.PATH_BUDGET));

        assertThat(onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookupAll(".adb-dialog__context")))
                .as("N26：内容区不再画自绘抬头条；关窗走系统标题栏的 × 与 Esc")
                .isEmpty();
    }

    /**
     * 规格 §13.4：SQL 预览是**只读的 SQL 编辑器**，不是 TextArea。
     *
     * <p>判据有三条，缺一条就是退化成灰底文本框：
     * 等宽字体 / 行号 / 语法高亮样式段。
     */
    @Test
    void sqlPreviewIsARealReadOnlyEditor() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));

        org.fxmisc.richtext.CodeArea area = onFx(dialog::previewArea);
        // ⚠️ 先落到局部变量再断言：`assertThat(onFx(area::getText))` 会让
        //    编译器在 assertThat 的一堆重载里挑花眼（方法引用 vs lambda）。
        String sql = onFx(area::getText);
        assertThat(sql).contains("CREATE TABLE");
        assertThat(onFx(() -> Boolean.valueOf(area.isEditable()))).isFalse();
        // 行号：ParagraphGraphicFactory 挂了才会返回非 null。
        assertThat(onFx(() -> area.getParagraphGraphicFactory())).isNotNull();
        // 高亮：非空文本上必然有样式段（纯 TextArea 一条都没有）。
        assertThat(onFx(() -> area.getStyleSpans(0).getSpanCount())).isPositive();
    }

    /**
     * 规格 §13.4 / R30-D：SQL 预览是设计器的<b>最后一页</b>，不是一条可折叠的装订线。
     *
     * <p>以前它是字段页底下的一条窄条，折叠按钮一按就整条消失 —— 用户看不到
     * 「索引 / 外键 / 触发器是不是也写进了这条语句」。独立成页之后，预览与执行
     * 同源这件事才有地方看。
     */
    @Test
    void sqlPreviewIsItsOwnDesignerTab() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        javafx.scene.control.Tab sqlTab = onFx(() -> dialog.tabs().getTabs().getLast());
        assertThat(onFx(sqlTab::getText)).isEqualTo(I18n.t("newtable.tab.sql"));
        assertThat(onFx(sqlTab::getId)).isEqualTo("new-table-tab-sql");
        assertThat(onFx(sqlTab::isClosable)).isFalse();
        assertThat(onFx(() -> sqlTab.getContent().lookup("#new-table-preview")))
                .as("预览编辑器必须真的挂在这一页上")
                .isNotNull();
        assertThat(onFx(() -> dialog.tabs().getTabs().size()))
                .as("字段 / 索引 / 外键 / 检查 / 触发器 / 选项 / 备注 / SQL 预览")
                .isEqualTo(8);
    }

    /**
     * R30-D / R31：八页的<b>顺序</b>就是 Navicat 表设计器的顺序。
     *
     * <p>顺序不是审美问题：文档里写的是「字段 → 索引 → 外键 → 检查 → 触发器 → 选项 → 备注 → SQL」，
     * 用户按文档找页签。空表时各对象页的标题不带计数，方便这条断言钉死标题。
     */
    @Test
    void designerPagesAreInNavicatOrder() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        List<String> titles = onFx(() -> dialog.tabs().getTabs().stream()
                .map(javafx.scene.control.Tab::getText).toList());

        assertThat(titles).containsExactly(I18n.t("newtable.tab.fields"),
                I18n.t("structure.tab.indexes"), I18n.t("structure.tab.foreignKeys"),
                I18n.t("structure.tab.checks"), I18n.t("structure.tab.triggers"),
                I18n.t("structure.tab.options"),
                I18n.t("newtable.tab.comment"), I18n.t("newtable.tab.sql"));
    }

    /** R30-D：页签名上带计数 —— 在字段页也看得见「索引页里有两条」。 */
    @Test
    void objectTabsCountWhatIsInsideThem() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        onFxRun(() -> dialog.indexes().add(new io.agenticdb.introspect.IndexDef(
                "idx_users_name", false, List.of("id"), "c", "", List.of())));

        assertThat(onFx(() -> dialog.tabs().getTabs().get(1).getText()))
                .isEqualTo(I18n.t("newtable.tabCount", I18n.t("structure.tab.indexes"), 1));
        assertThat(onFx(() -> dialog.tabs().getTabs().get(2).getText()))
                .as("没有外键时标题保持干净")
                .isEqualTo(I18n.t("structure.tab.foreignKeys"));

        onFxRun(() -> dialog.checks().add(new io.agenticdb.introspect.CheckDef("chk_id", "id > 0")));
        assertThat(onFx(() -> dialog.tabs().getTabs().get(3).getText()))
                .as("H4-b：CHECK 页与其他对象页同一套计数")
                .isEqualTo(I18n.t("newtable.tabCount", I18n.t("structure.tab.checks"), 1));
    }

    /**
     * ⭐ H4-b：CHECK 与字段收敛进同一个建表计划 —— 预览里看到的子句
     * 就是 {@code buildPlan()} 交给执行器的那一句，不多不少一条 CREATE。
     */
    @Test
    void checksGoIntoTheSameCreatePlanAsTheFields() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("orders");
            dialog.checks().add(new io.agenticdb.introspect.CheckDef("chk_orders_id", "id > 0"));
        });

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("CONSTRAINT `chk_orders_id` CHECK (id > 0)");
        StructurePlan plan = onFx(dialog::buildPlan);
        assertThat(plan).isNotNull();
        assertThat(plan.statements()).hasSize(1);
        assertThat(plan.statements().getFirst())
                .contains("CONSTRAINT `chk_orders_id` CHECK (id > 0)");
    }

    /** SQLite 允许匿名 CHECK —— 名字留空就不该长出 CONSTRAINT 关键字。 */
    @Test
    void anonymousCheckOmitsTheConstraintName() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("orders");
            dialog.checks().add(new io.agenticdb.introspect.CheckDef("", "id > 0"));
        });

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("\n    CHECK (id > 0)")
                .doesNotContain("CONSTRAINT");
    }

    /** 校验失败落在哪一页就把用户带到哪一页 —— CHECK 的问题去 CHECK 页（D-69 同族路由）。 */
    @Test
    void checkProblemsRouteToTheChecksTab() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("orders");
            dialog.checks().add(new io.agenticdb.introspect.CheckDef("chk_blank", "   "));
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> dialog.tabs().getSelectionModel().getSelectedItem().getId()))
                .isEqualTo("new-table-tab-checks");
    }

    /**
     * ⭐ R76 H4-b：`p6-new-table-checks` 的<b>帧前提门</b>。
     *
     * <p>pose 名打错一个字母、页签 id 改了、约束没进列表 —— 帧照常拍出默认页、
     * 返回码照样是 0，那张帧从此安静地不再证明任何东西（D-98 的假帧形状）。
     * 这条门禁把帧声称的三件事钉死：选中的是检查页、列表里真有一条约束、
     * SQL 预览里真有同一段子句。
     */
    @Test
    void checksPoseSelectsTheChecksTabAndShowsTheConstraintTheFrameClaims() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.poseForShots("checks"));

        assertThat(onFx(() -> dialog.tabs().getSelectionModel().getSelectedItem().getId()))
                .as("检查页帧必须真的落在检查页上")
                .isEqualTo("new-table-tab-checks");
        List<io.agenticdb.introspect.CheckDef> posed = onFx(() -> List.copyOf(dialog.checks()));
        assertThat(posed).as("帧里要有一条真约束，空列表证明不了 CHECK 收进了计划").hasSize(1);
        assertThat(onFx(() -> dialog.previewArea().getText()))
                .as("预览必须带上列表里那一条 —— 帧拍的就是「同一条计划」")
                .contains("CHECK (" + posed.getFirst().expression() + ")");
    }

    /**
     * 字段网格的高度跟着行数走 —— 只有一行时不许挂 170px 空白网格
     * （规格 §13.3「字段较少时不要显示过高的空白表格」）。
     */
    @Test
    void columnGridShrinksWhenThereAreFewColumns() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        double oneRow = onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookup("#new-table-columns").prefHeight(-1));

        onFxRun(() -> {
            for (int i = 0; i < 5; i++) {
                // onChange 传空实现：这条断言只看高度，不想顺带触发整表重算。
                dialog.rows().add(new NewTableDialog.ColumnRow(() -> { }));
            }
        });
        double sixRows = onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookup("#new-table-columns").prefHeight(-1));

        assertThat(sixRows).isGreaterThan(oneRow);
        // 一行时：表头 ~34 + 一行 30 ≈ 64，夹在 [120, 300] 里就是下限 120。
        assertThat(oneRow).isLessThanOrEqualTo(120.0);
    }

    /**
     * ⭐ R43-c：留白不许落在中间。
     *
     * <p>弹窗固定 680px 高（页签之间不许跳），只有四列时中间那条带子有 130px 没主。
     * 原来那一段全塞给了网格（{@code VBox.setVgrow(table, ALWAYS)}）—— 可网格的高度是
     * {@code fitTableHeight} 按行数算出来的，多出来的一截是<b>没有归属的白</b>，
     * 看着像还有几行没填：正是 fitTableHeight 注释里要治的那个毛病，被这句 vgrow 放了回来。
     *
     * <p>现在「工具条 / 网格 / 属性面板」三段作为一个整体在页签内容区里居中，
     * 留白回到上下两端，工具条仍然贴着自己的网格。
     * 钉的是<b>结构</b>（谁不许长、谁居中、三段是一组），不是量高度 —— 弹窗没 show 出来
     * 就没有场景，量到的全是 0，那条断言会静默通过。「居中这一句到底生效没有」由
     * {@code p6-new-table-fields} 重拍帧目视确认（R43-b 的教训：改对齐必须回拍）。
     */
    @Test
    void spareHeightGoesToTheEndsNotBetweenGridAndPanel() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        javafx.scene.layout.Priority gridGrow = onFx(() -> javafx.scene.layout.VBox
                .getVgrow(dialog.columnsTable()));
        javafx.scene.layout.VBox page = onFx(() ->
                (javafx.scene.layout.VBox) dialog.columnsTable().getParent());

        assertThat(gridGrow)
                .as("网格自己不许长 —— 长出来的那一段就是没有归属的白"
                        + "（VBox 没显式设过 vgrow 时读到的是 null，那同样表示「不长」）")
                .isNotEqualTo(javafx.scene.layout.Priority.ALWAYS);
        assertThat(page).as("网格外面那一层就是字段页（页签的 content）").isNotNull();
        assertThat(page.getAlignment())
                .as("三段作为一个整体垂直居中：留白回到上下两端")
                .isEqualTo(javafx.geometry.Pos.CENTER);
        assertThat(onFx(() -> page.getChildren().size()))
                .as("字段页就三格：工具条 / 网格 / 属性面板，中间不许再垫东西")
                .isEqualTo(3);
        assertThat(onFx(() -> page.getChildren().getFirst()))
                .as("第一格是字段工具条，它和网格是一组（不许被拆开）")
                .isInstanceOf(javafx.scene.layout.HBox.class);
    }

    // ─────────────────────── S-4：网格键盘导航 ───────────────────────

    /**
     * ⭐ S-4：方向键在网格里一格一格走（行、列都能走）。
     *
     * <p>没有这条之前，方向键只换行不选列 —— 7 列里有 4 列要手填，每次都得用鼠标点准。
     */
    @Test
    void arrowKeysMoveOneCellAtATimeInBothDirections() {
        NewTableDialog.CellPos middle = new NewTableDialog.CellPos(2, 3);

        assertThat(NewTableDialog.step(middle, javafx.scene.input.KeyCode.UP, 5, 7))
                .isEqualTo(new NewTableDialog.CellPos(1, 3));
        assertThat(NewTableDialog.step(middle, javafx.scene.input.KeyCode.DOWN, 5, 7))
                .isEqualTo(new NewTableDialog.CellPos(3, 3));
        assertThat(NewTableDialog.step(middle, javafx.scene.input.KeyCode.LEFT, 5, 7))
                .isEqualTo(new NewTableDialog.CellPos(2, 2));
        assertThat(NewTableDialog.step(middle, javafx.scene.input.KeyCode.RIGHT, 5, 7))
                .isEqualTo(new NewTableDialog.CellPos(2, 4));
    }

    /**
     * ⭐ S-4：走到边界要<b>停住</b>，不能绕圈 —— 按 ↓ 从最后一行绕回第一行会让用户迷路。
     * 出界返回 {@code null}，由调用方保持原地。
     */
    @Test
    void movingStopsAtTheEdgesInsteadOfWrappingAround() {
        NewTableDialog.CellPos topLeft = new NewTableDialog.CellPos(0, 0);
        NewTableDialog.CellPos bottomRight = new NewTableDialog.CellPos(4, 6);

        assertThat(NewTableDialog.move(topLeft, -1, 0, 5, 7)).isNull();
        assertThat(NewTableDialog.move(topLeft, 0, -1, 5, 7)).isNull();
        assertThat(NewTableDialog.move(bottomRight, 1, 0, 5, 7)).isNull();
        assertThat(NewTableDialog.move(bottomRight, 0, 1, 5, 7)).isNull();
        assertThat(NewTableDialog.move(null, 1, 0, 5, 7))
                .as("没有当前格时无从起步")
                .isNull();
    }

    /** ⭐ S-4：只有方向键在分管导航 —— 空格 / 回车留给 {@code BooleanCell} 切换。 */
    @Test
    void onlyArrowKeysDoNavigation() {
        NewTableDialog.CellPos pos = new NewTableDialog.CellPos(1, 1);

        assertThat(NewTableDialog.step(pos, javafx.scene.input.KeyCode.SPACE, 5, 7))
                .as("空格是布尔列的切换键 —— 导航不许抢")
                .isNull();
        assertThat(NewTableDialog.step(pos, javafx.scene.input.KeyCode.ENTER, 5, 7))
                .as("回车是布尔列的切换键")
                .isNull();
        assertThat(NewTableDialog.step(pos, javafx.scene.input.KeyCode.TAB, 5, 7))
                .as("Tab 走的是 next/previous，不是 step")
                .isNull();
    }

    /** ⭐ S-4：Tab 按阅读顺序走 —— 往右一格，行尾换行首。 */
    @Test
    void tabWalksTheWholeGridInReadingOrder() {
        assertThat(NewTableDialog.next(new NewTableDialog.CellPos(0, 0), 5, 7))
                .isEqualTo(new NewTableDialog.CellPos(0, 1));
        assertThat(NewTableDialog.next(new NewTableDialog.CellPos(0, 6), 5, 7))
                .as("行尾换行首")
                .isEqualTo(new NewTableDialog.CellPos(1, 0));
        assertThat(NewTableDialog.next(null, 5, 7))
                .as("还没有焦点时从第一格起步")
                .isEqualTo(new NewTableDialog.CellPos(0, 0));

        assertThat(NewTableDialog.previous(new NewTableDialog.CellPos(1, 0), 5, 7))
                .as("⇧Tab 反向：行首换行尾")
                .isEqualTo(new NewTableDialog.CellPos(0, 6));
        assertThat(NewTableDialog.previous(new NewTableDialog.CellPos(0, 1), 5, 7))
                .isEqualTo(new NewTableDialog.CellPos(0, 0));
    }

    /**
     * ⭐ S-4：最后一格必须把 Tab 让出去 —— 否则用户永远出不了这张网格，
     * 那就是「键盘可达」的反面（能进来、出不去）。
     */
    @Test
    void theLastCellLetsTabOutOfTheGrid() {
        assertThat(NewTableDialog.next(new NewTableDialog.CellPos(4, 6), 5, 7))
                .as("最后一行最后一格再按 Tab 就没有下一格了")
                .isNull();
        assertThat(NewTableDialog.previous(new NewTableDialog.CellPos(0, 0), 5, 7))
                .as("第一格再按 ⇧Tab 也没有上一格了")
                .isNull();
    }

    /**
     * ⭐ S-4：逻辑算得对不等于装上了 —— 这条盯「那张网格真的挂了这个键处理器」。
     *
     * <p>纯方法全绿但忘了 {@code setOnKeyPressed} 是最容易溜过去的一种假绿。
     */
    @Test
    void gridNavigationIsActuallyWiredToTheColumnsTable() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        assertThat(dialog.columnsTable().getOnKeyPressed())
                .as("字段网格必须挂着按键处理器，否则前面几条门禁测的是没人调用的逻辑")
                .isNotNull();
    }

    /** JavaFX 控件只能在 FX 线程上创建。 */
    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static io.agenticdb.model.ColumnSpec row(String name, boolean primaryKey) {
        return new io.agenticdb.model.ColumnSpec(name, "INTEGER", false, null, null,
                false, primaryKey);
    }

    // ───────────────── 第十八轮：只读预览不许教人「执行」 ─────────────────

    @Test
    void readOnlySqlPreviewDoesNotTellTheUserToExecute() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        // R30-D：提示挂在预览编辑器上，而编辑器在「SQL 预览」页里 ——
        // 页签没选中时它不在 DialogPane 的 children 里，所以从编辑器这层找。
        javafx.scene.Node hint = onFx(() ->
                dialog.previewEditor().lookup(".adb-query__hint"));
        assertThat(hint).as("空态提示要真的挂上场景图").isNotNull();

        String text = onFx(() -> ((javafx.scene.control.Label) hint).getText());
        assertThat(text)
                .as("预览是只读、不可执行的 —— 沿用「⌘↵ 执行当前语句」是误导")
                .isNotNull()
                .doesNotContain("执行当前语句")
                .doesNotContain("执行全部");

        // 预览本身必须不可编辑（只读这条是前提）。
        Boolean editable = onFx(() -> dialog.previewArea().isEditable());
        assertThat(editable).isFalse();
    }

    // ───────────────── R30-D：预览空着要说清原因 ─────────────────

    @Test
    void aTakenTableNameIsExplainedInThePreviewInsteadOfStayingBlank() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of("orders")));
        onFxRun(() -> dialog.nameField().setText("orders"));

        assertThat(onFx(() -> dialog.previewArea().getText())).isEmpty();
        String hint = onFx(() -> dialog.previewEditor()
                .lookup(".adb-query__hint") instanceof javafx.scene.control.Label label
                ? label.getText() : null);
        assertThat(hint)
                .as("预览空着必须说出原因 —— 表名已填却显示「填写表名后…」会让人以为预览坏了")
                .contains(I18n.t("table.error.exists", "orders"))
                .doesNotContain("⚠")
                .doesNotContain(I18n.t("newtable.preview.emptyHint"));
    }

    @Test
    void theFriendlyPreviewHintStaysWhileTheTableNameIsBlank() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of("orders")));

        String hint = onFx(() -> dialog.previewEditor()
                .lookup(".adb-query__hint") instanceof javafx.scene.control.Label label
                ? label.getText() : null);
        assertThat(hint).contains(I18n.t("newtable.preview.emptyHint"));
    }

    // ───────────────── 规格 §13.3：布尔单元格键盘可达 ─────────────────

    @Test
    void booleanCellToggleFlipsTheUnderlyingModelProperty() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        NewTableDialog.ColumnRow row = onFx(() -> dialog.rows().getFirst());
        assertThat(row.virtual.get()).isFalse();

        // 生产在用的布尔单元格，喂给它「虚拟」这列的属性访问器。
        // 空格 / 回车的按键处理器最终就是调 toggle()，这里直接驱动同一段逻辑。
        onFxRun(() -> new NewTableDialog.BooleanCell(r -> r.virtual).toggle(row));

        assertThat(row.virtual.get())
                .as("布尔单元格的切换必须写回模型 —— 违反「一切键盘可达」硬规则")
                .isTrue();
    }

    @Test
    void booleanCellOnlyConsumesSpaceAndEnterNotNavigationKeys() {
        // 未上屏（无 TableRow）的单元格：handleKey 只按「是不是切换键」判定吃不吃，
        // 方向键 / Tab / 字母键一律交还给 TableView 导航。
        boolean[] consumed = onFx(() -> {
            NewTableDialog.BooleanCell cell = new NewTableDialog.BooleanCell(r -> r.virtual);
            return new boolean[]{
                    cell.handleKey(javafx.scene.input.KeyCode.SPACE),
                    cell.handleKey(javafx.scene.input.KeyCode.ENTER),
                    cell.handleKey(javafx.scene.input.KeyCode.RIGHT),
                    cell.handleKey(javafx.scene.input.KeyCode.TAB),
                    cell.handleKey(javafx.scene.input.KeyCode.A),
            };
        });

        assertThat(consumed[0]).as("空格必须被布尔单元格吃掉").isTrue();
        assertThat(consumed[1]).as("回车必须被布尔单元格吃掉").isTrue();
        assertThat(consumed[2]).as("方向键必须留给导航").isFalse();
        assertThat(consumed[3]).as("Tab 必须留给导航").isFalse();
        assertThat(consumed[4]).as("字母键必须留给编辑/导航").isFalse();
    }

    @Test
    void toggleKeyClassifierMatchesOnlySpaceAndEnter() {
        assertThat(NewTableDialog.BooleanCell.isToggleKey(javafx.scene.input.KeyCode.SPACE)).isTrue();
        assertThat(NewTableDialog.BooleanCell.isToggleKey(javafx.scene.input.KeyCode.ENTER)).isTrue();
        assertThat(NewTableDialog.BooleanCell.isToggleKey(javafx.scene.input.KeyCode.UP)).isFalse();
        assertThat(NewTableDialog.BooleanCell.isToggleKey(javafx.scene.input.KeyCode.TAB)).isFalse();
    }

    @Test
    void booleanCellUsesTheCustomCellFactoryNotTheDefaultOne() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        boolean usesCustom = onFx(() -> dialog.columnsTable().getColumns().stream()
                .filter(column -> I18n.t("newtable.col.key").equals(column.getText()))
                .findFirst()
                .orElseThrow()
                .getCellFactory() != null);

        assertThat(usesCustom)
                .as("布尔列必须走自定义单元格工厂（CheckBoxTableCell 键盘不可达）")
                .isTrue();
    }

    // ───────── R30-D：六页收敛成同一个建表计划（预览 = 执行） ─────────

    /**
     * ⭐ R30-D：索引 / 触发器与字段进<b>同一个</b> {@link StructurePlan}。
     *
     * <p>如果它们各走一次「弹确认框 → 单独执行」，用户看到的「一条建表语句」就和
     * 实际执行的 N 次动作对不上了 —— 预览在骗人。
     */
    @Test
    void indexesAndTriggersJoinTheSameCreateTablePlan() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));
        onFxRun(() -> {
            dialog.indexes().add(new io.agenticdb.introspect.IndexDef(
                    "idx_orders_id", true, List.of("id"), "c", "", List.of()));
            dialog.triggers().add(new io.agenticdb.introspect.TriggerDef(
                    "trg_orders_audit", "AFTER", "INSERT", "SELECT 1"));
        });

        StructurePlan plan = onFx(dialog::buildPlan);

        assertThat(plan).isNotNull();
        assertThat(plan.statements()).hasSize(3);
        assertThat(plan.statements().get(0)).contains("CREATE TABLE `orders`");
        assertThat(plan.statements().get(1)).startsWith("CREATE UNIQUE INDEX `idx_orders_id`");
        assertThat(plan.statements().get(2)).startsWith("CREATE TRIGGER `trg_orders_audit`");

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .as("预览必须显示索引 / 触发器 —— 「看到的就是执行的」")
                .contains("CREATE UNIQUE INDEX `idx_orders_id`")
                .contains("CREATE TRIGGER `trg_orders_audit`");
    }

    /**
     * ⭐ R30-D：SQLite 的外键写进 {@code CREATE TABLE}，不是 {@code ALTER TABLE}。
     *
     * <p>SQLite 不支持 {@code ALTER TABLE … ADD CONSTRAINT}；而且表还没建出来时
     * 「整表重建」根本没有对象可重建。
     */
    @Test
    void sqliteForeignKeysAreInlinedIntoCreateTable() {
        FakeDbSession sqlite = FakeDbSession.of(DialectKind.SQLITE);
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(sqlite, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));
        onFxRun(() -> dialog.foreignKeys().add(new io.agenticdb.introspect.ForeignKeyDef(
                "fk_orders_user", List.of("id"), "users", List.of("id"),
                "NO ACTION", "CASCADE")));

        StructurePlan plan = onFx(dialog::buildPlan);

        assertThat(plan).isNotNull();
        assertThat(plan.statements()).hasSize(1);
        assertThat(plan.statements().getFirst())
                .contains("CONSTRAINT \"fk_orders_user\" FOREIGN KEY (\"id\")")
                .contains("REFERENCES \"users\" (\"id\")")
                .contains("ON DELETE CASCADE")
                .doesNotContain("ALTER TABLE");
        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("FOREIGN KEY")
                .doesNotContain("ALTER TABLE");
    }

    /** R30-D：页内的「移除」必须真的把对象从计划里拿掉（不是只从画面上消失）。 */
    @Test
    void theRemoveButtonTakesAnObjectBackOutOfThePlan() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));
        io.agenticdb.introspect.IndexDef index = new io.agenticdb.introspect.IndexDef(
                "idx_orders_id", false, List.of("id"), "c", "", List.of());
        onFxRun(() -> {
            dialog.indexes().add(index);
            dialog.indexList().getSelectionModel().select(index);
        });

        onFxRun(() -> {
            // 按钮在对象页里（列表的同级兄弟），页签没选中时 DialogPane.lookup 找不到它。
            javafx.scene.Node remove = dialog.indexList().getParent()
                    .lookup("#new-table-indexes-remove");
            ((javafx.scene.control.Button) remove).fire();
        });

        assertThat(onFx(() -> dialog.indexes())).isEmpty();
        assertThat(onFx(dialog::buildPlan).statements()).hasSize(1);
    }

    /**
     * R30-D：计划因为索引不合法而失败时，用户必须被带到「索引」页 ——
     * 在「字段」页上看一句「索引引用了表里没有的列」是找不到出路的。
     */
    @Test
    void aBrokenIndexTakesTheUserToTheIndexPage() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("orders"));
        onFxRun(() -> dialog.indexes().add(new io.agenticdb.introspect.IndexDef(
                "idx_bad", false, List.of("nope"), "c", "", List.of())));

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> dialog.tabs().getSelectionModel().getSelectedItem().getId()))
                .isEqualTo("new-table-tab-indexes");
        String message = onFx(() -> ((javafx.scene.control.Label) dialog.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText());
        // R45 G3：这条原先钉「索引」两个字，钉的同样是 D-9（中文从模型层漏进英文界面）。
        assertThat(message).contains(I18n.t("index.error.columnNotFound", "idx_bad", "nope"))
                .doesNotContain("⚠");
    }

    /**
     * ⭐ R30-D：子弹窗拿到的是一张<b>草稿结构</b> —— 列就是用户正在填的那些列。
     *
     * <p>这是「复用三个弹窗的采集与校验逻辑」能成立的前提：它们的
     * 「表里没有这一列」对新表同样成立，不用另写一套规则。
     */
    @Test
    void draftStructureMirrorsTheColumnsBeingTyped() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("orders");
            dialog.rows().add(extraRow("email", "VARCHAR(255)"));
        });

        io.agenticdb.introspect.TableStructure draft = onFx(dialog::draftStructure);

        assertThat(draft.name()).isEqualTo("orders");
        assertThat(draft.columns()).extracting(io.agenticdb.model.ColumnDef::name)
                .containsExactly("id", "email");
        assertThat(draft.primaryKeys()).containsExactly("id");
        assertThat(draft.columns().get(1).rawType()).isEqualTo("VARCHAR(255)");
        assertThat(draft.indexes()).isEmpty();
    }

    /** 还没填表名时草稿叫「?」—— 子弹窗标题不许出现 null 或空串。 */
    @Test
    void draftStructureHasAPlaceholderNameBeforeTheUserTypesOne() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        assertThat(onFx(() -> dialog.draftStructure().name())).isEqualTo("?");
    }

    private static NewTableDialog.ColumnRow extraRow(String column, String type) {
        NewTableDialog.ColumnRow row = new NewTableDialog.ColumnRow(() -> { });
        row.name.set(column);
        row.type.set(type);
        return row;
    }

    // ─────────── R31：字段深度（类型 / 长度 / 小数 / 自增 / 列序 / 属性面板） ───────────

    /**
     * 用户在「类型」格里敲的是<b>完整一串</b>（{@code DECIMAL(10,2)}），提交后由
     * {@code applyRawType} 拆成类型 / 长度 / 小数三格 —— 这条 helper 走的就是同一条入口，
     * 免得测试从侧门进、把真正要守的那段逻辑绕过去。
     *
     * <p>行必须来自 {@code dialog.newRow()}：自带「改属性就重算预览」的回调。
     * 用 {@code new ColumnRow(() -> { })} 往网格里加行，写完默认值预览还是旧的 ——
     * 那是会全绿放过回退的假证据（踩过）。
     */
    private static NewTableDialog.ColumnRow typedRow(NewTableDialog dialog, String column,
            String rawType) {
        NewTableDialog.ColumnRow row = dialog.newRow();
        row.name.set(column);
        row.applyRawType(rawType);
        return row;
    }

    /** 纯逻辑断言（不需要刷新链）用的一行。 */
    private static NewTableDialog.ColumnRow rowOfType(String rawType) {
        NewTableDialog.ColumnRow row = new NewTableDialog.ColumnRow(() -> { });
        row.name.set("c");
        row.applyRawType(rawType);
        return row;
    }

    /** 类型 + 长度 + 小数三格拼回来的声明类型。 */
    @Test
    void typeLengthAndDecimalsJoinTheDdl() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.rows().add(typedRow(dialog, "email", "VARCHAR(255)"));
            dialog.rows().add(typedRow(dialog, "amount", "DECIMAL(10,2)"));
        });

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("`email` VARCHAR(255)")
                .contains("`amount` DECIMAL(10,2)");
    }

    /**
     * ⭐ 换类型必须把<b>新类型不吃</b>的参数清掉。
     *
     * <p>{@code DECIMAL(10,2)} 改成 {@code INT} 要是留着 10，DDL 里就出现
     * {@code INT(10)} —— 用户以为自己设了显示宽度，实际改的是类型。
     * 两条路都得清：敲整串（{@code applyRawType}）和只动类型格（{@code normalizeType}）。
     */
    @Test
    void changingTheTypeDropsParametersTheNewTypeCannotTake() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        NewTableDialog.ColumnRow row = onFx(() -> {
            NewTableDialog.ColumnRow typed = rowOfType("DECIMAL(10,2)");
            typed.applyRawType("INT");
            return typed;
        });
        assertThat(row.rawType()).isEqualTo("INT");
        assertThat(row.length.get()).isEmpty();
        assertThat(row.decimals.get()).isEmpty();

        NewTableDialog.ColumnRow second = onFx(() -> {
            NewTableDialog.ColumnRow typed = rowOfType("DECIMAL(10,2)");
            typed.type.set("INT");
            dialog.normalizeType(typed);
            return typed;
        });
        assertThat(second.rawType())
                .as("只改类型格（不重敲整串）也必须清掉长度 / 小数")
                .isEqualTo("INT");
    }

    /** 长度 / 小数是不是该出现，由<b>方言的类型表</b>说了算，不是由用户猜。 */
    @Test
    void onlyTypesThatTakeParametersExposeTheLengthCells() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        assertThat(dialog.takesLength(rowOfType("VARCHAR(255)"))).isTrue();
        assertThat(dialog.takesDecimals(rowOfType("VARCHAR(255)"))).isFalse();
        assertThat(dialog.takesLength(rowOfType("DECIMAL(10,2)"))).isTrue();
        assertThat(dialog.takesDecimals(rowOfType("DECIMAL(10,2)"))).isTrue();
        assertThat(dialog.takesLength(rowOfType("INT"))).isFalse();
        assertThat(dialog.takesDecimals(rowOfType("INT"))).isFalse();
        assertThat(dialog.takesLength(rowOfType("MY_CUSTOM_TYPE")))
                .as("下拉里没有的自定义类型按「不吃参数」处理，别替用户编一个长度")
                .isFalse();
    }

    /** ⭐ 非法的「长度 / 小数」组合在<b>点创建之前</b>就拒绝，并说清是哪一列。 */
    @Test
    void aDecimalScaleLargerThanItsLengthIsRefusedBeforeExecuting() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.rows().add(typedRow(dialog, "amount", "DECIMAL(10,11)"));
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        String message = onFx(() -> ((javafx.scene.control.Label) dialog.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText());
        assertThat(message).contains(I18n.t("newtable.error.decimalsTooLarge", "amount"));
        assertThat(onFx(() -> dialog.tabs().getSelectionModel().getSelectedItem().getId()))
                .as("长度错误属于字段页 —— 别把用户丢在别的页上找不着北")
                .isEqualTo("new-table-tab-columns");
    }

    /** 只有小数位没有长度是无意义的（{@code DECIMAL(,2)} 连语法都不成）。 */
    @Test
    void decimalsWithoutALengthAreRefused() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            NewTableDialog.ColumnRow row = rowOfType("DECIMAL");
            row.decimals.set("2");
            dialog.rows().add(row);
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> ((javafx.scene.control.Label) dialog.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText()))
                .contains(I18n.t("newtable.error.decimalsNeedsLength", "c"));
    }

    /** 长度不是数字就别说「执行时才报错」—— 现在就拦。 */
    @Test
    void aNonNumericLengthIsRefused() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            NewTableDialog.ColumnRow row = rowOfType("VARCHAR");
            row.length.set("abc");
            dialog.rows().add(row);
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> ((javafx.scene.control.Label) dialog.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText()))
                .contains(I18n.t("newtable.error.lengthDigits", "c"));
    }

    /**
     * 网格的列 = Navicat 表设计器的列，顺序也一样。
     *
     * <p>这条不是审美：文档里教用户「第五列是可空」（D-88 翻正后的叫法），
     * 列序一变，照着文档点就点到别的格子上。
     */
    @Test
    void theFieldGridHasTheNavicatColumnsInOrder() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        List<String> headers = onFx(() -> dialog.columnsTable().getColumns().stream()
                .map(javafx.scene.control.TableColumn::getText).toList());

        assertThat(headers).containsExactly(I18n.t("column.name"), I18n.t("detail.type"),
                I18n.t("newtable.col.length"), I18n.t("newtable.col.decimals"),
                I18n.t("newtable.col.nullable"), I18n.t("newtable.col.virtual"),
                I18n.t("newtable.col.key"), I18n.t("column.comment"));
    }

    // ─────────────── N29：生成列 / 单一键下拉 / 键长 / BINARY / Ask AI ───────────────

    /** ⭐ N29：勾「虚拟」+ 填表达式 → DDL 里是真正的 {@code GENERATED ALWAYS AS (…)}。 */
    @Test
    void virtualColumnEmitsAGeneratedColumnExpression() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            NewTableDialog.ColumnRow row = typedRow(dialog, "mail_lc", "VARCHAR(255)");
            row.virtual.set(true);
            row.generatedExpression.set("lower(email)");
            dialog.rows().add(row);
        });

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("`mail_lc` VARCHAR(255) GENERATED ALWAYS AS (lower(email)) VIRTUAL");
    }

    /**
     * ⭐ N29：勾了虚拟却没写表达式 —— 必须拦在创建之前。
     *
     * <p>悄悄降级成普通列是最坏的结局：用户以为自己建了一列算出来的值，
     * 实际建出来的是一列永远为空的普通列。
     */
    @Test
    void aVirtualColumnWithoutExpressionIsRefused() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            NewTableDialog.ColumnRow row = typedRow(dialog, "mail_lc", "VARCHAR(255)");
            row.virtual.set(true);
            dialog.rows().add(row);
        });

        assertThat(onFx(dialog::buildPlan)).isNull();
        assertThat(onFx(() -> ((javafx.scene.control.Label) dialog.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText()))
                .contains(I18n.t("newtable.error.virtualNoExpression", "mail_lc"));
    }

    /** 方言不支持生成列时不许编一条假 DDL：直接拦下并说明是哪一列。 */
    @Test
    void virtualIsRefusedWhereTheDialectHasNoGeneratedColumns() {
        NewTableDialog sqlServer = onFx(() -> NewTableDialog.create(
                FakeDbSession.of(DialectKind.SQLSERVER), List.of()));
        onFxRun(() -> {
            sqlServer.nameField().setText("members");
            NewTableDialog.ColumnRow row = typedRow(sqlServer, "mail_lc", "VARCHAR(255)");
            row.virtual.set(true);
            row.generatedExpression.set("lower(email)");
            sqlServer.rows().add(row);
        });

        assertThat(onFx(sqlServer::buildPlan)).isNull();
        assertThat(onFx(() -> ((javafx.scene.control.Label) sqlServer.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText()))
                .contains(I18n.t("newtable.error.generatedUnsupported", "SQL Server", "mail_lc"));
    }

    /** ⭐ N29：多列主键按网格顺序编号（列序就是 PRIMARY KEY 子句里的顺序）。 */
    @Test
    void multiColumnPrimaryKeyFollowsTheGridOrder() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            NewTableDialog.ColumnRow tenant = typedRow(dialog, "tenant", "INTEGER");
            tenant.key.set(NewTableDialog.ColumnRow.KEY_PRIMARY);
            dialog.rows().add(tenant);
        });

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("PRIMARY KEY (`id`, `tenant`)");
    }

    /** ⭐ N29：自增住在属性面板，且只在整型列上可用。 */
    @Test
    void autoIncrementLivesInThePropertyPanelAndOnlyForIntegerColumns() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.nameField().setText("members"));

        onFxRun(() -> dialog.columnsTable().getSelectionModel().select(0));
        assertThat(onFx(() -> dialog.fieldAutoIncrementBox().isDisable())).isFalse();
        assertThat(onFx(() -> dialog.fieldAutoIncrementBox().isSelected()))
                .as("默认的 id 主键带着自增")
                .isTrue();

        onFxRun(() -> dialog.rows().add(typedRow(dialog, "email", "VARCHAR(255)")));
        onFxRun(() -> dialog.columnsTable().getSelectionModel().select(1));
        assertThat(onFx(() -> dialog.fieldAutoIncrementBox().isDisable()))
                .as("VARCHAR 不能自增 —— 格子置灰而不是留着让人填")
                .isTrue();
        assertThat(onFx(() -> dialog.previewArea().getText())).contains("AUTO_INCREMENT");
    }

    /** ⭐ N29：键长把主键写成表级前缀键（MySQL 的 {@code PRIMARY KEY (email(20))}）。 */
    @Test
    void keyLengthEmitsATableLevelPrefixKey() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.rows().clear();
            NewTableDialog.ColumnRow row = typedRow(dialog, "email", "VARCHAR(255)");
            row.key.set(NewTableDialog.ColumnRow.KEY_PRIMARY);
            row.keyLength.set("20");
            dialog.rows().add(row);
        });

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("PRIMARY KEY (`email`(20))");
    }

    /** 不支持前缀索引的方言：键长填了也建不出来 —— 拦下并说明原因。 */
    @Test
    void keyLengthIsRefusedWhereTheDialectHasNoPrefixIndexes() {
        NewTableDialog postgres = onFx(() -> NewTableDialog.create(
                FakeDbSession.of(DialectKind.POSTGRESQL), List.of()));
        onFxRun(() -> {
            postgres.nameField().setText("members");
            postgres.rows().clear();
            NewTableDialog.ColumnRow row = typedRow(postgres, "email", "VARCHAR(255)");
            row.key.set(NewTableDialog.ColumnRow.KEY_PRIMARY);
            row.keyLength.set("20");
            postgres.rows().add(row);
        });

        assertThat(onFx(postgres::buildPlan)).isNull();
        assertThat(onFx(() -> ((javafx.scene.control.Label) postgres.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText()))
                .contains(I18n.t("newtable.error.keyLengthUnsupported", "PostgreSQL", "email"));
    }

    /** ⭐ N29：BINARY 是 MySQL 的列属性，别的方言不能假装有。 */
    @Test
    void binaryAttributeIsEmittedForMySqlAndRefusedElsewhere() {
        NewTableDialog mysql = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            mysql.nameField().setText("members");
            NewTableDialog.ColumnRow row = typedRow(mysql, "email", "VARCHAR(255)");
            row.binary.set(true);
            mysql.rows().add(row);
        });
        assertThat(onFx(() -> mysql.previewArea().getText()))
                .contains("`email` VARCHAR(255) BINARY");

        NewTableDialog postgres = onFx(() -> NewTableDialog.create(
                FakeDbSession.of(DialectKind.POSTGRESQL), List.of()));
        onFxRun(() -> {
            postgres.nameField().setText("members");
            NewTableDialog.ColumnRow row = typedRow(postgres, "email", "VARCHAR(255)");
            row.binary.set(true);
            postgres.rows().add(row);
        });
        assertThat(onFx(postgres::buildPlan)).isNull();
        assertThat(onFx(() -> ((javafx.scene.control.Label) postgres.dialog()
                .getDialogPane().getContent().lookup("#new-table-error")).getText()))
                .contains(I18n.t("newtable.error.binaryUnsupported", "PostgreSQL", "email"));
    }

    /**
     * ⭐ T9（Q-B「要么接要么删」）：第九处假按钮 {@code new-table-ask-ai} 整颗删除。
     *
     * <p>连着 {@code askAiAlert()} 说明页一起删 —— 全仓「本版未提供」说明页入口归零。
     * 变异验证：把那颗按钮或 {@code PendingMark.mark} 加回来，这条会红。
     */
    @Test
    void theAskAiEntryAndItsNoticeStayDeleted() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        javafx.scene.Node content = onFx(() -> dialog.dialog().getDialogPane().getContent());
        assertThat(onFx(() -> content.lookup("#new-table-field-count")))
                .as("前置条件：字段工具条这一层必须真的在 content 里（否则下面的 isNull 是假绿）")
                .isNotNull();
        assertThat(onFx(() -> content.lookup("#new-table-ask-ai")))
                .as("Ask AI 整颗删除（AI 是架构红线），字段工具条右端不许长回来")
                .isNull();
        assertThat(onFx(() -> content.lookupAll(".adb-pending__mark")))
                .as("字段工具条那一排不许再挂角标环").isEmpty();
        assertThat(java.util.Arrays.stream(NewTableDialog.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .as("askAiAlert / showAskAiNotice / askAiButton 三个说明页接缝必须清零")
                .doesNotContain("askAiAlert", "showAskAiNotice", "askAiButton");
    }

    /** 类型格是「点开才选」的可编辑下拉，候选来自本方言。 */
    @Test
    void theTypeCellIsAnEditableDropdownFedFromTheDialect() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        Object cell = onFx(() -> {
            javafx.scene.control.TableColumn<NewTableDialog.ColumnRow, String> type =
                    dialog.typeGridColumn();
            return (Object) type.getCellFactory().call(type);
        });

        assertThat(cell)
                .as("常驻 ComboBox 在 21px 行里会把箭头压扁 —— 用「编辑态才弹下拉」的单元格")
                .isInstanceOf(javafx.scene.control.cell.ComboBoxTableCell.class);
        javafx.scene.control.cell.ComboBoxTableCell<NewTableDialog.ColumnRow, ?> box =
                onFx(() -> (javafx.scene.control.cell.ComboBoxTableCell<NewTableDialog.ColumnRow, ?>) cell);
        assertThat(onFx(box::isComboBoxEditable))
                .as("方言专属类型（域、自定义类型）照样要能敲进去")
                .isTrue();
        assertThat(onFx(() -> box.getItems().stream().map(String::valueOf).toList()))
                .contains("VARCHAR", "DECIMAL");
    }

    /**
     * ⭐ 下拉里<b>不许出现这个库没有的类型</b>。
     *
     * <p>把 MySQL 的 {@code TINYINT} 摆给 PostgreSQL 选，用户选完要到执行才报错 ——
     * 那是把「背语法」换成了「猜哪一个能选」。
     */
    @Test
    void typeCandidatesAreRealForTheDialect() {
        NewTableDialog mysql = onFx(() -> NewTableDialog.create(session, List.of()));
        assertThat(mysql.typeCandidates()).contains("TINYINT", "MEDIUMINT", "YEAR")
                .doesNotContain("SERIAL", "BIGSERIAL");

        NewTableDialog postgres = onFx(() -> NewTableDialog.create(
                FakeDbSession.of(DialectKind.POSTGRESQL), List.of()));
        assertThat(postgres.typeCandidates())
                .contains("SERIAL", "BIGSERIAL", "DOUBLE PRECISION", "UUID")
                .doesNotContain("TINYINT", "MEDIUMINT", "YEAR");

        NewTableDialog sqlite = onFx(() -> NewTableDialog.create(
                FakeDbSession.of(DialectKind.SQLITE), List.of()));
        assertThat(sqlite.typeCandidates()).contains("INTEGER", "TEXT")
                .as("SQLite 没有 SERIAL —— 自增只认 INTEGER PRIMARY KEY AUTOINCREMENT")
                .doesNotContain("SERIAL");
    }

    /**
     * ⭐ 列顺序就是 {@code CREATE TABLE} 里的顺序 —— 上移 / 下移必须真的改到计划。
     *
     * <p>测的是「点按钮」这条路，不是私有方法的返回值：挪了画面没挪 SQL 是同一种 bug。
     */
    @Test
    void movingAColumnChangesTheOrderInTheCreateTable() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.rows().add(typedRow(dialog, "email", "VARCHAR(255)"));
            dialog.rows().add(typedRow(dialog, "note", "TEXT"));
            dialog.columnsTable().getSelectionModel().select(1);
        });

        String before = onFx(() -> dialog.previewArea().getText());
        assertThat(before.indexOf("`email`")).isLessThan(before.indexOf("`note`"));

        fire(dialog, "new-table-move-down");
        String moved = onFx(() -> dialog.previewArea().getText());
        assertThat(moved.indexOf("`note`"))
                .as("下移一格：email 应该跑到 note 后面")
                .isLessThan(moved.indexOf("`email`"));
        assertThat(onFx(() -> dialog.columnsTable().getSelectionModel().getSelectedIndex()))
                .as("移动之后选中要跟着走，否则连按两次下移会移错行")
                .isEqualTo(2);

        fire(dialog, "new-table-move-up");
        String back = onFx(() -> dialog.previewArea().getText());
        assertThat(back.indexOf("`email`")).isLessThan(back.indexOf("`note`"));
    }

    /** 已经在第一行还按上移 = 不动，不许把行甩到末尾去。 */
    @Test
    void movingPastTheEdgesKeepsTheOrderAlone() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.rows().add(typedRow(dialog, "email", "VARCHAR(255)"));
            dialog.columnsTable().getSelectionModel().select(0);
        });

        fire(dialog, "new-table-move-up");

        assertThat(onFx(() -> dialog.rows().getFirst().name.get())).isEqualTo("id");
        assertThat(onFx(() -> dialog.rows())).hasSize(2);
    }

    /** 「插入字段」加在选中行<b>前面</b> —— Navicat 的语义，追加到末尾的是「添加字段」。 */
    @Test
    void insertFieldGoesBeforeTheSelectedRow() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.columnsTable().getSelectionModel().select(0);
        });

        fire(dialog, "new-table-insert-column");

        assertThat(onFx(() -> dialog.rows())).hasSize(2);
        assertThat(onFx(() -> dialog.rows().getFirst().name.get()))
                .as("插在 id 之前，id 被挤到第二行")
                .isNotEqualTo("id");
        assertThat(onFx(() -> dialog.rows().get(1).name.get())).isEqualTo("id");
        assertThat(onFx(() -> dialog.columnsTable().getSelectionModel().getSelectedIndex()))
                .as("刚插进来的那一行就是当前行 —— 用户接下来要填它")
                .isEqualTo(0);
    }

    /** 没有选中行时「插入」退化成追加 —— 不许把行插到一个不存在的位置上。 */
    @Test
    void insertFieldAppendsWhenNothingIsSelected() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.columnsTable().getSelectionModel().clearSelection();
        });

        fire(dialog, "new-table-insert-column");

        assertThat(onFx(() -> dialog.rows().getFirst().name.get())).isEqualTo("id");
        assertThat(onFx(() -> dialog.rows())).hasSize(2);
    }

    /** 删除 = 从计划里拿掉，不是从画面上藏起来。 */
    @Test
    void removeFieldTakesTheColumnOutOfTheSql() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.rows().add(typedRow(dialog, "email", "VARCHAR(255)"));
            dialog.columnsTable().getSelectionModel().select(1);
        });

        fire(dialog, "new-table-remove-column");

        assertThat(onFx(() -> dialog.previewArea().getText())).doesNotContain("`email`");
        assertThat(onFx(() -> dialog.fieldCountLabel().getText()))
                .isEqualTo(I18n.t("newtable.fieldCount", 1));
    }

    /** 工具条在网格上方、五颗按钮齐全（Navicat 形态）。 */
    @Test
    void theFieldToolbarCarriesTheFiveRowCommands() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        for (String id : List.of("new-table-add-column", "new-table-insert-column",
                "new-table-remove-column", "new-table-move-up", "new-table-move-down")) {
            assertThat(onFx(() -> dialog.dialog().getDialogPane().getContent().lookup("#" + id)))
                    .as("字段页的工具条上必须有 " + id)
                    .isNotNull();
        }
    }

    /**
     * ⭐ 字段页那一帧存在的理由，就是「选中行 + 下方属性面板」这一对联动。
     *
     * <p>实拍抓到过反例：摆完态后网格里<b>一行都没有选中</b>（只剩 Modena 的单元格描边），
     * 帧里看不出面板改的是哪一列 —— 门禁全绿也照样拍出一张证不了事的图。
     * 所以摆态之后必须断言选中项还在，而不是只看属性面板的文本。
     */
    @Test
    void theFieldsPoseLeavesTheEditedRowSelected() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        onFxRun(() -> dialog.poseForShots("fields"));

        javafx.scene.control.TableView<?> table = onFx(() ->
                (javafx.scene.control.TableView<?>) dialog.dialog().getDialogPane()
                        .getContent().lookup("#new-table-columns"));
        Object selected = onFx(() -> table.getSelectionModel().getSelectedItem());

        assertThat(selected)
                .as("摆完字段页的态，网格必须真的选中一行 —— 否则这一帧证不了「选中行 ↔ 属性面板」")
                .isNotNull();
        assertThat(((NewTableDialog.ColumnRow) selected).name.get())
                .as("选中的要是属性面板正在编辑的那一列（N29 起摆的是生成列 full_name）")
                .isEqualTo("full_name");
    }

    /**
     * ⭐ 勾「自增」就是把这一列变成主键：自增列必须是键，反过来说
     * 取消主键时自增一并没有意义 —— 两头都要自动跟着走。
     */
    @Test
    void autoIncrementAndPrimaryKeyLockEachOther() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        NewTableDialog.ColumnRow row = onFx(() -> {
            NewTableDialog.ColumnRow plain = rowOfType("BIGINT");
            plain.autoIncrement.set(true);
            return plain;
        });
        assertThat(row.primaryKey()).isTrue();
        assertThat(row.nullable.get()).isFalse();

        onFxRun(() -> row.key.set(""));
        assertThat(row.autoIncrement.get())
                .as("取消主键 → 自增必须跟着取消，否则计划里是一条建不出来的语句")
                .isFalse();
    }

    /** 设计器的行高就是 Navicat 的数据行密度（21px），不是默认的 24。 */
    @Test
    void theDesignerGridUsesTheNavicatRowHeight() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        double cellSize = onFx(() -> dialog.columnsTable().getFixedCellSize());

        assertThat(cellSize).isEqualTo(io.agenticdb.ui.RowHeights.of("normal"));
        assertThat(cellSize).isLessThanOrEqualTo(22.0);
    }

    /**
     * ⭐ 属性面板编辑的是<b>选中的那一列</b>：切行要回填，敲进去要写回。
     *
     * <p>回填时挂了守卫（{@code syncingProperties}），否则「切到下一行」会把
     * 上一行的默认值抄过来 —— 那种 bug 只有两行以上才看得见。
     */
    @Test
    void thePropertiesPanelEditsTheSelectedColumn() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        NewTableDialog.ColumnRow email = onFx(() -> {
            dialog.nameField().setText("members");
            NewTableDialog.ColumnRow row = typedRow(dialog, "email", "VARCHAR(255)");
            row.defaultValue.set("''");
            dialog.rows().add(row);
            NewTableDialog.ColumnRow second = dialog.newRow();
            second.name.set("note");
            second.applyRawType("TEXT");
            second.defaultValue.set("'draft'");
            dialog.rows().add(second);
            dialog.columnsTable().getSelectionModel().select(1);
            return row;
        });

        assertThat(onFx(() -> dialog.fieldDefaultField().isDisable())).isFalse();
        assertThat(onFx(() -> dialog.fieldDefaultField().getText())).isEqualTo("''");

        onFxRun(() -> dialog.columnsTable().getSelectionModel().select(2));
        assertThat(onFx(() -> dialog.fieldDefaultField().getText()))
                .as("切到第三行 → 面板显示第三行的默认值")
                .isEqualTo("'draft'");

        onFxRun(() -> dialog.fieldDefaultField().setText("'new'"));
        assertThat(onFx(() -> dialog.rows().get(2).defaultValue.get())).isEqualTo("'new'");
        assertThat(onFx(() -> dialog.previewArea().getText())).contains("DEFAULT 'new'");
        assertThat(onFx(() -> email.defaultValue.get()))
                .as("改第三行不许顺带改第一行")
                .isEqualTo("''");
    }

    /** 没选中行时面板置灰，并说清「先选一行」—— 不然三个空控件看着像已经填了。 */
    @Test
    void thePropertiesPanelAsksForASelectionWhenNothingIsSelected() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> dialog.columnsTable().getSelectionModel().clearSelection());

        assertThat(onFx(() -> dialog.fieldDefaultField().isDisable())).isTrue();
        assertThat(onFx(() -> dialog.fieldCharsetBox().isDisable())).isTrue();
        assertThat(onFx(() -> dialog.fieldCollationBox().isDisable())).isTrue();
        assertThat(onFx(() -> dialog.fieldKeyLengthField().isDisable())).isTrue();
        assertThat(onFx(() -> dialog.fieldBinaryBox().isDisable())).isTrue();
        assertThat(onFx(() -> dialog.fieldExpressionField().isDisable())).isTrue();
        assertThat(onFx(() -> dialog.fieldAutoIncrementBox().isDisable())).isTrue();
        javafx.scene.Node hint = onFx(() -> dialog.dialog().getDialogPane().getContent()
                .lookup("#new-table-field-properties-hint"));
        assertThat(hint).isNotNull();
        assertThat(onFx(() -> ((javafx.scene.control.Label) hint).getText()))
                .contains(I18n.t("newtable.properties.hint"));
    }

    /**
     * ⭐ 字符集 / 排序规则只对<b>文本列</b>开放：整数列选中时两格置灰。
     *
     * <p>PostgreSQL 对 {@code integer COLLATE "x"} 直接报错，MySQL 收下但没意义。
     * 与其让用户填完被库打回来，不如在格子上就说明白。
     */
    @Test
    void charsetAndCollationApplyToTextColumnsOnly() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));
        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.rows().add(typedRow(dialog, "email", "VARCHAR(255)"));
        });

        onFxRun(() -> dialog.columnsTable().getSelectionModel().select(0));
        assertThat(onFx(() -> dialog.fieldCharsetBox().isDisable()))
                .as("id 是 INTEGER —— 字符集 / 排序规则要置灰")
                .isTrue();
        assertThat(onFx(() -> dialog.fieldCollationBox().isDisable())).isTrue();

        onFxRun(() -> dialog.columnsTable().getSelectionModel().select(1));
        assertThat(onFx(() -> dialog.fieldCharsetBox().isDisable())).isFalse();
        onFxRun(() -> {
            dialog.fieldCharsetBox().getEditor().setText("utf8mb4");
            dialog.fieldCollationBox().getEditor().setText("utf8mb4_general_ci");
        });

        assertThat(onFx(() -> dialog.previewArea().getText()))
                .contains("`email` VARCHAR(255) CHARACTER SET utf8mb4 "
                        + "COLLATE utf8mb4_general_ci");
    }

    /** 没有列级字符集的方言（PG / SQLite）：格子置灰 + 一句原因，而不是留着让人填。 */
    @Test
    void dialectsWithoutColumnCharsetGreyTheFieldOutWithAReason() {
        NewTableDialog postgres = onFx(() -> NewTableDialog.create(
                FakeDbSession.of(DialectKind.POSTGRESQL), List.of()));
        onFxRun(() -> {
            postgres.nameField().setText("members");
            postgres.rows().add(typedRow(postgres, "email", "VARCHAR(255)"));
            postgres.columnsTable().getSelectionModel().select(1);
        });

        assertThat(onFx(() -> postgres.fieldCharsetBox().isDisable())).isTrue();
        assertThat(onFx(() -> postgres.fieldCharsetBox().getPromptText()))
                .contains(I18n.t("newtable.charset.unsupported", "PostgreSQL"));
        assertThat(onFx(() -> postgres.fieldCollationBox().isDisable()))
                .as("排序规则 PostgreSQL 是支持的，不该跟着一起灰")
                .isFalse();
    }

    /**
     * ⭐ 「备注」是独立一页，表注释挂在这一页、并且真的进得了计划。
     *
     * <p>Navicat 的页签里备注和「选项」是并列的两件事；塞在选项页里省了一个页签，
     * 代价是用户照着手边的对照表找不到输入框。
     */
    @Test
    void commentHasItsOwnPageAndFeedsThePlan() {
        NewTableDialog dialog = onFx(() -> NewTableDialog.create(session, List.of()));

        javafx.scene.control.Tab commentTab = onFx(() -> dialog.tabs().getTabs().get(6));
        assertThat(onFx(commentTab::getId)).isEqualTo("new-table-tab-comment");
        assertThat(onFx(commentTab::getText)).isEqualTo(I18n.t("newtable.tab.comment"));
        assertThat(onFx(() -> commentTab.getContent().lookup("#new-table-comment")))
                .as("表注释输入必须挂在「备注」页上")
                .isNotNull();
        assertThat(onFx(() -> dialog.tabs().getTabs().get(5).getContent()
                .lookup("#new-table-comment")))
                .as("选项页里不该再有注释输入")
                .isNull();

        onFxRun(() -> {
            dialog.nameField().setText("members");
            dialog.commentField().setText("会员表");
        });

        StructurePlan plan = onFx(dialog::buildPlan);
        assertThat(plan).isNotNull();
        assertThat(plan.statements()).anyMatch(statement -> statement.contains("COMMENT"));
        assertThat(onFx(() -> dialog.previewArea().getText())).contains("COMMENT");
    }

    /** 点工具条 / 页签上的按钮（字段页默认选中，DialogPane 里找得到）。 */
    private void fire(NewTableDialog dialog, String buttonId) {
        onFxRun(() -> ((javafx.scene.control.Button) dialog.dialog().getDialogPane()
                .getContent().lookup("#" + buttonId)).fire());
    }
}
