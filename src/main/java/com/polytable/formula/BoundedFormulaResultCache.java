package com.polytable.formula;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Thread-safe, byte-bounded LRU cache with single-flight computation per versioned key. */
public final class BoundedFormulaResultCache {

    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<Key, Entry> values = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key, CompletableFuture<FormulaValue>> inFlight = new java.util.HashMap<>();
    private long currentBytes;

    public BoundedFormulaResultCache(int maxEntries, long maxBytes) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public CompletableFuture<FormulaValue> getOrCompute(Key key, Supplier<FormulaValue> loader) {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(loader, "loader");
        synchronized (this) {
            Entry entry = values.get(key);
            if (entry != null) return CompletableFuture.completedFuture(entry.value());
            CompletableFuture<FormulaValue> existing = inFlight.get(key);
            if (existing != null) return existing;
            CompletableFuture<FormulaValue> future = CompletableFuture.supplyAsync(() ->
                    java.util.Objects.requireNonNull(loader.get(), "formula loader returned null"))
                    .handle((value, failure) -> {
                        synchronized (BoundedFormulaResultCache.this) {
                            inFlight.remove(key);
                            if (failure != null) {
                                throw new java.util.concurrent.CompletionException(failure);
                            }
                            put(key, value);
                            return value;
                        }
                    });
            inFlight.put(key, future);
            return future;
        }
    }

    public synchronized Optional<FormulaValue> get(Key key) {
        Entry entry = values.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.value());
    }

    public synchronized int size() {
        return values.size();
    }

    public synchronized long currentBytes() {
        return currentBytes;
    }

    private void put(Key key, FormulaValue value) {
        long bytes = estimateBytes(key, value);
        if (bytes > maxBytes) return;
        Entry previous = values.put(key, new Entry(value, bytes));
        if (previous != null) currentBytes -= previous.bytes();
        currentBytes += bytes;
        Iterator<Map.Entry<Key, Entry>> iterator = values.entrySet().iterator();
        while (values.size() > maxEntries || currentBytes > maxBytes) {
            Map.Entry<Key, Entry> eldest = iterator.next();
            currentBytes -= eldest.getValue().bytes();
            iterator.remove();
        }
    }

    private static long estimateBytes(Key key, FormulaValue value) {
        long size = 96L + stringBytes(key.tableId()) + stringBytes(key.rowId()) + stringBytes(key.fieldKey())
                + stringBytes(key.rowVersion()) + stringBytes(key.formulaVersion()) + stringBytes(key.crossTableVersion());
        return size + valueBytes(value);
    }

    private static long valueBytes(FormulaValue value) {
        return switch (value.kind()) {
            case BLANK -> 8;
            case BOOLEAN -> 16;
            case DECIMAL -> 40L + value.asDecimal().precision() / 2L;
            case TEXT -> 24L + stringBytes(value.asText());
            case DATE_TIME, RECORD_REFERENCE -> 48L + stringBytes(String.valueOf(value.rawValue()));
            case ERROR -> 32L + stringBytes(value.errorCode()) + stringBytes(value.errorMessage());
            case LIST -> 24L + ((java.util.List<?>) value.rawValue()).stream()
                    .mapToLong(item -> valueBytes((FormulaValue) item)).sum();
        };
    }

    private static long stringBytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record Entry(FormulaValue value, long bytes) { }

    public record Key(String tableId, String rowId, String fieldKey, String rowVersion,
                      String formulaVersion, String crossTableVersion, long timeBucket) {
        public Key {
            require(tableId, "tableId");
            require(rowId, "rowId");
            require(fieldKey, "fieldKey");
            rowVersion = java.util.Objects.requireNonNullElse(rowVersion, "");
            formulaVersion = java.util.Objects.requireNonNullElse(formulaVersion, "");
            crossTableVersion = java.util.Objects.requireNonNullElse(crossTableVersion, "");
        }

        private static void require(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}
