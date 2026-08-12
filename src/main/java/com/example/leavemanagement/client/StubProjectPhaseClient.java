package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.ProjectPhase;
import com.example.leavemanagement.dto.ProjectPhasesResponse;
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
 * <p>Sample: Phase 1 is not resource-based; the two resource-based phases (Phase 2 and Phase 3) merge
 * into the resource-based period {@code [10-May-2027, 10-May-2032]}.
 */
@Component
@ConditionalOnProperty(name = "project-phase-service.mock", havingValue = "true")
public class StubProjectPhaseClient implements ProjectPhaseClient {

    private static final ProjectPhasesResponse SAMPLE = new ProjectPhasesResponse(List.of(
            new ProjectPhase("Phase 1 - Planning",
                    LocalDate.of(2024, 1, 10), LocalDate.of(2026, 1, 9), false),
            new ProjectPhase("Phase 2 - Build",
                    LocalDate.of(2026, 1, 10), LocalDate.of(2027, 1, 10), true),
            new ProjectPhase("Phase 3 - Operate",
                    LocalDate.of(2026, 1, 10), LocalDate.of(2031, 11, 10), true)));

    @Override
    public Optional<ProjectPhasesResponse> getPhases(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(SAMPLE);
    }
}
