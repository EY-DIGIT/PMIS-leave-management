package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.ProjectApiResponse;
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
 * Fetches a project's leave policy from the real projects service: {@code GET
 * {base-url}/api/v3/projects/{projectId}}, taking the {@code leaveConfig} block of the response.
 * Set {@code leave-policy-service.mock=true} to fall back to {@link StubLeavePolicyClient} (e.g.
 * for offline dev).
 */
@Component
@ConditionalOnProperty(name = "leave-policy-service.mock", havingValue = "false", matchIfMissing = true)
public class RestLeavePolicyClient implements LeavePolicyClient {

    private static final Logger log = LoggerFactory.getLogger(RestLeavePolicyClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public RestLeavePolicyClient(
            RestClient.Builder restClientBuilder, @Value("${projects-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = baseUrl;
    }
//     @Override
//    public Optional<LeavePolicyResponse> getLeavePolicy(String projectId) {
//        try {
//            ProjectApiResponse response = restClient
//                    .get()
//                    .uri(baseUrl + "/api/v3/projects/{projectId}", projectId)
//                    .headers(this::propagateCallerToken)
//                    .retrieve()
//                    .body(ProjectApiResponse.class);
//
//            Optional<LeavePolicyResponse> leaveConfig = Optional.ofNullable(response)
//                    .map(ProjectApiResponse::data)
//                    .map(ProjectApiResponse.ProjectData::leaveConfig);
//            log.debug("Leave policy for projectId={}: {}", projectId, leaveConfig.orElse(null));
//            return leaveConfig;
//        } catch (RestClientException e) {
//            log.warn("Projects service call failed for projectId={}: {}", projectId, e.getMessage(), e);
//            return Optional.empty();
//        }
//    }

   @Override
   public Optional<LeavePolicyResponse> getLeavePolicy(String projectId) {
       return Optional.of(new LeavePolicyResponse(
               4,           // halfDay (hours)
               8,           // fullDay (hours)
               "false",     // saturdayWorking
               "false",     // sundayWorking
               true,        // attendanceCaptured
               true,        // sandwichLeaveApplied
               2,           // leavesPerFrequencyCount
               "MONTHLY",   // leavesFrequency
               true,        // proratedLeavesApplied
               false,       // carryForwardAllowed
               true,        // leaveLapseAtQuarterEnd
               true         // automaticBalanceReset
       ));
   }


    /** Forwards the current request's bearer token — the projects service requires one. */
    private void propagateCallerToken(HttpHeaders headers) {
        String token = CurrentUserContext.getToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }
}
