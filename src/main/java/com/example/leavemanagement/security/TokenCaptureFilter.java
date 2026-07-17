package com.example.leavemanagement.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Always-on filter that captures the caller's raw bearer token into {@link CurrentUserContext} so
 * it can be propagated to downstream services (e.g. the projects service) — independent of whether
 * introspection-based authentication ({@code auth.enabled}) is turned on. It performs no
 * validation: token verification remains {@link TokenAuthenticationFilter}'s job when enabled.
 */
public class TokenCaptureFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean captured = false;
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.isBlank()) {
                CurrentUserContext.setToken(token);
                captured = true;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (captured) {
                CurrentUserContext.clear();
            }
        }
    }
}
