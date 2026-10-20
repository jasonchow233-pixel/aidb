package io.agenticdb.editing;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EditBufferTest {

    private static final RowIdentity ROW = RowIdentity.from(
            List.of("id"), Map.of("id", 2));

    @Test
    void undoRedoRestoresEmptyStateAndValues() {
        EditBuffer buffer = new EditBuffer("users");

        assertThat(buffer.setCell(ROW, "name", "User 002", "Renamed")).isTrue();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("Renamed");
        assertThat(buffer.dirtyCount()).isEqualTo(1);

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("User 002");
        assertThat(buffer.isDirty()).isFalse();

        assertThat(buffer.redo()).isTrue();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("Renamed");
        assertThat(buffer.dirtyCount()).isEqualTo(1);
    }

    @Test
    void repeatedEditsUndoInOrder() {
        EditBuffer buffer = new EditBuffer("users");
        buffer.setCell(ROW, "name", "User 002", "A");
        buffer.setCell(ROW, "name", "User 002", "B");

        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("B");
        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("A");
        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("User 002");
        assertThat(buffer.dirtyCount()).isZero();
    }

    @Test
    void groupsChangesByPrimaryKey() {
        EditBuffer buffer = new EditBuffer("users");
        RowIdentity other = RowIdentity.from(List.of("id"), Map.of("id", 3));
        buffer.setCell(ROW, "name", "User 002", "A");
        buffer.setCell(ROW, "bio", "Bio", null);
        buffer.setCell(other, "name", "User 003", "");

        Map<RowIdentity, Map<String, Object>> rows = buffer.editsByRow();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(ROW)).containsEntry("name", "A");
        assertThat(rows.get(ROW)).containsEntry("bio", null);
        assertThat(rows.get(other)).containsEntry("name", "");
    }

    @Test
    void deleteRowCollapsesCellEditsAndCanBeUndone() {
        EditBuffer buffer = new EditBuffer("users");
        buffer.setCell(ROW, "name", "User 002", "Renamed");
        assertThat(buffer.dirtyCount()).isEqualTo(1);

        assertThat(buffer.deleteRow(ROW)).isTrue();
        assertThat(buffer.isDeleted(ROW)).isTrue();
        assertThat(buffer.dirtyCount()).isEqualTo(1);
        assertThat(buffer.editsByRow()).isEmpty();

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.isDeleted(ROW)).isFalse();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("Renamed");
        assertThat(buffer.dirtyCount()).isEqualTo(1);

        assertThat(buffer.redo()).isTrue();
        assertThat(buffer.isDeleted(ROW)).isTrue();
        assertThat(buffer.dirtyCount()).isEqualTo(1);
    }

    @Test
    void batchDeleteUsesASingleUndoStep() {
        EditBuffer buffer = new EditBuffer("users");
        RowIdentity second = new RowIdentity(Map.of("id", 3));
        buffer.setCell(ROW, "name", "User 002", "Renamed");

        assertThat(buffer.deleteRows(List.of(ROW, second))).isEqualTo(2);
        assertThat(buffer.isDeleted(ROW)).isTrue();
        assertThat(buffer.isDeleted(second)).isTrue();
        assertThat(buffer.dirtyCount()).isEqualTo(2);

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.isDirty()).isTrue();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("Renamed");

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.isDirty()).isFalse();

        assertThat(buffer.redo()).isTrue();
        assertThat(buffer.redo()).isTrue();
        assertThat(buffer.deletedRows()).containsExactlyInAnyOrder(ROW, second);
    }

    @Test
    void refusesCellEditOnDeletedRow() {
        EditBuffer buffer = new EditBuffer("users");
        buffer.deleteRow(ROW);

        assertThat(buffer.setCell(ROW, "name", "User 002", "x")).isFalse();
        assertThat(buffer.hasCell(ROW, "name")).isFalse();
    }

    @Test
    void draftRowsCanBeEditedAndRemovedWithUndo() {
        EditBuffer buffer = new EditBuffer("users");
        long id = buffer.addDraft(Map.of("name", "New", "email", "new@example.com"));
        assertThat(buffer.hasDraft(id)).isTrue();
        assertThat(buffer.dirtyCount()).isEqualTo(1);

        assertThat(buffer.setDraftCell(id, "bio", "hello")).isTrue();
        assertThat(buffer.draftValue(id, "bio", null)).isEqualTo("hello");
        assertThat(buffer.hasDraftValue(id, "bio")).isTrue();

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.hasDraftValue(id, "bio")).isFalse();
        assertThat(buffer.draftValue(id, "bio", null)).isNull();

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.hasDraft(id)).isFalse();
        assertThat(buffer.isDirty()).isFalse();

        assertThat(buffer.redo()).isTrue();
        assertThat(buffer.hasDraft(id)).isTrue();
        assertThat(buffer.redo()).isTrue();
        assertThat(buffer.draftValue(id, "bio", null)).isEqualTo("hello");

        assertThat(buffer.removeDraft(id)).isTrue();
        assertThat(buffer.hasDraft(id)).isFalse();
        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.draftValue(id, "bio", null)).isEqualTo("hello");
    }

    @Test
    void batchDraftsUseOneUndoStep() {
        EditBuffer buffer = new EditBuffer("users");
        List<Long> ids = buffer.addDrafts(List.of(
                Map.of("name", "A", "email", "a@example.com"),
                Map.of("name", "B", "email", "b@example.com")));

        assertThat(ids).hasSize(2);
        assertThat(buffer.dirtyCount()).isEqualTo(2);
        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.drafts()).isEmpty();
        assertThat(buffer.redo()).isTrue();
        assertThat(buffer.drafts()).hasSize(2);
    }

    @Test
    void batchValuesUseOneUndoStepForRowsAndDrafts() {
        EditBuffer buffer = new EditBuffer("users");
        RowIdentity second = new RowIdentity(Map.of("id", 3));
        long draft = buffer.addDraft(Map.of("name", "Draft"));

        int changed = buffer.setValues(
                List.of(new EditBuffer.CellWrite(ROW, "name", "User 002", null),
                        new EditBuffer.CellWrite(second, "name", "User 003", null)),
                List.of(new EditBuffer.DraftWrite(draft, "name", null)));

        assertThat(changed).isEqualTo(3);
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isNull();
        assertThat(buffer.valueOr(second, "name", "User 003")).isNull();
        assertThat(buffer.draftValue(draft, "name", null)).isNull();

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.valueOr(ROW, "name", "User 002")).isEqualTo("User 002");
        assertThat(buffer.valueOr(second, "name", "User 003")).isEqualTo("User 003");
        assertThat(buffer.draftValue(draft, "name", null)).isEqualTo("Draft");
        assertThat(buffer.dirtyCount()).isEqualTo(1);
    }

    @Test
    void mixedDeleteOfRowsAndDraftsUsesOneUndoStep() {
        EditBuffer buffer = new EditBuffer("users");
        RowIdentity second = new RowIdentity(Map.of("id", 3));
        long draft = buffer.addDraft(Map.of("name", "Draft"));

        int removed = buffer.removeRows(
                Map.of(ROW, Map.of("id", 2, "name", "User 002"),
                        second, Map.of("id", 3, "name", "User 003")),
                List.of(draft));

        assertThat(removed).isEqualTo(3);
        assertThat(buffer.isDeleted(ROW)).isTrue();
        assertThat(buffer.isDeleted(second)).isTrue();
        assertThat(buffer.hasDraft(draft)).isFalse();

        assertThat(buffer.undo()).isTrue();
        assertThat(buffer.isDeleted(ROW)).isFalse();
        assertThat(buffer.hasDraft(draft)).isTrue();
        assertThat(buffer.dirtyCount()).isEqualTo(1);
    }

    @Test
    void rowIdentityCopiesKeyValuesAndKeepsNullDistinct() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7);
        RowIdentity identity = RowIdentity.from(List.of("id"), row);

        assertThat(identity.display()).isEqualTo("id=7");
    }
}
