package com.example.leavemanagement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for every {@code /api/**} endpoint. {@code cors.allowed-origins} defaults to {@code *}
 * (any origin) for easy local/dev use — tighten it to your real frontend origin(s) (comma
 * separated) before any shared or production deployment.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public WebCorsConfig(@Value("${cors.allowed-origins:*}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split("\\s*,\\s*"))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
