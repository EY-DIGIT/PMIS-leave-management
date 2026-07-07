package com.example.leavemanagement.config;

import com.example.leavemanagement.client.UsersServiceClient;
import com.example.leavemanagement.security.TokenAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityFilterConfig {

    @Bean
    public FilterRegistrationBean<TokenAuthenticationFilter> tokenAuthenticationFilter(
            UsersServiceClient usersServiceClient) {
        FilterRegistrationBean<TokenAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TokenAuthenticationFilter(usersServiceClient));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
