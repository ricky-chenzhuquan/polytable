package com.polytable.formula;

import java.util.Map;
import java.util.Set;

/** Batch boundary for cross-table formula references; implementations own storage access. */
@FunctionalInterface
public interface FormulaDataSource {

    Map<RecordReference, FormulaValue> loadBatch(Set<RecordReference> references);

    record RecordReference(String tableId, String recordId, String fieldKey) {
        public RecordReference {
            if (tableId == null || tableId.isBlank()) throw new IllegalArgumentException("tableId cannot be blank");
            if (recordId == null || recordId.isBlank()) throw new IllegalArgumentException("recordId cannot be blank");
            if (fieldKey == null || fieldKey.isBlank()) throw new IllegalArgumentException("fieldKey cannot be blank");
        }
    }
}
