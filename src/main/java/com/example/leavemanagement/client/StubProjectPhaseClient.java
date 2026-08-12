package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.ProjectPhase;
import com.example.leavemanagement.dto.ProjectPhasesResponse;
import com.example.leavemanagement.dto.ProjectPhasesResponse.ProjectPhasesData;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local project-phases client: returns the same sample phase list for every projectId, with no
 * external API call. Only active when {@code project-phase-service.mock=true} is set explicitly (e.g.
 * for offline dev/tests) — {@link RestProjectPhaseClient} (the real projects service) is the default.
 *
 * <p>Sample mirrors the real projects payload: phases "1" and "D11" are not resource-based; the two
 * resource-based phases ("2" and "3") merge into the resource-based period {@code [10-May-2027,
 * 10-May-2032]}.
 */
@Component
@ConditionalOnProperty(name = "project-phase-service.mock", havingValue = "true")
public class StubProjectPhaseClient implements ProjectPhaseClient {

    private static final ProjectPhasesResponse SAMPLE = new ProjectPhasesResponse(new ProjectPhasesData(List.of(
            new ProjectPhase("1", LocalDate.of(2025, 11, 10), LocalDate.of(2027, 5, 10), false, false),
            new ProjectPhase("D11", LocalDate.of(2025, 11, 10), LocalDate.of(2032, 5, 10), false, false),
            new ProjectPhase("2", LocalDate.of(2027, 5, 10), LocalDate.of(2028, 5, 10), true, false),
            new ProjectPhase("3", LocalDate.of(2027, 5, 10), LocalDate.of(2032, 5, 10), true, false))));

    @Override
    public Optional<ProjectPhasesResponse> getPhases(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SAMPLE);
    }
}
