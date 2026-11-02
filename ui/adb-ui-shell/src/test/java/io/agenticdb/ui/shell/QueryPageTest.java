package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class QueryPageTest {

    private JdbcSession session;
    private ExecutorService executor;
    private QueryPage page;
    private Stage primaryStage;

    @Start
    void start(Stage stage) throws Exception {
        primaryStage = stage;
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Bob')");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-query");
            thread.setDaemon(true);
            return thread;
        });
        page = new QueryPage(session, "test", "", executor, null,
                new DataGridPage.Feedback() {
                    @Override
                    public void status(String message) {
                    }

                    @Override
                    public void range(String text) {
                    }

                    @Override
                    public void duration(long millis) {
                    }
                });
        page.setConfirmationHandler(danger -> true);
        stage.setScene(new Scene(page, 900, 700));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            page.close();
        }
        executor.shutdownNow();
        session.close();
    }

    @Test
    void runsOnlyCaretStatementAndRendersRows() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;\nSELECT 2 AS n;");
            page.editor().moveTo(0);
            page.runCurrent();
        });
        waitForTabs(1);

        assertThat(page.statusText()).contains(I18n.t("query.done"));
        assertThat(page.resultTabCount()).isEqualTo(1);
        assertThat(page.statusText()).doesNotContain("2 条");
    }

    @Test
    void runsAllStatementsAndCreatesOneTabPerStatement() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;\nSELECT 2 AS n;");
            page.runAll();
        });
        waitForTabs(2);

        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs()).hasSize(2);
        assertThat(results.getTabs().get(0).getText()).contains(I18n.t("query.resultTab", 1));
        assertThat(results.getTabs().get(1).getText()).contains(I18n.t("query.resultTab", 2));
    }

    @Test
    void dmlWithoutRowsCreatesMessageTab() {
        interact(() -> {
            page.setSql("INSERT INTO users VALUES (3, 'Cara');");
            page.runAll();
        });
        waitForTabs(1);

        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs().getFirst().getText()).contains(I18n.t("query.messageTab", 1));
    }

    @Test
    void sqlErrorCreatesErrorTabAndKeepsPriorResults() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;\nSELECT * FROM missing_table;");
            page.runAll();
        });
        waitForTabs(2);

        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs()).hasSize(2);
        assertThat(results.getTabs().get(1).getText()).contains(I18n.t("query.statementFailed", 2));
        assertThat(results.getTabs().get(1).getContent().lookup("#query-error-details"))
                .as("⭐ P2-2：错误页签必须给一个「查看详情」入口 —— "
                        + "页签只放下一步要改什么，完整证据在弹窗里")
                .isNotNull();
        assertThat(page.statusText()).contains(I18n.t("query.runFailedTitle"));
    }

    @Test
    void duplicateColumnNamesRemainReadable() {
        interact(() -> {
            page.setSql("SELECT 1 AS n, 2 AS n;");
            page.runAll();
        });
        waitForTabs(1);

        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        // ⭐ 两列都该取到，且值可区分。重复列的具体键名由驱动决定
        //   （SQLite 给第二列命名为 n:1，而不是 n_2），所以这里只校验「两列值都能取到」，
        //   不绑定具体键名 —— 否则换个驱动这个断言就假红。
        assertThat(table.getColumns()).hasSize(3);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> row =
                (java.util.Map<String, Object>) table.getItems().getFirst();
        assertThat(row).hasSize(2);
        assertThat(row.values()).containsExactlyInAnyOrder(1, 2);
    }

    /**
     * ⭐ U25：结果网格挂了右键菜单 —— DBA 的高频动作（抄单元格 / 抄一行 /
     * 抄列名）以前全藏在「进单元格全选 Ctrl+C」后面。门禁验两件事：
     * 处理器真的挂在表上；模拟事件时**选择会挪到被点的行**
     * （菜单说的是这一行、高亮停在别处就是两张嘴）。
     */
    @Test
    void resultGridCarriesTheContextMenu() throws Exception {
        interact(() -> {
            page.setSql("SELECT id, name FROM users;");
            page.runAll();
        });
        waitForTabs(1);

        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        assertThat(table.getOnContextMenuRequested())
                .as("结果网格的右键处理器")
                .isNotNull();

        javafx.scene.control.TableCell<?, ?> cell = table.lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.TableCell.class::isInstance)
                .map(node -> (javafx.scene.control.TableCell<?, ?>) node)
                .filter(c -> c.getTableRow() != null && c.getIndex() == 1
                        && c.getTableColumn() != null
                        && "name".equals(String.valueOf(c.getTableColumn().getText())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 name 列第二行的单元格"));

        interact(() -> cell.fireEvent(new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                10, 10,
                cell.localToScreen(10, 10).getX(),
                cell.localToScreen(10, 10).getY(),
                false, null)));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> table.getSelectionModel().getSelectedIndex() == 1);
        assertThat(table.getSelectionModel().getSelectedIndex())
                .as("右键先把选择挪到被点的行")
                .isEqualTo(1);
        // 关掉右键浮窗，别让悬着的菜单污染后续断言。
        // ⚠️ 必须先快照：hide() 会当场从 Window.getWindows() 这个可观察列表里移除自己，
        //    直接 forEach 会在迭代中缩表 → IndexOutOfBounds。
        interact(() -> java.util.List.copyOf(javafx.stage.Window.getWindows()).forEach(w -> {
            if (w != primaryStage && w.isShowing()) {
                w.hide();
            }
        }));
    }

    @Test
    void explainUsesDialectSpecificPrefix() {
        interact(() -> {
            page.setSql("SELECT * FROM users;");
            page.explainCurrent();
        });
        waitForTabs(1);

        assertThat(page.statusText()).contains(I18n.t("query.explain"));
        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs().getFirst().getText()).contains(I18n.t("query.resultTab", 1));
    }

    @Test
    void paginatesSelectResultsAndShowsPager() {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);

        // ⭐ Q5：可分页 SELECT 必须出现分页条，且默认页大小 200 → 250 行分 2 页。
        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(page.lookup("#query-result-pager")).isNotNull();
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);
        assertThat(pageLabel()).isEqualTo(I18n.t("query.pageOfTotal", 1, 2));
        assertThat(rangeLabel()).isEqualTo(I18n.t("status.rows", 250));
        // 第 1 页装的应是前 200 行（id 1..200），绝对行号从 1 开始。
        assertThat(firstRowText(table)).contains("1");
    }

    /**
     * ⭐ 分页条上的每一项**不许被压成省略号**（第六批的重拍帧抓到 "Previous Pa…" / "Per p…" /
     * "Go to pa" / "Exp…" —— 中文三四个字装得下的写死宽度，换英文就是半截字）。
     *
     * <p>钉的是结果不是手段：无论是把翻页键退回纯图标、还是把输入框抬到装得下提示文字，
     * 只要这一排里有任何一个控件实得宽度小于它想要的宽度，这条就红。
     */
    @Test
    void queryPagerItemsAreNeverSqueezedIntoEllipsis() throws Exception {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);

        // 喂布局与读几何必须在**同一条 FX 任务**里（第四批的教训：分开两次调用，
        // 中间能插入布局事件，读到自相矛盾的帧），lookup 本身也必须在 FX 线程上。
        java.util.List<String> squeezed = org.testfx.util.WaitForAsyncUtils.asyncFx(() -> {
            javafx.scene.Scene scene = page.getScene();
            // ⚠️ 必须挂样式表：本类的 @Start 建的是裸场景（别的测试只比行为，不比几何），
            //    而字号/内边距全来自 app.css —— 不挂 CSS 量出来的宽度与实拍对不上，
            //    这条门禁会变成"永远绿"的假闸（第四批踩过同一坑）。
            if (scene.getStylesheets().size() < 2) {
                scene.getStylesheets().add(getClass()
                        .getResource("/io/agenticdb/ui/theme-light.css").toExternalForm());
                scene.getStylesheets().add(getClass()
                        .getResource("/io/agenticdb/ui/app.css").toExternalForm());
                scene.getRoot().getStyleClass().add("theme-light");
            }
            page.applyCss();
            page.layout();
            javafx.scene.layout.HBox bar =
                    (javafx.scene.layout.HBox) page.lookup("#query-result-pager");
            assertThat(bar).as("分页条 #query-result-pager 必须在场景里").isNotNull();
            assertThat(bar.getWidth())
                    .as("分页条根本没布局（宽度 0 就是这条门禁在空转）")
                    .isGreaterThan(0);

            // ⭐ 不去改窗口尺寸：实测 `stage.setWidth()` 在这套 harness 里**不会同步落到场景上**
            //    （第一版就是这么静默量在 900 宽，靠"确认场景到 1280"那条断言才没变成假绿）。
            //    改成推导验收宽度下的预算：此刻「场景宽 − 分页条实得宽」就是分页条以外的固定开销，
            //    1280 宽下分页条的预算 = 1280 − 那个开销。整排**想要**的宽度超过预算，
            //    HBox 就把每一项往 min 上压 —— 实拍里 "Previous Pa…" 那半截字就是这么来的。
            double overhead = scene.getWidth() - bar.getWidth();
            double budget = 1280 - overhead;
            double wanted = bar.prefWidth(-1);
            java.util.List<String> bad = new java.util.ArrayList<>();
            if (wanted + 0.5 > budget) {
                bad.add("整排想要 " + Math.round(wanted) + "px > 1280 宽下的预算 "
                        + Math.round(budget) + "px");
            }
            // 逐项点一次名：哪一颗可以被压，改的时候才知道该动谁。
            // ⚠️ 读的是**计算后的** `minWidth(-1)`，不是 `getMinWidth()` 属性 ——
            //    后者默认是 -1（USE_COMPUTED_SIZE，意思是"按 pref 算"），
            //    拿属性值比 pref 会把每一颗都报成可压缩，门禁当场变成假红。
            java.util.function.Consumer<javafx.scene.Node> check = node -> {
                if (node instanceof javafx.scene.control.Labeled labeled) {
                    labeled.applyCss();
                    double want = labeled.prefWidth(-1);
                    if (labeled.minWidth(-1) + 0.5 < want) {
                        bad.add(who(labeled) + "「" + labeled.getText() + "」下限 "
                                + Math.round(labeled.minWidth(-1)) + "px < 想要 "
                                + Math.round(want) + "px");
                    }
                }
                if (node instanceof javafx.scene.control.TextField field) {
                    field.applyCss();
                    String prompt = field.getPromptText();
                    if (prompt != null && !prompt.isEmpty()) {
                        javafx.scene.text.Text probe = new javafx.scene.text.Text(prompt);
                        probe.setFont(field.getFont());
                        double want = Math.ceil(probe.getLayoutBounds().getWidth()) + 20;
                        if (field.minWidth(-1) + 0.5 < want) {
                            bad.add(who(field) + " 的提示「" + prompt + "」要 "
                                    + Math.round(want) + "px 而下限只有 "
                                    + Math.round(field.minWidth(-1)) + "px");
                        }
                    }
                }
            };
            for (javafx.scene.Node node : bar.getChildren()) {
                check.accept(node);
                if (node instanceof javafx.scene.layout.Pane pane) {
                    pane.getChildren().forEach(check);
                }
            }
            return bad;
        }).get();

        assertThat(squeezed)
                .as("分页条在 1280 验收宽度下会被挤成半截字 —— 英文比中文宽，"
                        + "文字项要钉 USE_PREF_SIZE（压不了就只能整项收起），宽度不许按中文写死")
                .isEmpty();
    }

    /** 报错时点名用：优先 id，没有 id 就用样式类。 */
    private static String who(javafx.scene.Node node) {
        return node.getId() != null ? "#" + node.getId() : node.getStyleClass().toString();
    }

    @Test
    void navigatingToNextPageLoadsTheFollowingRows() {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);

        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);

        interact(() -> ((javafx.scene.control.Button) page.lookup("#query-result-next")).fire());
        waitFor(() -> I18n.t("query.pageOfTotal", 2, 2).equals(pageLabel()));
        // 第 2 页只有剩下的 50 行，且按 ORDER BY id 应为 id 201..250。
        assertThat(table.getItems()).hasSize(50);
        assertThat(firstRowText(table)).contains("201");
    }

    @Test
    void singleRowSelectShowsOnePagePager() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 1);
        // 单行结果也走分页路径：分页条仍在，只是只有 1 页 —— 行为一致、无回归。
        assertThat(page.lookup("#query-result-pager")).isNotNull();
        assertThat(pageLabel()).isEqualTo(I18n.t("query.pageOfTotal", 1, 1));
    }

    // ─────────────── P6-2：导出范围菜单与「全部结果」 ───────────────

    @Test
    void exportMenuOffersThreeScopesAndDisablesEmptySelection() {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);

        MenuButton export = (MenuButton) page.lookup("#query-export");
        assertThat(export).as("分页条要有一个导出范围菜单").isNotNull();
        assertThat(export.getItems())
                .filteredOn(item -> item.getId() != null)
                .extracting(MenuItem::getId)
                .containsExactly("query-export-page", "query-export-selected",
                        "query-export-all");

        MenuItem selected = export.getItems().stream()
                .filter(item -> "query-export-selected".equals(item.getId()))
                .findFirst().orElseThrow();
        assertThat(selected.isDisable())
                .as("没有选区时「导出选中行」必须灰掉，否则导出的是空表")
                .isTrue();

        interact(() -> table.getSelectionModel().select(0));
        assertThat(selected.isDisable()).isFalse();
    }

    @Test
    void exportAllResultsIgnoresTheUiPageCap() throws Exception {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);

        StringWriter all = new StringWriter();
        long exported = page.exportAllResultsTo(0, all);
        assertThat(exported)
                .as("⭐ 「全部结果」要重跑原始 SQL，不受 UI 单页 200 行上限")
                .isEqualTo(250);
        List<String> lines = all.toString().lines().toList();
        assertThat(lines).hasSize(251); // 表头 + 250 行
        assertThat(lines.getLast()).contains("250").contains("u250");

        StringWriter currentPage = new StringWriter();
        page.exportCurrentPageTo(0, currentPage);
        List<String> pageLines = currentPage.toString().lines().toList();
        assertThat(pageLines).hasSize(201); // 表头 + 当前页 200 行
        assertThat(pageLines.getLast()).doesNotContain("u250");
    }

    private void seedRows(int count) {
        try {
            // @Start 已预置 (1,'Ada'),(2,'Bob')，先清空避免主键冲突。
            session.execute("DELETE FROM users");
        } catch (java.sql.SQLException ignored) {
        }
        StringBuilder sql = new StringBuilder("INSERT INTO users (id, name) VALUES ");
        for (int i = 1; i <= count; i++) {
            sql.append("(").append(i).append(", 'u").append(i).append("')")
                    .append(i < count ? "," : "");
        }
        try {
            session.execute(sql.toString());
        } catch (java.sql.SQLException e) {
            throw new AssertionError("seed 失败", e);
        }
    }

    private String pageLabel() {
        return ((javafx.scene.control.Label) page.lookup("#query-result-page-label")).getText();
    }

    private String rangeLabel() {
        return ((javafx.scene.control.Label) page.lookup("#query-result-range")).getText();
    }

    private String firstRowText(TableView<?> table) {
        return table.getItems().getFirst().toString();
    }

    private void waitFor(java.util.function.BooleanSupplier condition) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition::getAsBoolean);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("条件超时；状态：" + page.statusText(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void formatsSqlAndMarksEditorDirty() {
        interact(() -> page.setSql("select id, name from users where id = 1;"));
        assertThat(page.dirtyCount()).isEqualTo(1);

        interact(() -> page.formatSql());
        assertThat(page.sql()).contains("SELECT id, name").contains("\nFROM users");
    }

    @Test
    void togglesLineComments() {
        interact(() -> {
            page.setSql("SELECT 1;\nSELECT 2;");
            page.editor().select(0, page.sql().length());
            page.toggleComment();
        });
        assertThat(page.sql()).isEqualTo("-- SELECT 1;\n-- SELECT 2;");

        interact(() -> page.toggleComment());
        assertThat(page.sql()).isEqualTo("SELECT 1;\nSELECT 2;");
    }

    @Test
    void findAndReplaceShortcutsDriveFindBar() {
        interact(() -> {
            page.setSql("SELECT 1;\nSELECT 2;");
            page.editor().moveTo(0);
        });
        TextField find = (TextField) page.lookup("#query-find");
        interact(() -> {
            find.setText("SELECT 2");
            // ⚠️ 快捷键是「平台快捷键」：macOS 是 ⌘，Windows/Linux 是 Ctrl。
            //    写死 meta=true 只在 macOS 上成立（Windows 上 isShortcutDown() 为 false）。
            page.editor().area().fireEvent(shortcut(KeyCode.G));
        });
        assertThat(page.editor().area().getSelectedText()).isEqualTo("SELECT 2");

        interact(() -> page.editor().area().fireEvent(key(KeyCode.H, true, false, false)));
        assertThat(page.lookup("#query-find-bar").isVisible()).isTrue();
        assertThat(page.lookup("#query-replace").isFocused()).isTrue();
    }

    // ───────────────── 第十八轮：每页档位必须真的选中 ─────────────────

    @Test
    void resultPagerShowsThePageSizeInsteadOfAnEmptyBox() {
        interact(() -> {
            page.setSql("SELECT id FROM users;");
            page.editor().moveTo(0);
            page.runAll();
        });
        waitForTabs(1);

        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<Integer> box =
                (javafx.scene.control.ComboBox<Integer>) page.lookup("#query-result-page-size");
        assertThat(box).as("结果分页条要有「每页」下拉").isNotNull();

        // ⚠️ 回归守卫：`select(int)` 是按下标选（items 只有 0–3 四项），
        //    传原生 int 会静默失败，「每页」框渲染成空白 —— 截图里就是空的。
        assertThat(box.getValue())
                .as("「每页」必须显示当前档位（DEFAULT_PAGE_SIZE=200），不能是空框")
                .isNotNull()
                .isEqualTo(200);

        // ⚠️ 第十八轮实测补的回归守卫：**选中了不等于显示得出来**。
        //    78px 时截图里「200」被截成「2…」，96px 才完整渲染。
        assertThat(box.getPrefWidth())
                .as("「每页」框要够渲染最宽档位「1000」—— 实测 78px 会把「200」截成「2…」")
                .isGreaterThanOrEqualTo(90);
    }

    // ─────────────── 第十八轮：跳页框必须与数据页同规格 ───────────────

    @Test
    void resultPagerJumpFieldCarriesTheSameAffordancesAsTheDataPage() {
        interact(() -> {
            page.setSql("SELECT id FROM users;");
            page.editor().moveTo(0);
            page.runAll();
        });
        waitForTabs(1);

        TextField jump = (TextField) page.lookup("#query-result-jump");
        assertThat(jump).as("结果分页条要有跳页框").isNotNull();

        // 以前这里只有 id + 宽度 —— 截图上是一个**没有占位、读屏念不出用途**的空白框。
        assertThat(jump.getPromptText())
                .as("跳页框必须有占位提示（数据页的页码框也带「跳至页」提示，两页要一致）")
                .isNotEmpty();
        assertThat(jump.getAccessibleText())
                .as("跳页框必须有 accessibleText（同数据页）")
                .isNotEmpty();
        assertThat(jump.getStyleClass())
                .as("同一控件要走同一个样式类 adb-grid__jump")
                .contains("adb-grid__jump");
    }

    private static KeyEvent key(KeyCode code, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, alt, meta);
    }

    /** 平台快捷键事件（macOS = ⌘，其它 = Ctrl）。 */
    private static KeyEvent shortcut(KeyCode code) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, !mac, false, mac);
    }

    /**
     * ⭐ D-17（R44 帧目视抓到，2026-11-02）：主操作那颗 Run 是 **SplitMenuButton**，
     * 而 modena 给拆体键的内层 `.label` 与 `.arrow-button` **各自又画一层白底** ——
     * 外壳的蓝底只剩一圈边，实拍是「蓝底两个白块 + 黑字 + 白图标压在白块上看不见」。
     *
     * <p>钉三件事：① 内层两格必须透底并用反色文字令牌；② 箭头跟着反白；
     * ③ 规则必须排在基础主色规则**之后**（同特异度后写胜，挪到前面就等于没写）。
     */
    @Test
    void primaryRunButtonPaintsItsSplitCellsTransparent() throws Exception {
        String css = appCss();
        int base = css.indexOf(".adb-button--primary {");
        int split = css.indexOf(".adb-button--primary.split-menu-button > .label");
        assertThat(base).as("主色基础规则要在").isPositive();
        assertThat(split).as("拆体内层规则要在").isPositive();
        assertThat(split)
                .as("内层规则必须排在主色规则之后，否则被后写的基础规则盖掉")
                .isGreaterThan(base);

        String label = ruleBody(css, ".adb-button--primary.split-menu-button > .label");
        assertThat(label)
                .as("内层 .label 不许留 modena 的白底，文字要跟着反白")
                .contains("-fx-background-color: transparent")
                .contains("-adb-text-inverse");
        assertThat(ruleBody(css, ".adb-button--primary.split-menu-button > .arrow-button .arrow"))
                .as("箭头本体是 -fx-background-color 画的，不反白就是白块上一个白三角")
                .contains("-adb-text-inverse");
        assertThat(css)
                .as("点开那一拍（:showing）modena 会再刷一次内层底色")
                .contains(".adb-button--primary.split-menu-button:showing > .label");
        assertThat(ruleBody(css, ".split-menu-button > .label"))
                .as("R40 的「内层 label 竖直内边距归零」必须够到拆体键，"
                        + "否则 18px 的 label 吃掉 4+4，「Run」下半截被裁")
                .contains("-fx-padding: 0px 8px 0px 8px");
    }

    /** app.css 全文，注释剥掉（注释里也写着选择器名，不剥会假通过）。 */
    private static String appCss() throws java.io.IOException {
        try (java.io.InputStream stream =
                     QueryPageTest.class.getResourceAsStream("/io/agenticdb/ui/app.css")) {
            assertThat(stream).as("缺 app.css").isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    /** 取某个选择器**自己的那条规则**的规则体（允许选择器成组写在同一个 {} 前）。 */
    private static String ruleBody(String css, String selector) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([^{}]+)\\{([^}]*)\\}").matcher(css);
        while (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                if (part.trim().equals(selector)) {
                    return matcher.group(2);
                }
            }
        }
        throw new AssertionError("app.css 里没有这条规则：" + selector);
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void waitForTabs(int count) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> page.resultTabCount() == count);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("等待结果页 " + count + " 超时；当前 "
                    + page.resultTabCount() + "，状态：" + page.statusText(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }
}
