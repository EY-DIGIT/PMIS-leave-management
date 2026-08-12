package com.example.leavemanagement.service;

import com.example.leavemanagement.client.ProjectPhaseClient;
import com.example.leavemanagement.dto.ProjectPhase;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves a project's <em>resource-based period</em> from its phases. Only {@code isResourceBased}
 * phases are billable per resource; their (possibly overlapping or contiguous) date ranges merge into
 * one or more spans, and the overall {@code [min start, max end]} of those spans is the billable
 * window. Cost outside this window is not chargeable.
 *
 * <p>When the projects service returns no phases (call failed, mock off, or no resource-based phases),
 * {@link #resolve(String)} returns {@link Optional#empty()} — callers treat a missing window as "no
 * gating" so cost is unchanged (backward-compatible).
 */
@Service
public class ResourceBasedPeriodService {

    private static final Logger log = LoggerFactory.getLogger(ResourceBasedPeriodService.class);

    private final ProjectPhaseClient phaseClient;

    public ResourceBasedPeriodService(ProjectPhaseClient phaseClient) {
        this.phaseClient = phaseClient;
    }

    /**
     * The merged resource-based window for the project, or empty when it cannot be determined.
     *
     * @param from inclusive start of the billable window (earliest resource-based phase start)
     * @param to   inclusive end of the billable window (latest resource-based phase end)
     */
    public record ResourceBasedPeriod(LocalDate from, LocalDate to) {

        /** Clamps a day into the window; returns true when {@code day} is billable (inside the window). */
        public boolean contains(LocalDate day) {
            return day != null && !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /** Resolves the merged resource-based window for a project. Empty ⇒ no gating (cost unchanged). */
    public Optional<ResourceBasedPeriod> resolve(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        List<ProjectPhase> resourceBased = phaseClient.getPhases(projectId)
                .map(r -> r.phases())
                .orElse(List.of())
                .stream()
                .filter(ProjectPhase::resourceBased)
                .filter(p -> p.startDate() != null && p.endDate() != null)
                .sorted(Comparator.comparing(ProjectPhase::startDate))
                .toList();

        if (resourceBased.isEmpty()) {
            return Optional.empty();
        }

        // The merged spans always cover [min start, max end]; the billable window is that overall span.
        LocalDate min = resourceBased.get(0).startDate();
        LocalDate max = resourceBased.get(0).endDate();
        for (ProjectPhase p : resourceBased) {
            if (p.startDate().isBefore(min)) min = p.startDate();
            if (p.endDate().isAfter(max)) max = p.endDate();
        }
        log.debug("Resource-based period for projectId={}: {} → {}", projectId, min, max);
        return Optional.of(new ResourceBasedPeriod(min, max));
    }
}
