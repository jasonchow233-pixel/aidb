package io.agenticdb.jdbc;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.agenticdb.jdbc.ColumnTypeOption.plain;
import static io.agenticdb.jdbc.ColumnTypeOption.scaled;
import static io.agenticdb.jdbc.ColumnTypeOption.sized;

/**
 * ⭐ R31：各方言的「类型」候选表。
 *
 * <p>只收<b>目标库真的存在</b>的类型 —— 把 MySQL 的 {@code TINYINT} 摆给 PostgreSQL 选，
 * 用户选完到执行才报错，等于把「背语法」换成了「猜哪一个能选」。清单按常用度排序，
 * 不追求穷举；下拉框可编辑，方言专属类型（域、自定义类型）照样能敲进去。
 *
 * <p>⚠️ 这里的"每种库一份清单"就是 A1 说的「差异放方言层」：
 * {@code Dialect.columnTypes()} 各自返回自己那一份，加一种库只加一份常量。
 */
public final class ColumnTypes {

    private ColumnTypes() {
    }

    /** MySQL：整型齐全，定点数要长度 + 小数位，字符类型要长度。 */
    public static final List<ColumnTypeOption> MYSQL = List.of(
            plain("INT"), plain("BIGINT"), plain("SMALLINT"), plain("TINYINT"),
            plain("MEDIUMINT"), scaled("DECIMAL"), scaled("NUMERIC"),
            plain("FLOAT"), plain("DOUBLE"), sized("BIT"),
            plain("DATE"), plain("TIME"), plain("DATETIME"), plain("TIMESTAMP"), plain("YEAR"),
            sized("CHAR"), sized("VARCHAR"), sized("BINARY"), sized("VARBINARY"),
            plain("TINYTEXT"), plain("TEXT"), plain("MEDIUMTEXT"), plain("LONGTEXT"),
            plain("TINYBLOB"), plain("BLOB"), plain("MEDIUMBLOB"), plain("LONGBLOB"),
            sized("ENUM"), sized("SET"), plain("JSON"), plain("BOOLEAN"));

    /** PostgreSQL：没有 TINYINT / MEDIUMINT；DOUBLE PRECISION 带空格。 */
    public static final List<ColumnTypeOption> POSTGRESQL = List.of(
            plain("INTEGER"), plain("BIGINT"), plain("SMALLINT"),
            scaled("NUMERIC"), scaled("DECIMAL"), plain("REAL"), plain("DOUBLE PRECISION"),
            plain("SERIAL"), plain("BIGSERIAL"), sized("VARCHAR"), sized("CHARACTER VARYING"),
            sized("CHAR"), sized("CHARACTER"), plain("TEXT"), plain("BOOLEAN"),
            plain("DATE"), plain("TIME"), plain("TIMESTAMP"), plain("TIMESTAMPTZ"),
            plain("UUID"), plain("JSON"), plain("JSONB"), plain("BYTEA"), plain("MONEY"),
            plain("INTERVAL"), plain("INET"), plain("CIDR"), plain("MACADDR"), plain("XML"));

    /** SQLite：类型名宽松，但只有 INTEGER 是 rowid 别名（自增只认它）。 */
    public static final List<ColumnTypeOption> SQLITE = List.of(
            plain("INTEGER"), plain("INT"), plain("TINYINT"), plain("SMALLINT"),
            plain("MEDIUMINT"), plain("BIGINT"), plain("UNSIGNED BIG INT"),
            scaled("NUMERIC"), scaled("DECIMAL"), plain("REAL"), plain("DOUBLE"),
            plain("FLOAT"), plain("BOOLEAN"), plain("DATE"), plain("DATETIME"),
            plain("TIMESTAMP"), sized("VARCHAR"), sized("CHAR"), plain("TEXT"),
            plain("CLOB"), plain("BLOB"), plain("JSON"));

    /**
     * ⭐ D-3 SQL Server：整型没有 MEDIUMINT；字符类型分「库内代码页」与 Unicode 两套
     * （{@code VARCHAR} / {@code NVARCHAR}），日期时间有 {@code DATETIME} 与更宽的
     * {@code DATETIME2} 两代，布尔是 {@code BIT}（没有 {@code BOOLEAN} 这个类型名）。
     */
    public static final List<ColumnTypeOption> SQLSERVER = List.of(
            plain("INT"), plain("BIGINT"), plain("SMALLINT"), plain("TINYINT"),
            plain("BIT"), scaled("DECIMAL"), scaled("NUMERIC"), scaled("MONEY"),
            scaled("SMALLMONEY"), plain("FLOAT"), plain("REAL"),
            plain("DATE"), plain("TIME"), plain("DATETIME"), plain("DATETIME2"),
            plain("SMALLDATETIME"), plain("DATETIMEOFFSET"),
            sized("CHAR"), sized("VARCHAR"), sized("NCHAR"), sized("NVARCHAR"),
            plain("TEXT"), plain("NTEXT"), sized("BINARY"), sized("VARBINARY"),
            plain("UNIQUEIDENTIFIER"), plain("XML"));

    /**
     * ⭐ D-3 Oracle：数值只有 {@code NUMBER(p,s)}（整型是它的精度 0 特例）；
     * 变长字符是 {@code VARCHAR2}（{@code VARCHAR} 是留给未来的兼容别名），
     * 定长是 {@code CHAR}，Unicode 走 {@code NVARCHAR2}；大对象分
     * {@code CLOB}/{@code NCLOB}/{@code BLOB}/{@code BFILE}；
     * {@code DATE} 自带时分秒，要小数秒或时区得用 {@code TIMESTAMP} 一族。
     */
    public static final List<ColumnTypeOption> ORACLE = List.of(
            scaled("NUMBER"), plain("INTEGER"), plain("FLOAT"), plain("BINARY_FLOAT"),
            plain("BINARY_DOUBLE"), plain("PLS_INTEGER"),
            sized("VARCHAR2"), sized("NVARCHAR2"), sized("CHAR"), sized("NCHAR"),
            plain("CLOB"), plain("NCLOB"), plain("BLOB"), plain("BFILE"),
            sized("RAW"), plain("LONG RAW"), plain("DATE"), plain("TIMESTAMP"),
            plain("TIMESTAMP WITH TIME ZONE"), plain("TIMESTAMP WITH LOCAL TIME ZONE"),
            plain("INTERVAL YEAR TO MONTH"), plain("INTERVAL DAY TO SECOND"),
            plain("XMLTYPE"));

    /**
     * 这一类类型有没有「文本」语义 —— 只有文本类型能带字符集 / 排序规则。
     *
     * <p>整数列写 {@code COLLATE} 在 PostgreSQL 上直接报错（"collations are not
     * supported by type integer"），MySQL 收下但没有意义；JSON 更特殊 ——
     * MySQL 明确拒绝给它指定字符集。三种方言在这件事上的名单高度重合，
     * 所以共用一份，不再各写一遍。
     */
    private static final Set<String> TEXTUAL = Set.of(
            "CHAR", "VARCHAR", "NVARCHAR", "NCHAR", "CHARACTER", "CHARACTER VARYING",
            "VARCHAR2", "NVARCHAR2", "NTEXT", "NCLOB",
            "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "ENUM", "SET",
            "NAME", "CITEXT");

    /** 类型名（不含长度部分）是否支持列级字符集 / 排序规则。 */
    public static boolean isTextual(String baseType) {
        if (baseType == null) {
            return false;
        }
        String normalized = baseType.trim().toUpperCase(Locale.ROOT);
        int paren = normalized.indexOf('(');
        if (paren >= 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        return TEXTUAL.contains(normalized);
    }
}
