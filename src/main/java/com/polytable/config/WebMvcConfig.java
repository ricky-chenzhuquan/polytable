package com.polytable.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 中文备注：ops 静态页迭代频繁，禁止浏览器长期缓存 index.html / sheet-canvas.js。
        registry.addResourceHandler("/ops/**")
                .addResourceLocations("classpath:/static/ops/")
                .setCacheControl(CacheControl.noStore().mustRevalidate());
    }
}
