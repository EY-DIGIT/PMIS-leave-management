package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Envelope of the projects service's {@code GET /api/v3/projects/{projectId}/phases} response. The
 * real payload is wrapped in a {@code data} array (mirroring {@link ActivityApiResponse}); each entry
 * is a {@link ProjectPhase}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectPhasesResponse(List<ProjectPhase> data) {

    public List<ProjectPhase> phases() {
        return data == null ? List.of() : data;
    }
}
