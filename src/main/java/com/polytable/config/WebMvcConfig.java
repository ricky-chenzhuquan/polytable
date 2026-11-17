package com.polytable.config;

import com.polytable.monitor.LocalMonitorAuthInterceptor;
import com.polytable.monitor.OperationLogInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final OperationLogInterceptor operationLogInterceptor;
    private final LocalMonitorAuthInterceptor localMonitorAuthInterceptor;

    public WebMvcConfig(OperationLogInterceptor operationLogInterceptor,
                        LocalMonitorAuthInterceptor localMonitorAuthInterceptor) {
        this.operationLogInterceptor = operationLogInterceptor;
        this.localMonitorAuthInterceptor = localMonitorAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localMonitorAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/monitor/**",
                        "/api/v1/users/me",
                        "/api/v1/menus/routes",
                        "/api/v1/auth/logout"
                );

        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/**", "/health");
        // 中文备注：协作工作台接口单独记录访问，便于排查谁在刷页。
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 中文备注：ops 静态页迭代频繁，禁止浏览器长期缓存 index.html / sheet-canvas.js。
        registry.addResourceHandler("/ops/**")
                .addResourceLocations("classpath:/static/ops/")
                .setCacheControl(CacheControl.noStore().mustRevalidate());
    }
}
