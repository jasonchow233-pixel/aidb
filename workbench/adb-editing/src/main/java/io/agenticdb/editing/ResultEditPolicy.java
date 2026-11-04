package io.agenticdb.editing;

import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.sql.SqlQueryShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一屏查询结果能不能按主键改回表里 —— 拿 {@link SqlQueryShape} 认出的形状，
 * 加上<b>真表的列与主键</b>（{@link TableSchema}）和<b>驱动实际返回的列标签</b>做判定。
 *
 * <p>三层各管一段，谁也不越界：
 * <ul>
 *   <li>{@link SqlQueryShape} 只看 SQL 文本，不碰数据库；</li>
 *   <li>本类只做映射与主键核验，不连库、不执行；</li>
 *   <li>界面按 {@link Decision} 决定「能不能点开一格」，说不清的原因由
 *       {@link Blocked#messageKey()} 去资源里取那句人话。</li>
 * </ul>
 *
 * <p>⭐ 判据一律**保守**：宁可说「这一屏不能改」，也不要宣布可编辑之后
 * 把 UPDATE 打到另一张表、或者打到不止一行上。列标签对不上、主键没在结果里、
 * 两列共用一个名字 —— 全都拒绝，并且各说一句原因。
 */
public final class ResultEditPolicy {

    /**
     * 形状认出来了，但内省这一层拦住的卡点。
     *
     * <p>键同样写成字面量，交给 {@code I18nAuditTest} 的模型层那条门禁对账。
     */
    public enum Block {
        /** {@code FROM 库.表}：限定的那一段到底是 catalog 还是 schema，各家不同，不猜。 */
        OTHER_SCHEMA("query.edit.blocked.otherSchema"),
        /** 内省读不到这张表（被删了、改名了、没权限）。 */
        TABLE_UNREADABLE("query.edit.blocked.tableUnreadable"),
        /** 表没有主键：UPDATE 可能一次命中多行。 */
        NO_PRIMARY_KEY("query.edit.blocked.noPrimaryKey"),
        /** {@code SELECT *, id …}：星号与具名列混在一起，行列对不上号。 */
        MIXED_PROJECTION("query.edit.blocked.mixedProjection"),
        /** 结果里两个格子同名：改哪一个说不清。 */
        AMBIGUOUS_LABEL("query.edit.blocked.ambiguousLabel"),
        /** 结果里有一列在表里找不到：不能凭猜写回。 */
        COLUMN_NOT_IN_TABLE("query.edit.blocked.columnNotInTable"),
        /** 主键列没被投影出来：拿不到定位一行所需的值。 */
        PRIMARY_KEY_NOT_SELECTED("query.edit.blocked.primaryKeyNotSelected");

        private final String messageKey;

        Block(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * 一格结果对应的写回目标。
     *
     * @param resultKey  行 Map 里的键（重名列由 {@code ExecutionResult.columnKeys()} 加后缀）
     * @param column     表里的列名，大小写按内省结果
     * @param definition 表侧的列定义（类型 / 可空 / 长度），解析用户输入时用它
     * @param primaryKey 这一列是不是主键 —— 改它等于挪行，界面要另眼看
     */
    public record Cell(String resultKey, String column, ColumnDef definition, boolean primaryKey) {
    }

    /** 判定结果的公共形状。 */
    public sealed interface Decision permits Editable, Blocked {
    }

    /** 能改：给出表名与每一格的写回目标（顺序与结果列一致）。 */
    public record Editable(String table, List<Cell> cells) implements Decision {

        public Editable {
            cells = List.copyOf(cells);
        }

        /** 主键格：构造 {@link RowIdentity} 时要从这几格取值。 */
        public List<Cell> keyCells() {
            return cells.stream().filter(Cell::primaryKey).toList();
        }

        /** 按行 Map 的键找一格；找不到给 null（调用方据此拒绝，不猜）。 */
        public Cell cell(String resultKey) {
            for (Cell cell : cells) {
                if (cell.resultKey().equals(resultKey)) {
                    return cell;
                }
            }
            return null;
        }
    }

    /** 不能改：一句人话的键 + 一段只进日志的技术细节。 */
    public record Blocked(String messageKey, String detail) implements Decision {

        /** 同一个卡点从两层来（形状 / 内省），键都得能在资源里找到。 */
        static Blocked of(Block block, String detail) {
            return new Blocked(block.messageKey(), detail == null ? "" : detail);
        }

        static Blocked of(SqlQueryShape.Block block, String detail) {
            return new Blocked(block.messageKey(), detail == null ? "" : detail);
        }
    }

    private ResultEditPolicy() {
    }

    /**
     * 判定一屏结果。
     *
     * @param shape  {@link SqlQueryShape#parse} 的结果；被形状层拦下的直接带着它的键出来
     * @param schema {@code SchemaService.describe} 读到的表结构
     * @param labels 驱动返回的列标签，按结果列顺序（{@code ColumnDef.name()}）
     * @param keys   行 Map 实际用的键（{@code ExecutionResult.columnKeys()}）
     */
    public static Decision judge(SqlQueryShape.Shape shape, TableSchema schema,
                                 List<String> labels, List<String> keys) {
        if (shape instanceof SqlQueryShape.Refused refused) {
            return Blocked.of(refused.reason(), refused.detail());
        }
        if (labels.size() != keys.size()) {
            throw new IllegalArgumentException(
                    "labels and keys must have the same size: " + labels.size() + " vs " + keys.size());
        }
        SqlQueryShape.Select select = ((SqlQueryShape.Readable) shape).select();

        if (!select.schema().isEmpty()) {
            return Blocked.of(Block.OTHER_SCHEMA, select.schema() + "." + select.table());
        }
        if (schema == null || schema.columns().isEmpty()) {
            return Blocked.of(Block.TABLE_UNREADABLE, select.table());
        }
        // 内省读的是「当前库里的这个名字」；万一驱动把名字改了大小写，表还是那张，
        // 但名字对不上说明 describe 拿到的不是查询里的表 —— 保守拒绝。
        if (!schema.name().equalsIgnoreCase(select.table())) {
            return Blocked.of(Block.TABLE_UNREADABLE, select.table() + " / " + schema.name());
        }
        if (schema.primaryKeys().isEmpty()) {
            return Blocked.of(Block.NO_PRIMARY_KEY, schema.name());
        }
        if (select.allColumns() && !select.columns().isEmpty()) {
            return Blocked.of(Block.MIXED_PROJECTION, select.table());
        }

        Map<String, ColumnDef> tableColumns = new LinkedHashMap<>();
        for (ColumnDef column : schema.columns()) {
            tableColumns.put(lower(column.name()), column);
        }

        List<Cell> cells = new ArrayList<>(labels.size());
        Map<String, String> seenColumns = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null || label.isBlank()) {
                return Blocked.of(Block.COLUMN_NOT_IN_TABLE, "column #" + (i + 1));
            }
            String real = select.allColumns()
                    ? label
                    : mappedColumn(select.columns(), label);
            if (real == null) {
                return Blocked.of(Block.COLUMN_NOT_IN_TABLE, label);
            }
            ColumnDef definition = tableColumns.get(lower(real));
            if (definition == null) {
                return Blocked.of(Block.COLUMN_NOT_IN_TABLE, label + " -> " + real);
            }
            String previous = seenColumns.putIfAbsent(lower(definition.name()), label);
            if (previous != null) {
                return Blocked.of(Block.AMBIGUOUS_LABEL, definition.name());
            }
            cells.add(new Cell(keys.get(i), definition.name(), definition,
                    schema.isPrimaryKey(definition.name())));
        }

        List<String> missing = new ArrayList<>();
        for (String key : schema.primaryKeys()) {
            if (!seenColumns.containsKey(lower(key))) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return Blocked.of(Block.PRIMARY_KEY_NOT_SELECTED, String.join(",", missing));
        }
        return new Editable(schema.name(), cells);
    }

    /**
     * 具名投影里，结果标签 {@code label} 对应表里的哪一列。
     *
     * <p>按**名字**找而不是按位置：位置对齐看着更严，可星号与别名一混就会错配，
     * 而错配的后果是把值写到另一列上。同名多列由 {@link Block#AMBIGUOUS_LABEL} 那一段拦。
     */
    private static String mappedColumn(List<SqlQueryShape.Column> projected, String label) {
        for (SqlQueryShape.Column column : projected) {
            if (column.label().equalsIgnoreCase(label)) {
                return column.column();
            }
        }
        return null;
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
