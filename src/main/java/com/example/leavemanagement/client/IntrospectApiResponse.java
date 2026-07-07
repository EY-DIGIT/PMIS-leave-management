package com.example.leavemanagement.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The users service's {@code /users/api/v3/users/introspect} response envelope. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IntrospectApiResponse(IntrospectData data, String message, String error, Integer status) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntrospectData(boolean active, String userId, String email, String username) {}
}
