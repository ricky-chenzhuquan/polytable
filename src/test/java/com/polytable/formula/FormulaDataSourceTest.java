package com.polytable.formula;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormulaDataSourceTest {

    @Test
    void loadsUniqueCrossTableReferencesInOneBatch() {
        AtomicInteger calls = new AtomicInteger();
        FormulaDataSource source = references -> {
            calls.incrementAndGet();
            return references.stream().collect(java.util.stream.Collectors.toMap(
                    reference -> reference,
                    reference -> FormulaValue.text(reference.fieldKey())));
        };
        var shared = new FormulaDataSource.RecordReference("sales", "r1", "amount");
        Set<FormulaDataSource.RecordReference> dependencies = new LinkedHashSet<>(Set.of(
                shared, new FormulaDataSource.RecordReference("sales", "r2", "amount")));

        Map<FormulaDataSource.RecordReference, FormulaValue> result = source.loadBatch(dependencies);

        assertEquals(1, calls.get());
        assertEquals(2, result.size());
        assertEquals("amount", result.get(shared).asText());
    }
}
