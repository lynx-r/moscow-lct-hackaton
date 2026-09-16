package com.ultra.dit.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@EnableWebSecurity
@Configuration
public class AppSecurityConfig extends WebSecurityConfigurerAdapter {

    private final CorsProperties corsProperties;

    public AppSecurityConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                // В старых версиях вместо securityMatcher используется antMatcher
                .antMatcher("/**")
                // Настройка CORS без лямбда-выражений
                .cors().configurationSource(corsConfigurationSource())
                .and()
                // Отключение CSRF в старом стиле
                .csrf().disable();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        return request -> {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(corsProperties.getAllowedOrigins());
            config.setAllowedMethods(corsProperties.getAllowedMethods());
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(corsProperties.isAllowCredentials());
            return config;
        };
    }

}
