package com.example.leavemanagement.security;

/** The caller identity resolved from the users-service introspect response for the current request. */
public record CurrentUser(String userId, String email, String username) {}
