package com.polytable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PolyTable 启动入口。
 *
 * <p>定时调度默认开启（SCHEDULING_ENABLED=false 可整体关闭），
 * 多维表格本身的协作心跳/缓存刷新不依赖额外中间件。</p>
 */
@SpringBootApplication
public class PolyTableApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolyTableApplication.class, args);
    }

    @EnableScheduling
    @ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
