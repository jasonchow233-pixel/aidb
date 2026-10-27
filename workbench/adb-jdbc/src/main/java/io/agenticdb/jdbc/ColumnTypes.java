package io.agenticdb.jdbc;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.agenticdb.jdbc.ColumnTypeOption.plain;
import static io.agenticdb.jdbc.ColumnTypeOption.scaled;
import static io.agenticdb.jdbc.ColumnTypeOption.sized;

/**
 * ⭐ R31：三种方言的「类型」候选表。
 *
 * <p>只收<b>目标库真的存在</b>的类型 —— 把 MySQL 的 {@code TINYINT} 摆给 PostgreSQL 选，
 * 用户选完到执行才报错，等于把「背语法」换成了「猜哪一个能选」。清单按常用度排序，
 * 不追求穷举；下拉框可编辑，方言专属类型（域、自定义类型）照样能敲进去。
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
     * 这一类类型有没有「文本」语义 —— 只有文本类型能带字符集 / 排序规则。
     *
     * <p>整数列写 {@code COLLATE} 在 PostgreSQL 上直接报错（"collations are not
     * supported by type integer"），MySQL 收下但没有意义；JSON 更特殊 ——
     * MySQL 明确拒绝给它指定字符集。三种方言在这件事上的名单高度重合，
     * 所以共用一份，不再各写一遍。
     */
    private static final Set<String> TEXTUAL = Set.of(
            "CHAR", "VARCHAR", "NVARCHAR", "NCHAR", "CHARACTER", "CHARACTER VARYING",
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
