package io.agenticdb.introspect;

import io.agenticdb.model.ColumnDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ⭐ H4-d 最小切片：两个会话里<b>同名表</b>的结构与行数差异（只读）。
 *
 * <p>只做比较，不做同步：跨方言的 {@code ALTER} 写法差异很大，自动拼一条
 * 「加列」语句在 PostgreSQL / Oracle 上可能直接把目标表改坏。这一版把差异
 * 如实列出来，由人决定怎么改 —— 与项目「不做假装能做的事」同一条口径。
 *
 * <p>纯领域对象，不认识 JavaFX / i18n；文案与展示由传输工具页负责。
 * 比较对列名大小写不敏感（MySQL 与 Oracle 对大小写的处理不同），
 * 但报告里保留各自库返回的原始列名，免得用户照着改错名字。
 */
public final class SchemaDiff {

    private SchemaDiff() {
    }

    /** 一列的最小描述：名字 + 显示类型（{@link ColumnDef#displayType()}）。 */
    public record Column(String name, String type) {
    }

    public record TypeMismatch(String column, String sourceType, String targetType) {
    }

    public record Report(String table,
                         List<String> onlyInSource,
                         List<String> onlyInTarget,
                         List<TypeMismatch> typeMismatches,
                         boolean primaryKeySame,
                         long sourceRows,
                         long targetRows) {

        public Report {
            onlyInSource = List.copyOf(onlyInSource);
            onlyInTarget = List.copyOf(onlyInTarget);
            typeMismatches = List.copyOf(typeMismatches);
        }

        public boolean identical() {
            return onlyInSource.isEmpty() && onlyInTarget.isEmpty()
                    && typeMismatches.isEmpty() && primaryKeySame
                    && sourceRows == targetRows;
        }

        /** 差异条数，给 UI 一行汇总用；不把「行数不同」算成结构差异。 */
        public int differenceCount() {
            return onlyInSource.size() + onlyInTarget.size() + typeMismatches.size()
                    + (primaryKeySame ? 0 : 1);
        }
    }

    /** 从结构页快照取列；{@code displayType()} 带长度 / 精度，比裸枚举名更能看出差异。 */
    public static List<Column> columnsOf(TableStructure structure) {
        if (structure == null) {
            return List.of();
        }
        List<Column> columns = new ArrayList<>();
        for (ColumnDef column : structure.columns()) {
            columns.add(new Column(column.name(), column.displayType()));
        }
        return List.copyOf(columns);
    }

    /** 从两个结构快照比较（UI 走这条）。 */
    public static Report compare(String table, TableStructure source, TableStructure target,
                                 long sourceRows, long targetRows) {
        return compare(table, columnsOf(source), columnsOf(target),
                source == null ? List.of() : source.primaryKeys(),
                target == null ? List.of() : target.primaryKeys(),
                sourceRows, targetRows);
    }

    /** 纯逻辑入口：不碰 JDBC，可被单测完整覆盖。 */
    public static Report compare(String table, List<Column> sourceColumns,
                                 List<Column> targetColumns, List<String> sourcePrimaryKeys,
                                 List<String> targetPrimaryKeys, long sourceRows,
                                 long targetRows) {
        Map<String, Column> source = index(sourceColumns);
        Map<String, Column> target = index(targetColumns);

        List<String> onlyInSource = new ArrayList<>();
        for (Map.Entry<String, Column> entry : source.entrySet()) {
            if (!target.containsKey(entry.getKey())) {
                onlyInSource.add(entry.getValue().name());
            }
        }
        List<String> onlyInTarget = new ArrayList<>();
        for (Map.Entry<String, Column> entry : target.entrySet()) {
            if (!source.containsKey(entry.getKey())) {
                onlyInTarget.add(entry.getValue().name());
            }
        }
        List<TypeMismatch> mismatches = new ArrayList<>();
        for (Map.Entry<String, Column> entry : source.entrySet()) {
            Column other = target.get(entry.getKey());
            if (other != null && !sameType(entry.getValue().type(), other.type())) {
                mismatches.add(new TypeMismatch(entry.getValue().name(),
                        entry.getValue().type(), other.type()));
            }
        }
        return new Report(table == null ? "" : table, onlyInSource, onlyInTarget,
                mismatches, sameKeys(sourcePrimaryKeys, targetPrimaryKeys),
                sourceRows, targetRows);
    }

    private static Map<String, Column> index(List<Column> columns) {
        Map<String, Column> result = new LinkedHashMap<>();
        if (columns == null) {
            return result;
        }
        for (Column column : columns) {
            if (column != null && column.name() != null) {
                result.put(column.name().toLowerCase(Locale.ROOT), column);
            }
        }
        return result;
    }

    private static boolean sameType(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameKeys(List<String> left, List<String> right) {
        List<String> a = left == null ? List.of() : left;
        List<String> b = right == null ? List.of() : right;
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!normalize(a.get(i)).equals(normalize(b.get(i)))) {
                return false;
            }
        }
        return true;
    }
}
