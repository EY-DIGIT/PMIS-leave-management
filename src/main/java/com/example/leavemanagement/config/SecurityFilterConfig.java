package com.example.leavemanagement.config;

import com.example.leavemanagement.client.UsersServiceClient;
import com.example.leavemanagement.security.TokenAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityFilterConfig {

    /**
     * Enabled by default ({@code auth.enabled=true} in application.properties) — every /api/**
     * request must carry a valid bearer token, checked against the users-service introspect
     * endpoint. Flip to {@code false} to disable for local/dev testing.
     */
    @Bean
    public FilterRegistrationBean<TokenAuthenticationFilter> tokenAuthenticationFilter(
            UsersServiceClient usersServiceClient, @Value("${auth.enabled:false}") boolean authEnabled) {
        FilterRegistrationBean<TokenAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TokenAuthenticationFilter(usersServiceClient));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        registration.setEnabled(authEnabled);
        return registration;
    }
}
