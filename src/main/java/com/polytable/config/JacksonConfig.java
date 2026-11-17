package com.polytable.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * 中文备注：前端偶发多传字段（如 create 里的 operator）时不要整请求 400。
     * 用 BuilderCustomizer，避免替换整个 ObjectMapper 丢掉 Boot 默认模块。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer opsJacksonCustomizer() {
        return builder -> builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
