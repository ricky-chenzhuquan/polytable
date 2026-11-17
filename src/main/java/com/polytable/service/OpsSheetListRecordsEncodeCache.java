package com.polytable.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 中文备注：首屏 records gzip 块短缓存 + 编码并发上限，避免多人同窗口重复 Jackson/gzip 打满 CPU。
 */
@Component
public class OpsSheetListRecordsEncodeCache {

    private static final long TTL_MS = 90_000;
    /** 仅平滑编码尖峰，拿不到许可也照常编码，不挡同事首屏。 */
    private static final int MAX_ENCODE_PERMITS = 16;
    private static final long ENCODE_WAIT_MS = 2_000;

    public record CachedGzip(byte[] gzip, List<String> keys, int rowCount, String nextCursor, boolean hasMore,
                             String syncToken) {
        /** 兼容旧 3 参用法（列表路径的 nextCursor/hasMore 在 data 里已有）。 */
        public CachedGzip(byte[] gzip, List<String> keys, int rowCount) {
            this(gzip, keys, rowCount, null, false, null);
        }

        /** 兼容旧 5 参用法（无水位元数据）。 */
        public CachedGzip(byte[] gzip, List<String> keys, int rowCount, String nextCursor, boolean hasMore) {
            this(gzip, keys, rowCount, nextCursor, hasMore, null);
        }
    }

    private record Entry(CachedGzip chunk, long expiresAtMs) {
    }

    private final ConcurrentHashMap<String, Entry> byKey = new ConcurrentHashMap<>();
    private final Semaphore encodePermits = new Semaphore(MAX_ENCODE_PERMITS, true);
    /** 中文备注：stale-while-revalidate——写操作换新水位后旧块继续服务（客户端靠 SSE+delta 收敛），同时后台重编码一次。 */
    private final java.util.Set<String> rebuilding = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService rebuildPool = java.util.concurrent.Executors
            .newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ops-records-rebuild");
                t.setDaemon(true);
                return t;
            });

    public CachedGzip get(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return null;
        }
        purgeExpired();
        Entry entry = byKey.get(cacheKey.trim());
        if (entry == null || entry.expiresAtMs < System.currentTimeMillis()) {
            return null;
        }
        return entry.chunk();
    }

    public void put(String cacheKey, CachedGzip chunk) {
        if (cacheKey == null || cacheKey.isBlank() || chunk == null || chunk.gzip() == null || chunk.gzip().length == 0) {
            return;
        }
        purgeExpired();
        byKey.put(cacheKey.trim(), new Entry(chunk, System.currentTimeMillis() + TTL_MS));
    }

    /** 块是否与当前水位一致（一致=新鲜，直接服务；不一致=先旧后新）。 */
    public boolean isFresh(String cacheKey, String syncToken) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return true;
        }
        Entry entry = byKey.get(cacheKey.trim());
        if (entry == null) {
            return true;
        }
        String cachedToken = entry.chunk().syncToken();
        return cachedToken == null || cachedToken.equals(syncToken);
    }

    /** 后台重编码（同 key 去重，单线程串行防内存尖峰）；loader 返回 null 表示放弃本轮。 */
    public void scheduleRebuild(String cacheKey, java.util.function.Supplier<CachedGzip> loader) {
        if (cacheKey == null || cacheKey.isBlank() || loader == null) {
            return;
        }
        String key = cacheKey.trim();
        if (!rebuilding.add(key)) {
            return;
        }
        rebuildPool.execute(() -> {
            try {
                CachedGzip chunk = loader.get();
                if (chunk != null) {
                    put(key, chunk);
                }
            } catch (Exception ignored) {
                // 中文备注：后台重编码失败不打扰请求；TTL 内旧块继续，TTL 后由下一次请求触发。
            } finally {
                rebuilding.remove(key);
            }
        });
    }

    /** 未命中缓存时限制同时 gzip 编码数，排队而非无限并行。 */
    public boolean acquireEncodePermit() {
        try {
            return encodePermits.tryAcquire(ENCODE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void releaseEncodePermit(boolean held) {
        if (held) {
            encodePermits.release();
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        byKey.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expiresAtMs < now);
    }
}
