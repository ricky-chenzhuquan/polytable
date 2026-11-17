package com.polytable.formula;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedFormulaResultCacheTest {

    @Test
    void reusesOnlyExactRowFormulaAndCrossTableVersions() throws Exception {
        var cache = new BoundedFormulaResultCache(8, 4096);
        var key = key("row-1", "r1", "f1", "cross-1", 0);
        AtomicInteger loads = new AtomicInteger();
        var expected = FormulaValue.text("value");

        assertEquals(expected, cache.getOrCompute(key, () -> { loads.incrementAndGet(); return expected; }).get());
        assertEquals(expected, cache.getOrCompute(key, () -> { loads.incrementAndGet(); return FormulaValue.text("wrong"); }).get());
        assertEquals(1, loads.get());
        cache.getOrCompute(key("row-2", "r1", "f1", "cross-1", 0), () -> { loads.incrementAndGet(); return FormulaValue.text("new row"); }).get();
        cache.getOrCompute(key("row-1", "r1", "f2", "cross-1", 0), () -> { loads.incrementAndGet(); return FormulaValue.text("new formula"); }).get();
        cache.getOrCompute(key("row-1", "r1", "f1", "cross-2", 0), () -> { loads.incrementAndGet(); return FormulaValue.text("new cross table"); }).get();
        cache.getOrCompute(key("row-1", "r1", "f1", "cross-1", 123), () -> { loads.incrementAndGet(); return FormulaValue.text("new time bucket"); }).get();
        assertEquals(5, loads.get());
    }

    @Test
    void evictsByLeastRecentlyUsedAndEnforcesByteAndEntryLimits() throws Exception {
        var cache = new BoundedFormulaResultCache(2, 512);
        var first = key("r1", "r1", "f1", "x", 0);
        var second = key("r2", "r2", "f1", "x", 0);
        var third = key("r3", "r3", "f1", "x", 0);
        cache.getOrCompute(first, () -> FormulaValue.text("one")).get();
        cache.getOrCompute(second, () -> FormulaValue.text("two")).get();
        cache.get(first).orElseThrow();
        cache.getOrCompute(third, () -> FormulaValue.text("three")).get();

        assertEquals(2, cache.size());
        assertEquals(false, cache.get(second).isPresent());
        assertEquals(true, cache.get(first).isPresent());
        assertEquals(true, cache.currentBytes() <= 512);
    }

    @Test
    void mergesConcurrentLoadsAndDoesNotCacheFailures() throws Exception {
        var cache = new BoundedFormulaResultCache(4, 2048);
        var key = key("r1", "r1", "f1", "x", 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> cache.getOrCompute(key, () -> {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return FormulaValue.decimal(java.math.BigDecimal.TEN);
            }).join());
            entered.await(2, TimeUnit.SECONDS);
            var second = executor.submit(() -> cache.getOrCompute(key, () -> {
                calls.incrementAndGet();
                return FormulaValue.decimal(java.math.BigDecimal.ZERO);
            }).join());
            release.countDown();
            assertEquals(java.math.BigDecimal.TEN, first.get().asDecimal());
            assertEquals(java.math.BigDecimal.TEN, second.get().asDecimal());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, calls.get());

        var failing = key("bad", "bad", "f1", "x", 0);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> cache.getOrCompute(failing, () -> { throw new IllegalStateException("expected failure"); }).get());
        assertEquals(false, cache.get(failing).isPresent());
    }

    @Test
    void doesNotRetainAnIndividualResultLargerThanTheByteBudget() throws Exception {
        var cache = new BoundedFormulaResultCache(10, 180);
        var key = key("r1", "r1", "f1", "x", 0);
        var large = FormulaValue.text("x".repeat(1024));

        assertEquals(large, cache.getOrCompute(key, () -> large).get());
        assertEquals(0, cache.size());
        assertEquals(0, cache.currentBytes());
    }

    private static BoundedFormulaResultCache.Key key(String rowVersion, String rowId, String formulaVersion,
                                                     String crossTableVersion, long timeBucket) {
        return new BoundedFormulaResultCache.Key("table", rowId, "field", rowVersion,
                formulaVersion, crossTableVersion, timeBucket);
    }
}
