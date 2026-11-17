package com.polytable.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 中文备注：按系统负载做重接口降级；登录 / me / logout / save 永不拦。
 * elevated：拉长轮询、跳过可延后的后台静默刷新。
 * critical：协作列表自动缩小分批（游标补页），不用 A0401 挡人；超大单次拉齐仍排队。
 */
@Component
public class ServerLoadGuard {

    public enum Level {
        NORMAL, ELEVATED, CRITICAL
    }

    /** 中文备注：堆内存压力（内联实现）——used/max 越高越紧张；与 CPU 闸门取更严一档。 */
    enum MemoryLevel {
        NORMAL, ELEVATED, CRITICAL;

        static MemoryLevel of(double usedRatio) {
            if (usedRatio >= 0.92D) {
                return CRITICAL;
            }
            if (usedRatio >= 0.80D) {
                return ELEVATED;
            }
            return NORMAL;
        }
    }

    record MemorySnapshot(MemoryLevel level, long availableBytes) {
        static final MemorySnapshot EMPTY = new MemorySnapshot(MemoryLevel.NORMAL, 0L);
    }

    private final boolean enabled;
    private final double elevatedLoadPerCpu;
    private final double criticalLoadPerCpu;
    private final long sampleTtlMs;
    private final int heavyPermits;
    private final long heavyWaitMs;
    private final Semaphore heavyQueryPermits;
    private final AtomicReference<Sample> sampleRef = new AtomicReference<>(Sample.EMPTY);
    private final AtomicLong lastCleanupHintAt = new AtomicLong(0L);

    public ServerLoadGuard(@Value("${ops.load-guard.enabled:true}") boolean enabled,
                           @Value("${ops.load-guard.elevated-load-per-cpu:1.2}") double elevatedLoadPerCpu,
                           @Value("${ops.load-guard.critical-load-per-cpu:2.0}") double criticalLoadPerCpu,
                           @Value("${ops.load-guard.sample-ttl-ms:2000}") long sampleTtlMs,
                           @Value("${ops.load-guard.heavy-permits:4}") int heavyPermits,
                           @Value("${ops.load-guard.heavy-wait-ms:5000}") long heavyWaitMs) {
        this.enabled = enabled;
        this.elevatedLoadPerCpu = Math.max(0.3, elevatedLoadPerCpu);
        this.criticalLoadPerCpu = Math.max(this.elevatedLoadPerCpu, criticalLoadPerCpu);
        this.sampleTtlMs = Math.max(500L, sampleTtlMs);
        this.heavyPermits = Math.max(1, heavyPermits);
        this.heavyWaitMs = Math.max(200L, heavyWaitMs);
        this.heavyQueryPermits = new Semaphore(this.heavyPermits, true);
    }

    public boolean enabled() {
        return enabled;
    }

    public Level level() {
        return current().level();
    }

    public boolean elevated() {
        Level level = level();
        return level == Level.ELEVATED || level == Level.CRITICAL;
    }

    public boolean critical() {
        return level() == Level.CRITICAL;
    }

    /** 中文备注：内存或 CPU 偏高时推迟可延后的后台整表刷新。登录永不拦。 */
    public boolean shouldDeferBackgroundRefresh(String type) {
        return memoryPressure().level() != MemoryLevel.NORMAL || critical();
    }

    private MemorySnapshot memoryPressure() {
        try {
            MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
            long max = mx.getHeapMemoryUsage().getMax();
            long used = mx.getHeapMemoryUsage().getUsed();
            if (max <= 0) {
                return MemorySnapshot.EMPTY;
            }
            double ratio = (double) used / (double) max;
            return new MemorySnapshot(MemoryLevel.of(ratio), Math.max(0L, max - used));
        } catch (Exception e) {
            return MemorySnapshot.EMPTY;
        }
    }

    /**
     * @return true 表示已占用重查询名额（finally 必须 releaseHeavyQuery）；
     *         false 表示未占用（非 critical 或 guard 关闭）；
     *         繁忙时抛 IllegalStateException。
     */
    public boolean enterHeavyQueryOrThrow() {
        if (!enabled || !critical()) {
            return false;
        }
        try {
            if (heavyQueryPermits.tryAcquire(heavyWaitMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("服务繁忙，请稍后重试（登录不受影响）");
    }

    /**
     * 协作台列表：不缩 batch（同事操作不受限）；防 CPU 靠首屏 gzip 缓存 + 补页 JSON，不靠砍 limit。
     */
    public int capListBatchSize(int requestedLimit) {
        return Math.max(1, requestedLimit);
    }

    /**
     * 列表专用：小批已 cap 时直接查库；极大 limit 在 critical 下短暂排队，排不到也放行（已 cap）。
     */
    public boolean enterHeavyQueryForList(int cappedBatchSize) {
        if (!enabled || !critical()) {
            return false;
        }
        if (cappedBatchSize <= 150) {
            return false;
        }
        try {
            return heavyQueryPermits.tryAcquire(heavyWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void releaseHeavyQuery(boolean held) {
        if (held) {
            heavyQueryPermits.release();
        }
    }

    /** 中文备注：在线清理最多每 15s 一次，避免 30 人轮询时每次 DELETE。 */
    public boolean shouldRunPresenceCleanup() {
        long now = System.currentTimeMillis();
        long prev = lastCleanupHintAt.get();
        long interval = elevated() ? 30_000L : 15_000L;
        if (now - prev < interval) {
            return false;
        }
        return lastCleanupHintAt.compareAndSet(prev, now);
    }

    public Map<String, Object> snapshot() {
        Sample sample = current();
        MemorySnapshot memory = memoryPressure();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loadLevel", sample.level().name().toLowerCase());
        data.put("systemLoadAverage", sample.loadAverage());
        data.put("availableProcessors", sample.processors());
        data.put("elevated", sample.level() != Level.NORMAL);
        data.put("critical", sample.level() == Level.CRITICAL);
        data.put("memoryLevel", memory.level().name().toLowerCase());
        data.put("memoryAvailableBytes", memory.availableBytes());
        data.put("authNeverBlocked", true);
        return data;
    }

    private Sample current() {
        if (!enabled) {
            return Sample.disabled(processors());
        }
        long now = System.currentTimeMillis();
        Sample cached = sampleRef.get();
        if (cached != null && now - cached.atMs() < sampleTtlMs) {
            return cached;
        }
        Sample next = sampleNow(now);
        sampleRef.set(next);
        return next;
    }

    private Sample sampleNow(long now) {
        int processors = processors();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        Level cpuLevel = Level.NORMAL;
        if (load >= 0) {
            double perCpu = load / Math.max(1, processors);
            if (perCpu >= criticalLoadPerCpu) {
                cpuLevel = Level.CRITICAL;
            } else if (perCpu >= elevatedLoadPerCpu) {
                cpuLevel = Level.ELEVATED;
            }
        }
        Level level = max(cpuLevel, map(memoryPressure().level()));
        return new Sample(now, level, load < 0 ? 0D : load, processors);
    }

    private static int processors() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return Math.max(1, os.getAvailableProcessors());
    }

    private static Level map(MemoryLevel level) {
        return switch (level) {
            case NORMAL -> Level.NORMAL;
            case ELEVATED -> Level.ELEVATED;
            case CRITICAL -> Level.CRITICAL;
        };
    }

    private static Level max(Level left, Level right) {
        if (left.ordinal() >= right.ordinal()) {
            return left;
        }
        return right;
    }

    private record Sample(long atMs, Level level, double loadAverage, int processors) {
        static final Sample EMPTY = new Sample(0L, Level.NORMAL, 0D, 1);

        static Sample disabled(int processors) {
            return new Sample(System.currentTimeMillis(), Level.NORMAL, 0D, processors);
        }
    }
}
