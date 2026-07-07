package com.example.leavemanagement.client;

/** Caller identity extracted from an active introspect response. */
public record IntrospectResult(String userId, String email, String username) {}
