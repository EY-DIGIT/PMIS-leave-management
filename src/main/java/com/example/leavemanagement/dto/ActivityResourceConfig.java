package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One planned-resource line of an activity, as returned by the projects service's
 * {@code GET /projects/api/v3/activities/{activityId}} {@code resources} array. {@code quantity} is
 * how many resources of {@code designation} are planned; {@code duration} is the planned span in
 * months; {@code monthlyRate} is the per-month cost used for all activity-scoped calculations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityResourceConfig(
        String designation,
        Integer quantity,
        Double duration,
        Double monthlyRate) {}
