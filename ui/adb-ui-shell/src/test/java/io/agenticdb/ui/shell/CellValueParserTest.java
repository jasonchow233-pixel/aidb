package io.agenticdb.ui.shell;

import io.agenticdb.editing.EditValues;
import io.agenticdb.introspect.FilterCondition;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellValueParserTest {

    @Test
    void convertsEditorTextToColumnTypes() {
        assertThat(CellValueParser.parse(column(ColumnType.NUMBER), "42")).isEqualTo(42L);
        assertThat(CellValueParser.parse(column(ColumnType.NUMBER), "42.5")).isEqualTo(42.5);
        assertThat(CellValueParser.parse(column(ColumnType.BOOLEAN), "true")).isEqualTo(true);
        assertThat(CellValueParser.parse(column(ColumnType.BOOLEAN), "0")).isEqualTo(false);
        assertThat(CellValueParser.parse(column(ColumnType.TEXT), "")).isEqualTo("");
        assertThat(CellValueParser.parse(column(ColumnType.TIMESTAMP), "2026-10-20 10:00:00"))
                .isEqualTo("2026-10-20 10:00:00");
    }

    @Test
    void rejectsInvalidNumbersAndBooleans() {
        assertThatThrownBy(() -> CellValueParser.parse(column(ColumnType.NUMBER), "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(io.agenticdb.ui.I18n.t("parse.notNumber", "abc"));
        assertThatThrownBy(() -> CellValueParser.parse(column(ColumnType.BOOLEAN), "maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(io.agenticdb.ui.I18n.t("parse.notBoolean", "maybe"));
    }

    @Test
    void nullAndEmptyStringHaveDistinctDisplay() {
        assertThat(CellValueParser.display(null)).isEqualTo("NULL");
        assertThat(CellValueParser.display("")).isEqualTo(io.agenticdb.ui.I18n.t("parse.emptyStringLabel"));
    }

    /**
     * ⭐ R53：用户把一格的内容<b>删干净</b>之后，格子往服务器绑的是什么。
     *
     * <p>这一条量的是<b>接线</b>，不是 {@code EditValues} 的规则本身（规则由
     * {@code EditValuesTest} 钉）：界面曾经在这里有一个私有分支自己判断「非文本档给 null」，
     * 于是真库契约只能猜那个分支的形状 —— 第一版猜成「界面交空串」，
     * 直接往 {@code DECIMAL} 列绑 {@code ""}，四家服务端拒、SQL Server 的 {@code BIT}
     * 静默折成 0，看着像四个缺陷，实际是契约自己造的形状。
     * 现在规则和契约共用 {@code adb-editing} 那一个方法，这条测试守住的是
     * 「界面有没有真的去调它」。
     */
    @Test
    void emptiedTypedCellBindsNullNotEmptyString() {
        assertThat(DataGridPage.parseCellValue(column(ColumnType.NUMBER), ""))
                .as("数字格清空 = 真 NULL。绑 \"\" 会被 MySQL/MariaDB/PG/Oracle 整批拒掉")
                .isNull();
        assertThat(DataGridPage.parseCellValue(column(ColumnType.BOOLEAN), ""))
                .as("布尔格清空 = 真 NULL。SQL Server 的 BIT 会把 \"\" 静默折成 0，那是改数不是清空")
                .isNull();
        assertThat(DataGridPage.parseCellValue(column(ColumnType.TIMESTAMP), ""))
                .as("日期格清空 = 真 NULL")
                .isNull();
        assertThat(DataGridPage.parseCellValue(column(ColumnType.TEXT), ""))
                .as("文本格清空 = 空串：和 NULL 是两件事，网格里分开显示")
                .isEqualTo("");
        // 非空档不是这里拦的：调用方（commitEditor / 批量 / 表单）在打到服务器之前
        // 用 grid.batch.notNull 拒掉，别把原生完整性错误当提示发给用户。
        assertThat(DataGridPage.parseCellValue(column(ColumnType.NUMBER), "42"))
                .isEqualTo(42L);
    }

    /**
     * ⭐ R55：把「没填」这件事摊到<b>每一个</b> ColumnType 上量。
     *
     * <p>R53 定下的规则（{@code EditValues.emptiedCell}：文本档 {@code ""}、其余档 {@code null}）
     * 当时只接进了网格那一条路。筛选面板没接：{@code CellValueParser} 里留着自己的
     * {@code 空 → ""} 分支，于是列头值筛选的「精确值」框里<b>输一个空格</b>就能把
     * {@code ""} 绑进 {@code DECIMAL} 列 —— 正是 R53 在真库里量出来的那个形状。
     * 这条循环门让「新增一档却漏了规则」当场红，而不是等下一次矩阵。
     */
    @Test
    void everyColumnTypeUsesTheOneEmptinessRuleOnBothPaths() {
        for (ColumnType type : ColumnType.values()) {
            ColumnDef column = column(type);
            assertThat(CellValueParser.parse(column, ""))
                    .as("%s 档：筛选那条路的「没填」必须等于网格的「清空一格」", type)
                    .isEqualTo(EditValues.emptiedCell(column));
            assertThat(DataGridPage.parseCellValue(column, ""))
                    .as("%s 档：网格那条路也在同一条规则上", type)
                    .isEqualTo(EditValues.emptiedCell(column));
        }
    }

    /**
     * ⭐ R55：空格 ≠ 空串。这一条钉的是筛选面板，不是解析器。
     *
     * <p>框里没填（空、纯空格）都是「还没填」，返回 {@code null} 让调用方走「清空条件」，
     * 而不是凭空造一个「等于空字符串」或「等于空格」的条件。
     */
    @Test
    void theExactValueBoxTreatsWhitespaceAsNotFilled() {
        assertThat(ColumnFilterPanel.exactCondition(column(ColumnType.NUMBER), "  "))
                .as("数字列 + 空格 = 没填。旧写法会下发 col = ''，四家真库整批拒")
                .isNull();
        assertThat(ColumnFilterPanel.exactCondition(column(ColumnType.TIMESTAMP), " "))
                .as("日期列同理：绑一个空格进去是服务端错误，不是筛选")
                .isNull();
        assertThat(ColumnFilterPanel.exactCondition(column(ColumnType.TEXT), ""))
                .as("文本列的空框也是「没填」，不是「筛空字符串」")
                .isNull();
        assertThat(ColumnFilterPanel.exactCondition(column(ColumnType.NUMBER), " 42 "))
                .as("填了数字 = 等值条件，值按列类型翻译")
                .returns(java.util.List.of(42L), FilterCondition::values);
        assertThat(ColumnFilterPanel.exactCondition(column(ColumnType.TEXT), " a "))
                .as("文本列先去空白再比对（与 Filter & Sort 生成器同一个口径：那边也 trim）")
                .returns(java.util.List.of("a"), FilterCondition::values);
    }

    /**
     * ⭐ R55：只剩空格这种输入，对有类型的列是<b>报错</b>，不是悄悄绑一个空串。
     *
     * <p>报错走面板已有的红字路径（{@code status}），用户看得见；绑 {@code ''} 要么服务端
     * 拒、要么 SQL Server 的 {@code BIT} 静默折成 0 —— 后者是改数，比报错严重。
     */
    @Test
    void blankInputIsRejectedForTypedColumns() {
        assertThatThrownBy(() -> CellValueParser.parse(column(ColumnType.NUMBER), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CellValueParser.parse(column(ColumnType.BOOLEAN), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ⭐ R55：这条规则在界面里<b>只有一处调用</b>，且没人再手写第二张类型名单。
     *
     * <p>为什么用源码扫而不是只靠上一条：R53 之前界面这里就是「自己一个私有分支」，
     * 分支长什么样只有真库契约猜得出来。把「只有一处」钉在源码上，第二处一冒头就红 ——
     * 不必等一个恰好覆盖它的矩阵。
     */
    @Test
    void theEmptinessRuleHasNoSecondCopyInTheShell() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("ui/adb-ui-shell"))) {
            root = root.getParent();
        }
        assertThat(root).as("找不到仓库根，源码门无法执行").isNotNull();
        Path shell = root.resolve("ui/adb-ui-shell/src/main/java");
        List<String> emptiedCellCallers = new ArrayList<>();
        List<String> handWrittenTypeLists = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(shell)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                String file = path.getFileName().toString();
                if (source.contains("EditValues.emptiedCell(")) {
                    emptiedCellCallers.add(file);
                }
                // 「哪一档吃空串」只允许问 ColumnType.textual()。手写名单 = 第二处真相。
                if ((file.endsWith("FilterSortPanel.java") || file.endsWith("ColumnFilterPanel.java"))
                        && source.contains("!= ColumnType.")) {
                    handWrittenTypeLists.add(file);
                }
            }
        }
        assertThat(emptiedCellCallers)
                .as("清空/没填 → 绑定值 的调用点必须只有一个（多处 = 又开了一条自己判断的分支）")
                .containsExactly("CellValueParser.java");
        assertThat(handWrittenTypeLists)
                .as("筛选面板里不许再手写一份「哪些类型不接受空值」，走 ColumnType.textual()")
                .isEmpty();
    }

    private static ColumnDef column(ColumnType type) {
        return new ColumnDef("value", type, true, false,
                null, null, null, null, null, null, false);
    }
}
