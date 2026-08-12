package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Envelope of the projects service's {@code GET /api/v3/projects/{projectId}/phases} response. The
 * payload wraps a {@code data} object (mirroring {@link ActivityApiResponse}) whose {@code phases}
 * array holds the {@link ProjectPhase} entries:
 *
 * <pre>{@code { "data": { "_type": "ProjectPhases", "phases": [ … ] }, "status": 200 } }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectPhasesResponse(ProjectPhasesData data) {

    /** Flattens the envelope to the phase list, tolerating a missing {@code data}/{@code phases}. */
    public List<ProjectPhase> phases() {
        return (data == null || data.phases() == null) ? List.of() : data.phases();
    }

    /** The {@code data} object of the phases response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectPhasesData(List<ProjectPhase> phases) {}
}
