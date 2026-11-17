package com.polytable.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 中文备注：协作表变更推送（飞书式「长连接推送代替轮询」）。
 * 前端 EventSource 订阅 /sheets/{tableId}/sync/stream；行写入经
 * {@link OpsSheetMetaCacheService#evictRows} 触发 broadcastDirty，
 * 推送的是「脏了」信号而非数据——客户端收到后走既有 metaOnly（1ms 缓存）+ sinceMs 增量拉取。
 * 广播在独立单线程池异步发送，不阻塞写入事务；单连接失败直接摘除，由 EventSource 自动重连。
 */
@Component
public class OpsSheetSyncPushService {

    private final Map<String, List<SseEmitter>> emittersByTable = new ConcurrentHashMap<>();
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ops-sheet-sync-push");
        t.setDaemon(true);
        return t;
    });
    // 中文备注：SSE 心跳——夜里没人编辑时连接上零字节流动，会被 nginx(默认 60s 读超时)掐成 504。
    // 25 秒一次 comment 帧（EventSource 不触发 message 事件）保活 + 顺带摘除断开的订阅者。
    private final java.util.concurrent.ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ops-sheet-sync-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public OpsSheetSyncPushService() {
        heartbeat.scheduleWithFixedDelay(this::sendHeartbeats, 25, 25, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void sendHeartbeats() {
        for (Map.Entry<String, List<SseEmitter>> entry : emittersByTable.entrySet()) {
            List<SseEmitter> list = entry.getValue();
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (Exception e) {
                    list.remove(emitter);
                }
            }
        }
    }

    public SseEmitter register(String tableId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emittersByTable.computeIfAbsent(tableId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /** 行写入后调用；推送 dirty 信号（不含数据）。rowHint 可空=批量操作；operator 可空。 */
    public void broadcastDirty(String tableId, String rowHint, String operator) {
        List<SseEmitter> list = emittersByTable.get(tableId);
        if (list == null || list.isEmpty()) {
            return;
        }
        String payload = buildPayload(rowHint, operator);
        broadcaster.execute(() -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("sync").data(payload));
                } catch (Exception e) {
                    list.remove(emitter);
                }
            }
        });
    }

    /** 中文备注：W1 单元格级推送——payload 携带变更单元格值数组与单调 seq;客户端按行直接应用,缺口回退 delta。
     *  event 带 id(seq)——EventSource 断线重连自动回传 Last-Event-ID,服务端按水位回放缺口(断线握手)。 */
    public void broadcastOps(String tableId, long seq, String opsJson, String operator) {
        List<SseEmitter> list = emittersByTable.get(tableId);
        if (list == null || list.isEmpty() || opsJson == null || "[]".equals(opsJson)) {
            return;
        }
        String payload = "{\"t\":\"ops\",\"seq\":" + seq + ",\"by\":\"" + (operator == null ? "" : operator.replace("\"", "")) + "\",\"ops\":" + opsJson + "}";
        broadcaster.execute(() -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().id(String.valueOf(seq)).name("sync").data(payload));
                } catch (Exception e) {
                    list.remove(emitter);
                }
            }
        });
    }

    /** 中文备注：断线握手——向单个重连订阅者发送一帧(带 id),用于按 Last-Event-ID 回放缺口。 */
    public void sendTo(SseEmitter emitter, String tableId, long seq, String payload) {
        try {
            emitter.send(SseEmitter.event().id(String.valueOf(seq)).name("sync").data(payload));
        } catch (Exception e) {
            List<SseEmitter> list = emittersByTable.get(tableId);
            if (list != null) list.remove(emitter);
        }
    }

    private static String buildPayload(String rowHint, String operator) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"t\":\"dirty\"");
        if (rowHint != null && !rowHint.isBlank()) {
            sb.append(",\"row\":\"").append(rowHint.replace("\"", "").trim()).append('"');
        }
        if (operator != null && !operator.isBlank()) {
            sb.append(",\"by\":\"").append(operator.replace("\"", "").trim()).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    public int subscriberCount(String tableId) {
        List<SseEmitter> list = emittersByTable.get(tableId);
        return list == null ? 0 : list.size();
    }
}
