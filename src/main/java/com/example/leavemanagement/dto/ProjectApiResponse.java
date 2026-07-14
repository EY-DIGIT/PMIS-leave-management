package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Envelope of the projects service's {@code GET /projects/api/v3/projects/{projectId}} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectApiResponse(ProjectData data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectData(String name, LeavePolicyResponse leaveConfig) {}
}
