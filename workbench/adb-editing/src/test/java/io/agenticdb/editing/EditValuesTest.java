package io.agenticdb.editing;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「清空一格」绑进 SQL 的那个值的门禁（R53）。
 *
 * <p>这条规则原来只有界面里一个私有分支，界面上的门禁（{@code DataGridPageTest} 的
 * 「设空」按钮 disable 条件）量的是<b>按钮状态</b>，量不到绑出去的值；
 * 真库契约量得到值，但界面一改它就对不上 —— 于是规则搬到 {@link EditValues}，
 * 这里钉档位，契约（{@code EditWriteBackMatrixTest}）钉五家服务器上的落库结果。
 */
class EditValuesTest {

    @Test
    void textualColumnsEmptyToString() {
        for (ColumnType type : List.of(ColumnType.TEXT, ColumnType.JSON,
                ColumnType.UUID, ColumnType.OTHER)) {
            assertThat(EditValues.emptiedCell(column(type)))
                    .as("%s：文本档的「清空」= 空串，网格里显示成空串标签，和 NULL 是两件事", type)
                    .isEqualTo("");
        }
    }

    @Test
    void typedColumnsEmptyToNullNotEmptyString() {
        // ⚠️ 全部档位都要点名，新增一档漏进这个清单 = 它默认按「非文本」走，没人看见。
        List<ColumnType> nonTextual = Arrays.stream(ColumnType.values())
                .filter(type -> !type.textual())
                .toList();
        assertThat(nonTextual)
                .as("非文本档要把每一档都列出来钉住（现在是这几档）")
                .containsExactlyInAnyOrder(ColumnType.NUMBER, ColumnType.BOOLEAN,
                        ColumnType.DATE, ColumnType.TIME, ColumnType.TIMESTAMP,
                        ColumnType.BINARY, ColumnType.ENUM, ColumnType.SET,
                        ColumnType.ARRAY, ColumnType.GEO);
        for (ColumnType type : nonTextual) {
            assertThat(EditValues.emptiedCell(column(type)))
                    .as("%s：这一档没有「空串」这个值 —— 绑 \"\" 进服务器，"
                                    + "MySQL/MariaDB/PG/Oracle 整批保存失败，SQL Server 的 BIT 静默折成 0。"
                                    + "清空必须是真 NULL", type)
                    .isNull();
        }
    }

    @Test
    void emptiedCellFollowsTheCatalogTypeNotTheColumnName() {
        // 规则只看档，不看列名 / 是否可空：可空性由调用方在打服务器之前拦（界面那条
        // grid.batch.notNull 提示），这里不掺和，否则「非空列清空」会变成一条原生完整性错误。
        assertThat(EditValues.emptiedCell(new ColumnDef("amount", ColumnType.NUMBER,
                false, false, null, null, null, null, 12, 2, false)))
                .isEqualTo(EditValues.emptiedCell(column(ColumnType.NUMBER)));
    }

    private static ColumnDef column(ColumnType type) {
        return new ColumnDef("c_" + type.name().toLowerCase(java.util.Locale.ROOT), type,
                true, false, null, null, null, null, null, null, false);
    }
}
