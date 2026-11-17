package com.polytable.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 代投本机库专用 JdbcTemplate；双数据源开启时用 {@code agencyJdbcTemplate}，否则等同默认池。
 */
@Configuration
public class OpsAgencyLocalJdbcConfig {

    @Bean(name = "opsAgencyLocalJdbcTemplate")
    public JdbcTemplate opsAgencyLocalJdbcTemplate(
            DataSource dataSource,
            @Autowired(required = false) @Qualifier("agencyRawDataSource") DataSource agencyRawDataSource) {
        return new JdbcTemplate(agencyRawDataSource != null ? agencyRawDataSource : dataSource);
    }
}
