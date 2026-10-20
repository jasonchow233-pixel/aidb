package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

/**
 * JDBC 元数据 → 领域类型。
 *
 * <p>UI 只认 {@link ColumnType}，不认方言类型名。
 * 这样 Renderer / 编辑器 / 导出只写一次。
 */
public final class TypeMapper {

    private TypeMapper() {
    }

    public static ColumnDef toColumnDef(ResultSetMetaData meta, int index) throws SQLException {
        String name = meta.getColumnLabel(index);
        if (name == null || name.isBlank()) {
            name = meta.getColumnName(index);
        }
        String typeName = meta.getColumnTypeName(index);
        ColumnType type = from(meta.getColumnType(index), typeName);
        boolean nullable = meta.isNullable(index) != ResultSetMetaData.columnNoNulls;

        Integer length = null;
        if (type == ColumnType.TEXT && meta.getPrecision(index) > 0) {
            length = meta.getPrecision(index);
        }

        Integer precision = null;
        Integer scale = null;
        if (type == ColumnType.NUMBER) {
            int p = meta.getPrecision(index);
            int s = meta.getScale(index);
            precision = p > 0 ? p : null;
            scale = s >= 0 ? s : null;
        }

        return new ColumnDef(
                name,
                type,
                nullable,
                false,
                null,
                null,
                null,
                length,
                precision,
                scale,
                false);
    }

    public static ColumnType from(int jdbcType, String rawTypeName) {
        String typeName = rawTypeName == null ? "" : rawTypeName.toUpperCase(Locale.ROOT);

        // 先看类型名：SQLite/PG 的 JSON、UUID、数组、枚举都落在 OTHER/ARRAY 里
        if (typeName.contains("JSON") || typeName.contains("JSONB")) {
            return ColumnType.JSON;
        }
        if (typeName.contains("UUID")) {
            return ColumnType.UUID;
        }
        if (typeName.endsWith("[]") || typeName.contains("ARRAY")) {
            return ColumnType.ARRAY;
        }
        if (typeName.startsWith("ENUM")) {
            return ColumnType.ENUM;
        }
        if (typeName.startsWith("SET")) {
            return ColumnType.SET;
        }
        if (typeName.contains("GEO") || typeName.contains("POINT")
                || typeName.contains("POLYGON") || typeName.contains("GEOMETRY")) {
            return ColumnType.GEO;
        }

        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL,
                 Types.DOUBLE -> ColumnType.NUMBER;
            case Types.BOOLEAN, Types.BIT -> ColumnType.BOOLEAN;
            case Types.DATE -> ColumnType.DATE;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> ColumnType.TIME;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ColumnType.TIMESTAMP;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ColumnType.BINARY;
            case Types.ARRAY -> ColumnType.ARRAY;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> ColumnType.TEXT;
            default -> ColumnType.OTHER;
        };
    }
}
