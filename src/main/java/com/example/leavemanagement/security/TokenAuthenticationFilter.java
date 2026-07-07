package com.example.leavemanagement.security;

import com.example.leavemanagement.client.IntrospectResult;
import com.example.leavemanagement.client.UsersServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates every {@code /api/**} request's bearer token against the users service's introspect
 * API before it reaches a controller, and makes the caller's identity available via
 * {@link CurrentUserContext} for the duration of the request.
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UsersServiceClient usersServiceClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TokenAuthenticationFilter(UsersServiceClient usersServiceClient) {
        this.usersServiceClient = usersServiceClient;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            unauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        Optional<IntrospectResult> result = usersServiceClient.introspect(token);
        if (result.isEmpty()) {
            unauthorized(response, "Invalid or expired token");
            return;
        }

        IntrospectResult user = result.get();
        CurrentUserContext.set(new CurrentUser(user.userId(), user.email(), user.username()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            CurrentUserContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        body.put("message", message);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
