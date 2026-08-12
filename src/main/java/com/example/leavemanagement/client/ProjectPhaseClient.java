package com.example.leavemanagement.client;

import com.example.leavemanagement.dto.ProjectPhasesResponse;
import java.util.Optional;

/** Fetches a project's phases from the external projects service. */
public interface ProjectPhaseClient {

    /** Empty when the project has no phases or the projects service call fails. */
    Optional<ProjectPhasesResponse> getPhases(String projectId);
}
