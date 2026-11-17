package com.polytable.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 中文备注：协作表读路径元数据缓存（「表级 revision 计数器」的轻量实现）。
 * 轮询/首屏每次都要 contentSyncToken（COUNT+SUM+MAX）与 countActive（COUNT），
 * 大表上是两次全表扫描；100 人在线时轮询会把 MySQL CPU 打满（112 实测 100 用户 p50 2.8s）。
 * 这里按 tableId 缓存：水位 1s、总数 1s、字段 2s；行写入时主动失效，读侧最多容忍对应 TTL 的陈旧。
 * 协作语义：5s 轮询场景下 1s 陈旧不可感知；写路径 evict 保证本端保存后下一次轮询立刻可见。
 */
@Component
public class OpsSheetMetaCacheService {

    private static final long SYNC_TOKEN_TTL_MS = 2_000;
    private static final long COUNT_TTL_MS = 2_000;
    private static final long FIELDS_TTL_MS = 2_000;
    private static final long META_PAYLOAD_TTL_MS = 5_000;

    private record Entry(Object value, long expiresAtMs) {
    }

    private final ConcurrentHashMap<String, Entry> syncTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> fields = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> fieldMetas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> metaPayloads = new ConcurrentHashMap<>();

    /** 表内容水位（cachedCount:verSum:maxTs），2s TTL；写路径会 evict。 */
    public String syncToken(String tableId, Supplier<String> loader) {
        return cached(syncTokens, tableId, SYNC_TOKEN_TTL_MS, loader);
    }

    /** 可见行总数，2s TTL；写路径会 evict。 */
    public int count(String tableId, Supplier<Integer> loader) {
        return (Integer) cachedObj(counts, tableId, COUNT_TTL_MS, loader);
    }

    /** 活跃字段列表（调用方视为只读），2s TTL。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fields(String tableId, Supplier<List<Map<String, Object>>> loader) {
        return (List<Map<String, Object>>) cachedObj(fields, tableId, FIELDS_TTL_MS, loader);
    }

    /** 转换后的字段 metas（调用方视为只读），2s TTL；省去每轮询重建 72 个 map。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fieldMetas(String tableId, Supplier<List<Map<String, Object>>> loader) {
        return (List<Map<String, Object>>) cachedObj(fieldMetas, tableId, FIELDS_TTL_MS, loader);
    }

    /**
     * metaOnly 轮询整响应缓存，5s TTL + 写路径 evict。
     * 100 人 5s 轮询同一张表 = 每秒 20 次请求塌缩成 ~0.2 次真实计算；
     * key 已含 canManage（角色差异）与协议前缀，命中共享同一只读 map。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> metaPayload(String cacheKey, Supplier<Map<String, Object>> loader) {
        return (Map<String, Object>) cachedObj(metaPayloads, cacheKey, META_PAYLOAD_TTL_MS, loader);
    }

    private final OpsSheetSyncPushService syncPushService;

    public OpsSheetMetaCacheService(OpsSheetSyncPushService syncPushService) {
        this.syncPushService = syncPushService;
    }

    /** 行写入后失效水位/总数/metaOnly 响应，并向 SSE 订阅者广播 dirty 信号（rowHint 可空=批量操作）。 */
    public void evictRows(String tableId, String rowHint) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        syncTokens.remove(tableId);
        counts.remove(tableId);
        String prefix = "sheet|" + tableId + "|";
        metaPayloads.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        String hint = rowHint == null || rowHint.isBlank() ? null : rowHint.trim();
        syncPushService.broadcastDirty(tableId, hint, null);
    }

    /** 行写入后失效（兼容单参调用）。 */
    public void evictRows(String tableId) {
        evictRows(tableId, null);
    }

    /** 中文备注：W1——行写入后失效缓存并广播单元格级 op 帧(seq+变更值数组);opsJson 为空回落 dirty 信号。 */
    public void evictRowOps(String tableId, String rowHint, String operator, long seq, String opsJson) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        syncTokens.remove(tableId);
        counts.remove(tableId);
        String prefix = "sheet|" + tableId + "|";
        metaPayloads.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        if (opsJson == null || "[]".equals(opsJson)) {
            syncPushService.broadcastDirty(tableId, rowHint, operator);
            return;
        }
        syncPushService.broadcastOps(tableId, seq, opsJson, operator);
    }

    /** 行写入（带操作者）；SSE payload 带 rowId 与操作者，客户端可做行级提示。 */
    public void evictRowBy(String tableId, String rowHint, String operator) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        syncTokens.remove(tableId);
        counts.remove(tableId);
        String prefix = "sheet|" + tableId + "|";
        metaPayloads.keySet().removeIf(k -> k != null && k.startsWith(prefix));
        syncPushService.broadcastDirty(tableId, rowHint, operator);
    }

    public void evictFields(String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return;
        }
        fields.remove(tableId);
        fieldMetas.remove(tableId);
        String prefix = "sheet|" + tableId + "|";
        metaPayloads.keySet().removeIf(k -> k != null && k.startsWith(prefix));
    }

    private static String cached(ConcurrentHashMap<String, Entry> map, String key,
                                 long ttlMs, Supplier<String> loader) {
        return (String) cachedObj(map, key, ttlMs, loader);
    }

    private static Object cachedObj(ConcurrentHashMap<String, Entry> map, String key,
                                    long ttlMs, Supplier<?> loader) {
        long now = System.currentTimeMillis();
        Entry hit = map.get(key);
        if (hit != null && hit.expiresAtMs > now) {
            return hit.value;
        }
        Object value = loader.get();
        map.put(key, new Entry(value, now + ttlMs));
        return value;
    }
}
