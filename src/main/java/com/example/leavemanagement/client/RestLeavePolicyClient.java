package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.LeavePolicyResponse;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Set {@code leave-policy-service.mock=false} once the real leave-policy service is available. */
@Component
@ConditionalOnProperty(name = "leave-policy-service.mock", havingValue = "false")
public class RestLeavePolicyClient implements LeavePolicyClient {

    private final RestClient restClient;
    private final String baseUrl;

    public RestLeavePolicyClient(
            RestClient.Builder restClientBuilder, @Value("${leave-policy-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<LeavePolicyResponse> getLeavePolicy(String projectId) {
        try {
            LeavePolicyResponse response = restClient
                    .get()
                    .uri(baseUrl + "/leave-policy/{projectId}", projectId)
                    .retrieve()
                    .body(LeavePolicyResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }
}
