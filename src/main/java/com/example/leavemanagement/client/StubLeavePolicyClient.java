package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.LeavePolicyResponse;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local leave-policy client: returns the same sample policy for every project, with no external
 * API call. Active by default (the real leave-policy service isn't available yet).
 *
 * <p>Once the real service is up, set {@code leave-policy-service.mock=false} to switch to
 * {@link RestLeavePolicyClient} — no other code changes needed.
 */
@Component
@ConditionalOnProperty(name = "leave-policy-service.mock", havingValue = "true", matchIfMissing = true)
public class StubLeavePolicyClient implements LeavePolicyClient {

    private static final LeavePolicyResponse SAMPLE_POLICY =
            new LeavePolicyResponse("MONTHLY", 4.0, 8.0, true);

    @Override
    public Optional<LeavePolicyResponse> getLeavePolicy(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SAMPLE_POLICY);
    }
}
