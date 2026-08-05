package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Envelope of the projects service's {@code GET /api/v3/activities/{activityId}} response. The real
 * payload is wrapped in a {@code data} object (mirroring {@code ProjectApiResponse}); the activity's
 * dates and planned {@code resources} live inside it as an {@link ActivityDetailsResponse}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityApiResponse(ActivityDetailsResponse data) {}
