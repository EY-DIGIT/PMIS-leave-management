package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.ActivityApiResponse;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.security.CurrentUserContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches an activity's planned configuration from the real projects service: {@code GET
 * {projects-service.base-url}/api/v3/activities/{activityId}}. The response's {@code startDate},
 * {@code endDate} and {@code resources} array drive attendance validation, leave, and cost.
 * Set {@code activity-service.mock=true} to fall back to {@link StubActivityDetailsClient}
 * (e.g. for offline dev).
 */
@Component
@ConditionalOnProperty(name = "activity-service.mock", havingValue = "false", matchIfMissing = true)
public class RestActivityDetailsClient implements ActivityDetailsClient {

    private static final Logger log = LoggerFactory.getLogger(RestActivityDetailsClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public RestActivityDetailsClient(
            RestClient.Builder restClientBuilder, @Value("${projects-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<ActivityDetailsResponse> getActivityDetails(String activityId) {
        try {
            ActivityApiResponse response = restClient
                    .get()
                    .uri(baseUrl + "/api/v3/activities/{activityId}", activityId)
                    .headers(this::propagateCallerToken)
                    .retrieve()
                    .body(ActivityApiResponse.class);

            Optional<ActivityDetailsResponse> activity = Optional.ofNullable(response)
                    .map(ActivityApiResponse::data);
            log.debug("Activity config for activityId={}: {}", activityId, activity.orElse(null));
            return activity;
        } catch (RestClientException e) {
            log.warn("Projects service activity call failed for activityId={}: {}", activityId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private void propagateCallerToken(HttpHeaders headers) {
        String token = CurrentUserContext.getToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }
}
