package com.polytable.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 中文备注：全局兜底异常处理 + 限流。
 * <p>
 * 2026-11-12 18:52 / 2026-11-13 13:56 两次事故：MySQL 卡住 → Tomcat 线程同时抛同样的
 * {@code CannotGetJdbcConnectionException}，每个异常打 30+ 行栈，2 分钟冲出 3400+ 行日志，
 * 把磁盘 IO 也拖垮，加速整机冻结。
 * <p>
 * 这里对漏网到 Spring 层的异常做同类限流：60 秒窗口内只打一次完整栈，其余记数；
 * 窗口结束后由下一次同类型异常补报抑制数量。业务 Controller 自行捕获的异常不受影响。
 */
@RestControllerAdvice
public class GlobalRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);
    private static final long SUPPRESS_WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Long> lastFullLogAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> suppressedCount = new ConcurrentHashMap<>();

    /** Spring 语义异常（404/405/参数校验等）原样透传，不吞状态码。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "code", String.valueOf(e.getStatusCode().value()),
                "msg", e.getReason() == null ? e.getStatusCode().toString() : e.getReason(),
                "data", Map.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Map<String, Object>> handleUnexpected(Exception e) {
        String key = e.getClass().getName();
        long now = System.currentTimeMillis();
        Long last = lastFullLogAt.get(key);
        if (last == null || now - last >= SUPPRESS_WINDOW_MS) {
            lastFullLogAt.put(key, now);
            AtomicLong pending = suppressedCount.remove(key);
            long extra = pending == null ? 0L : pending.get();
            log.error("未处理异常"
                    + (extra > 0 ? "（前 " + SUPPRESS_WINDOW_MS / 1000 + "s 内已抑制 " + extra + " 次相同异常）" : ""), e);
        } else {
            suppressedCount.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        }
        return ApiResult.fail("A0500", "服务内部错误，请稍后重试");
    }
}
