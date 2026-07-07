package com.example.leavemanagement.client;

import java.util.Optional;

/** Validates a bearer access token against the users service's introspect API. */
public interface UsersServiceClient {

    /** Empty when the token is missing, inactive/expired, or the users service call fails. */
    Optional<IntrospectResult> introspect(String accessToken);
}
