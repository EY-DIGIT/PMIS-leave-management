package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.LeavePolicyResponse;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local leave-policy client: returns the same sample policy for every project, with no external
 * API call. Only active when {@code leave-policy-service.mock=true} is set explicitly (e.g. for
 * offline dev/tests) — {@link RestLeavePolicyClient} (the real projects service) is the default.
 */
@Component
@ConditionalOnProperty(name = "leave-policy-service.mock", havingValue = "true")
public class StubLeavePolicyClient implements LeavePolicyClient {

    private static final LeavePolicyResponse SAMPLE_POLICY = new LeavePolicyResponse(
            4, 8, "HALF_DAY", "FULL_DAY", true, true, 2, "MONTHLY", true, false, true, true);

    @Override
    public Optional<LeavePolicyResponse> getLeavePolicy(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SAMPLE_POLICY);
    }
}
