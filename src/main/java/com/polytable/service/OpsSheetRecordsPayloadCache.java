package com.polytable.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中文备注：/records JSON 与 /records/payload 拆包时，nonce 一次取用 gzip 字节，避免同页查两次库。
 */
@Component
public class OpsSheetRecordsPayloadCache {

    private static final long TTL_MS = 90_000;

    private record Entry(byte[] bytes, long expiresAtMs) {
    }

    private final ConcurrentHashMap<String, Entry> byNonce = new ConcurrentHashMap<>();

    public String store(byte[] gzipPayload) {
        if (gzipPayload == null || gzipPayload.length == 0) {
            return "";
        }
        purgeExpired();
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        byNonce.put(nonce, new Entry(gzipPayload, System.currentTimeMillis() + TTL_MS));
        return nonce;
    }

    /** 中文备注：TTL 内可重复取同 nonce（重试/双请求），避免 take 一次失效导致空表。 */
    public byte[] get(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return null;
        }
        purgeExpired();
        Entry entry = byNonce.get(nonce.trim());
        if (entry == null || entry.expiresAtMs < System.currentTimeMillis()) {
            return null;
        }
        return entry.bytes;
    }

    /** @deprecated  prefer {@link #get(String)} */
    public byte[] take(String nonce) {
        return get(nonce);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        byNonce.entrySet().removeIf(e -> e.getValue().expiresAtMs < now);
    }
}
