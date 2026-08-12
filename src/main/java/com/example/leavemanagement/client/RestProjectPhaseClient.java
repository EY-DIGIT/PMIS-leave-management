package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.ProjectPhasesResponse;
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
 * Fetches a project's phases from the real projects service: {@code GET
 * {projects-service.base-url}/api/v3/projects/{projectId}/phases}. The {@code isResourceBased} phases
 * define the resource-based billing period that gates cost. This is the default (real projects
 * service); set {@code project-phase-service.mock=true} to fall back to {@link StubProjectPhaseClient}
 * (e.g. for offline dev). Independent of the activity/leave-policy mock flags.
 */
@Component
@ConditionalOnProperty(name = "project-phase-service.mock", havingValue = "false", matchIfMissing = true)
public class RestProjectPhaseClient implements ProjectPhaseClient {

    private static final Logger log = LoggerFactory.getLogger(RestProjectPhaseClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public RestProjectPhaseClient(
            RestClient.Builder restClientBuilder, @Value("${projects-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<ProjectPhasesResponse> getPhases(String projectId) {
        try {
            ProjectPhasesResponse response = restClient
                    .get()
                    .uri(baseUrl + "/api/v3/projects/{projectId}/phases", projectId)
                    .headers(this::propagateCallerToken)
                    .retrieve()
                    .body(ProjectPhasesResponse.class);

            log.debug("Project phases for projectId={}: {}", projectId, response);
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            log.warn("Projects service phases call failed for projectId={}: {}", projectId, e.getMessage(), e);
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
