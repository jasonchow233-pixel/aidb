package io.agenticdb.editing;

/** 单元格坐标：主键 + 列名，而不是页面行号。 */
public record CellKey(RowIdentity row, String column) {

    public CellKey {
        if (row == null) {
            throw new IllegalArgumentException("行身份不能为空");
        }
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
    }
}
