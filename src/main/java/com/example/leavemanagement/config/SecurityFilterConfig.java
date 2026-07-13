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
     * Temporarily disabled (default off) — flip {@code auth.enabled=true} to re-enable the
     * introspect-token check on every /api/** request.
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
