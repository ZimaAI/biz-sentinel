/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.commerce.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("commerce")
public class CommerceConfiguration {
    private final Environment env;
    public CommerceConfiguration(Environment env) { this.env=env; }
    private DataSource datasource(String role) {
        HikariConfig config=new HikariConfig();
        config.setJdbcUrl(env.getRequiredProperty("commerce.datasource."+role+".url"));
        config.setUsername(env.getRequiredProperty("commerce.datasource."+role+".username"));
        config.setPassword(env.getRequiredProperty("commerce.datasource."+role+".password"));
        config.setMaximumPoolSize(role.equals("query")?4:3);
        config.setConnectionTimeout(5000);
        config.setPoolName("commerce-"+role);
        return new HikariDataSource(config);
    }
    @Bean @Primary
    public DataSource commerceManagementDataSource() { return datasource("management"); }
    @Bean
    public DataSource commerceIngestionDataSource() {
        DataSource ds=datasource("ingestion");
        if(env.getProperty("commerce.initialize-schema",Boolean.class,false)) {
            new ResourceDatabasePopulator(new ClassPathResource("commerce/business-schema.sql")).execute(ds);
        }
        return ds;
    }
    @Bean
    public DataSource commerceQueryDataSource(@Qualifier("commerceIngestionDataSource") DataSource ingestion) { return datasource("query"); }
    @Bean
    public JdbcTemplate commerceIngestJdbc(@Qualifier("commerceIngestionDataSource") DataSource ds) { return new JdbcTemplate(ds); }
    @Bean
    public NamedParameterJdbcTemplate commerceQueryJdbc(@Qualifier("commerceQueryDataSource") DataSource ds) {
        JdbcTemplate jdbc=new JdbcTemplate(ds); jdbc.setQueryTimeout(3); jdbc.setMaxRows(10001);
        return new NamedParameterJdbcTemplate(jdbc);
    }
    @Bean
    public SecurityWebFilterChain commerceSecurityChain(ServerHttpSecurity http) {
        // CommerceSessionFilter implements same-origin session + CSRF checks for every entry point.
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable)
            .authorizeExchange(auth->auth.anyExchange().permitAll()).build();
    }
}
