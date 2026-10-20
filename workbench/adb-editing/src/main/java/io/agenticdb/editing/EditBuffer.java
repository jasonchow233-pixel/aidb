package io.agenticdb.editing;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 表级编辑缓冲。
 *
 * <p>⛔ 单元格输入不直接写库。每次修改只进缓冲，保存时按主键生成 UPDATE/DELETE；
 * 这样撤销/重做、脏标记和 SQL 预览共用同一份真相。
 */
public final class EditBuffer {

    public sealed interface Change permits CellEdit, DeleteEdit, BatchChange,
            AddDraftEdit, RemoveDraftEdit, DraftCellEdit {
    }

    public record CellEdit(CellKey cell, Object original, Object previous, Object next)
            implements Change {
    }

    public record DeleteEdit(RowIdentity row,
                             Map<String, Object> rowValues,
                             Map<CellKey, Object> cellValues,
                             Map<CellKey, Object> cellOriginals) implements Change {
        public DeleteEdit {
            rowValues = Collections.unmodifiableMap(new LinkedHashMap<>(rowValues));
            cellValues = Collections.unmodifiableMap(new LinkedHashMap<>(cellValues));
            cellOriginals = Collections.unmodifiableMap(new LinkedHashMap<>(cellOriginals));
        }
    }

    /** 一次批量单元格修改的输入；{@code original} 是数据库当前值。 */
    public record CellWrite(RowIdentity row, String column, Object original, Object next) {
    }

    /** 一次批量草稿行修改的输入。 */
    public record DraftWrite(long id, String column, Object next) {
    }

    public record AddDraftEdit(long id, Map<String, Object> values) implements Change {
        public AddDraftEdit {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    public record RemoveDraftEdit(long id, Map<String, Object> values) implements Change {
        public RemoveDraftEdit {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    public record DraftCellEdit(long id, String column, boolean previousPresent,
                                Object previous, Object next) implements Change {
    }

    /** 批量操作只占一个撤销栈位，一次 ⌘Z 恢复整批。 */
    public record BatchChange(List<Change> changes) implements Change {
        public BatchChange {
            changes = List.copyOf(changes);
        }
    }

    /** 新增行在表格里的隐藏标记；真实数据保存时不会包含这个键。 */
    public static final String DRAFT_KEY = "__adb_draft";

    private final String table;
    private final Map<CellKey, Object> edited = new LinkedHashMap<>();
    private final Map<CellKey, Object> originals = new LinkedHashMap<>();
    private final Set<RowIdentity> deleted = new java.util.LinkedHashSet<>();
    private final Map<Long, Map<String, Object>> drafts = new LinkedHashMap<>();
    private long nextDraftId = 1;
    private final Deque<Change> undoStack = new ArrayDeque<>();
    private final Deque<Change> redoStack = new ArrayDeque<>();

    public EditBuffer(String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        this.table = table;
    }

    public String table() {
        return table;
    }

    /**
     * 记录一次单元格修改。
     *
     * @param original 数据库当前值（不是上一次缓冲值）
     * @return 是否真的改变了
     */
    public boolean setCell(RowIdentity row, String column, Object original, Object next) {
        if (deleted.contains(row)) {
            return false;
        }
        CellKey key = new CellKey(row, column);
        Object previous = edited.containsKey(key) ? edited.get(key) : original;
        if (Objects.deepEquals(previous, next)) {
            return false;
        }
        originals.putIfAbsent(key, original);
        Object baseline = originals.get(key);
        if (Objects.deepEquals(baseline, next)) {
            edited.remove(key);
        } else {
            edited.put(key, next);
        }
        undoStack.push(new CellEdit(key, baseline, previous, next));
        redoStack.clear();
        return true;
    }

    /**
     * 标记删除。该行已有的单元格修改会一并收起，撤销删除时恢复。
     */
    public boolean deleteRow(RowIdentity row) {
        return deleteRow(row, Map.of());
    }

    /**
     * 标记删除，并保留整行原值供保存后的反向撤销使用。
     */
    public boolean deleteRow(RowIdentity row, Map<String, Object> rowValues) {
        if (deleted.contains(row)) {
            return false;
        }
        DeleteEdit change = markDeleted(row, rowValues);
        undoStack.push(change);
        redoStack.clear();
        return true;
    }

    /**
     * 批量标记删除，整批只占一个撤销栈位。
     *
     * @return 实际标记的行数
     */
    public int deleteRows(java.util.Collection<RowIdentity> rows) {
        Map<RowIdentity, Map<String, Object>> values = new LinkedHashMap<>();
        rows.forEach(row -> values.put(row, Map.of()));
        return deleteRows(values);
    }

    /**
     * 批量标记删除；{@code rowValues} 缺失的行仍可删除，但不能做保存后的反向撤销。
     */
    public int deleteRows(Map<RowIdentity, Map<String, Object>> rows) {
        List<Change> changes = new java.util.ArrayList<>();
        for (Map.Entry<RowIdentity, Map<String, Object>> entry : rows.entrySet()) {
            RowIdentity row = entry.getKey();
            if (!deleted.contains(row)) {
                changes.add(markDeleted(row, entry.getValue()));
            }
        }
        if (changes.isEmpty()) {
            return 0;
        }
        undoStack.push(new BatchChange(changes));
        redoStack.clear();
        return changes.size();
    }

    private DeleteEdit markDeleted(RowIdentity row, Map<String, Object> rowValues) {
        Map<CellKey, Object> cellValues = new LinkedHashMap<>();
        Map<CellKey, Object> cellOriginals = new LinkedHashMap<>();
        edited.forEach((key, value) -> {
            if (key.row().equals(row)) {
                cellValues.put(key, value);
                cellOriginals.put(key, originals.get(key));
            }
        });
        edited.keySet().removeIf(key -> key.row().equals(row));
        originals.keySet().removeIf(key -> key.row().equals(row));
        deleted.add(row);
        return new DeleteEdit(row, rowValues == null ? Map.of() : rowValues,
                cellValues, cellOriginals);
    }

    /** 在缓冲里新增一行，返回稳定的草稿 id。 */
    public long addDraft(Map<String, Object> values) {
        long id = nextDraftId++;
        Map<String, Object> copy = new LinkedHashMap<>(values == null ? Map.of() : values);
        drafts.put(id, copy);
        undoStack.push(new AddDraftEdit(id, copy));
        redoStack.clear();
        return id;
    }

    /** 批量新增，整批只占一个撤销栈位；返回新建草稿的 id。 */
    public List<Long> addDrafts(List<Map<String, Object>> values) {
        List<Change> changes = new java.util.ArrayList<>();
        List<Long> ids = new java.util.ArrayList<>();
        for (Map<String, Object> row : values) {
            long id = nextDraftId++;
            Map<String, Object> copy = new LinkedHashMap<>(row == null ? Map.of() : row);
            drafts.put(id, copy);
            changes.add(new AddDraftEdit(id, copy));
            ids.add(id);
        }
        if (!changes.isEmpty()) {
            undoStack.push(new BatchChange(changes));
            redoStack.clear();
        }
        return ids;
    }

    public boolean setDraftCell(long id, String column, Object next) {
        Map<String, Object> values = drafts.get(id);
        if (values == null) {
            return false;
        }
        boolean present = values.containsKey(column);
        Object previous = values.get(column);
        if (present && Objects.deepEquals(previous, next)) {
            return false;
        }
        values.put(column, next);
        undoStack.push(new DraftCellEdit(id, column, present, previous, next));
        redoStack.clear();
        return true;
    }

    /**
     * 批量设置已有行与草稿行的同一列，整批只占一个撤销栈位。
     *
     * @return 实际改变的行数
     */
    public int setValues(List<CellWrite> cells, List<DraftWrite> draftWrites) {
        List<Change> changes = new java.util.ArrayList<>();
        for (CellWrite write : cells) {
            if (deleted.contains(write.row())) {
                continue;
            }
            CellKey key = new CellKey(write.row(), write.column());
            Object previous = edited.containsKey(key) ? edited.get(key) : write.original();
            if (Objects.deepEquals(previous, write.next())) {
                continue;
            }
            originals.putIfAbsent(key, write.original());
            Object baseline = originals.get(key);
            if (Objects.deepEquals(baseline, write.next())) {
                edited.remove(key);
            } else {
                edited.put(key, write.next());
            }
            changes.add(new CellEdit(key, baseline, previous, write.next()));
        }
        for (DraftWrite write : draftWrites) {
            Map<String, Object> values = drafts.get(write.id());
            if (values == null) {
                continue;
            }
            boolean present = values.containsKey(write.column());
            Object previous = values.get(write.column());
            if (present && Objects.deepEquals(previous, write.next())) {
                continue;
            }
            values.put(write.column(), write.next());
            changes.add(new DraftCellEdit(write.id(), write.column(), present,
                    previous, write.next()));
        }
        if (changes.isEmpty()) {
            return 0;
        }
        undoStack.push(new BatchChange(changes));
        redoStack.clear();
        return changes.size();
    }

    public boolean removeDraft(long id) {
        Map<String, Object> values = drafts.remove(id);
        if (values == null) {
            return false;
        }
        undoStack.push(new RemoveDraftEdit(id, values));
        redoStack.clear();
        return true;
    }

    public int removeDrafts(java.util.Collection<Long> ids) {
        List<Change> changes = new java.util.ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> values = drafts.remove(id);
            if (values != null) {
                changes.add(new RemoveDraftEdit(id, values));
            }
        }
        if (changes.isEmpty()) {
            return 0;
        }
        undoStack.push(new BatchChange(changes));
        redoStack.clear();
        return changes.size();
    }

    /**
     * 混合删除已有行与新增草稿行，整批只占一个撤销栈位。
     *
     * @return 实际移除/标记的行数
     */
    public int removeRows(Map<RowIdentity, Map<String, Object>> rows,
                          java.util.Collection<Long> draftIds) {
        List<Change> changes = new java.util.ArrayList<>();
        for (Map.Entry<RowIdentity, Map<String, Object>> entry : rows.entrySet()) {
            if (!deleted.contains(entry.getKey())) {
                changes.add(markDeleted(entry.getKey(), entry.getValue()));
            }
        }
        for (Long id : draftIds) {
            Map<String, Object> values = drafts.remove(id);
            if (values != null) {
                changes.add(new RemoveDraftEdit(id, values));
            }
        }
        if (changes.isEmpty()) {
            return 0;
        }
        undoStack.push(new BatchChange(changes));
        redoStack.clear();
        return changes.size();
    }

    public boolean hasDraft(long id) {
        return drafts.containsKey(id);
    }

    public Object draftValue(long id, String column, Object fallback) {
        Map<String, Object> values = drafts.get(id);
        return values != null && values.containsKey(column) ? values.get(column) : fallback;
    }

    public boolean hasDraftValue(long id, String column) {
        Map<String, Object> values = drafts.get(id);
        return values != null && values.containsKey(column);
    }

    public Map<Long, Map<String, Object>> drafts() {
        Map<Long, Map<String, Object>> copy = new LinkedHashMap<>();
        drafts.forEach((id, values) ->
                copy.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        return Collections.unmodifiableMap(copy);
    }

    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        Change change = undoStack.pop();
        undoOne(change);
        redoStack.push(change);
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        Change change = redoStack.pop();
        redoOne(change);
        undoStack.push(change);
        return true;
    }

    private void undoOne(Change change) {
        switch (change) {
            case CellEdit cell -> restore(cell.cell(), cell.previous(), cell.original());
            case DeleteEdit delete -> restoreDeleted(delete);
            case AddDraftEdit add -> drafts.remove(add.id());
            case RemoveDraftEdit remove -> drafts.put(remove.id(),
                    new LinkedHashMap<>(remove.values()));
            case DraftCellEdit cell -> applyDraftValue(cell, cell.previousPresent(), cell.previous());
            case BatchChange batch -> {
                for (int i = batch.changes().size() - 1; i >= 0; i--) {
                    undoOne(batch.changes().get(i));
                }
            }
        }
    }

    private void redoOne(Change change) {
        switch (change) {
            case CellEdit cell -> restore(cell.cell(), cell.next(), cell.original());
            case DeleteEdit delete -> applyDeleted(delete);
            case AddDraftEdit add -> drafts.put(add.id(), new LinkedHashMap<>(add.values()));
            case RemoveDraftEdit remove -> drafts.remove(remove.id());
            case DraftCellEdit cell -> applyDraftValue(cell, true, cell.next());
            case BatchChange batch -> {
                for (Change item : batch.changes()) {
                    redoOne(item);
                }
            }
        }
    }

    private void applyDraftValue(DraftCellEdit change, boolean present, Object value) {
        Map<String, Object> values = drafts.get(change.id());
        if (values == null) {
            return;
        }
        if (present) {
            values.put(change.column(), value);
        } else {
            values.remove(change.column());
        }
    }

    private void restore(CellKey key, Object value, Object original) {
        if (Objects.deepEquals(value, original)) {
            edited.remove(key);
        } else {
            edited.put(key, value);
        }
    }

    private void restoreDeleted(DeleteEdit delete) {
        deleted.remove(delete.row());
        edited.putAll(delete.cellValues());
        originals.putAll(delete.cellOriginals());
    }

    private void applyDeleted(DeleteEdit delete) {
        deleted.add(delete.row());
        edited.keySet().removeIf(key -> key.row().equals(delete.row()));
        originals.keySet().removeIf(key -> key.row().equals(delete.row()));
    }

    public boolean hasCell(RowIdentity row, String column) {
        return edited.containsKey(new CellKey(row, column));
    }

    public boolean hasRowEdits(RowIdentity row) {
        return edited.keySet().stream().anyMatch(key -> key.row().equals(row));
    }

    /** 缓冲中的当前值；没有修改时返回 {@code fallback}。 */
    public Object valueOr(RowIdentity row, String column, Object fallback) {
        CellKey key = new CellKey(row, column);
        return edited.containsKey(key) ? edited.get(key) : fallback;
    }

    public Map<CellKey, Object> edits() {
        return Collections.unmodifiableMap(edited);
    }

    public Map<CellKey, Object> originals() {
        return Collections.unmodifiableMap(originals);
    }

    public boolean isDeleted(RowIdentity row) {
        return deleted.contains(row);
    }

    public Set<RowIdentity> deletedRows() {
        return Collections.unmodifiableSet(deleted);
    }

    /** 删除行的整行原值；用于生成保存后的反向 INSERT。 */
    public Map<RowIdentity, Map<String, Object>> deletedRowValues() {
        Map<RowIdentity, Map<String, Object>> values = new LinkedHashMap<>();
        for (Change change : undoStack) {
            collectDeletedValues(change, values);
        }
        for (Change change : redoStack) {
            collectDeletedValues(change, values);
        }
        values.keySet().retainAll(deleted);
        return Collections.unmodifiableMap(values);
    }

    private static void collectDeletedValues(Change change,
                                             Map<RowIdentity, Map<String, Object>> values) {
        if (change instanceof DeleteEdit delete) {
            if (!delete.rowValues().isEmpty()) {
                values.putIfAbsent(delete.row(), delete.rowValues());
            }
        } else if (change instanceof BatchChange batch) {
            for (Change item : batch.changes()) {
                collectDeletedValues(item, values);
            }
        }
    }

    /** 按行分组，保存时一行只生成一条 UPDATE。 */
    public Map<RowIdentity, Map<String, Object>> editsByRow() {
        Map<RowIdentity, Map<String, Object>> rows = new LinkedHashMap<>();
        edited.forEach((key, value) -> rows
                .computeIfAbsent(key.row(), ignored -> new LinkedHashMap<>())
                .put(key.column(), value));
        return rows;
    }

    public boolean isDirty() {
        return !edited.isEmpty() || !deleted.isEmpty() || !drafts.isEmpty();
    }

    public int dirtyCount() {
        return edited.size() + deleted.size() + drafts.size();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public List<Change> undoHistory() {
        return List.copyOf(undoStack);
    }

    public void clear() {
        edited.clear();
        originals.clear();
        deleted.clear();
        drafts.clear();
        nextDraftId = 1;
        undoStack.clear();
        redoStack.clear();
    }
}
