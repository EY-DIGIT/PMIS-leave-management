package com.example.leavemanagement.client;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestUsersServiceClient implements UsersServiceClient {

    private final RestClient restClient;
    private final String introspectUrl;

    public RestUsersServiceClient(
            RestClient.Builder restClientBuilder, @Value("${users-service.introspect-url}") String introspectUrl) {
        this.restClient = restClientBuilder.build();
        this.introspectUrl = introspectUrl;
    }

    @Override
    public Optional<IntrospectResult> introspect(String accessToken) {
        try {
            IntrospectApiResponse response = restClient
                    .post()
                    .uri(introspectUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("access_token", accessToken))
                    .retrieve()
                    .body(IntrospectApiResponse.class);

            if (response == null || response.data() == null || !response.data().active()) {
                return Optional.empty();
            }
            IntrospectApiResponse.IntrospectData data = response.data();
            return Optional.of(new IntrospectResult(data.userId(), data.email(), data.username()));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }
}
