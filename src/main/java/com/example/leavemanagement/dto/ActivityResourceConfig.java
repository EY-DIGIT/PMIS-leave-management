package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDate;

/**
 * One planned-resource line of an activity, as returned by the projects service's
 * {@code GET /projects/api/v3/activities/{activityId}} {@code resources} array. {@code quantity} is
 * how many resources of {@code designation} are planned; {@code duration} is the planned span in
 * months; {@code monthlyRate} is the per-month cost used for all activity-scoped calculations;
 * {@code plannedDeploymentDate} is the planned start date for the designation;
 * {@code resourceClassification} is "planned" or "additional" — an "additional" line is an approved
 * additional requirement subject to UIDAI SLA 008 (a null/blank value is treated as "planned").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityResourceConfig(
        String designation,
        Integer quantity,
        Double duration,
        Double monthlyRate,
        @JsonDeserialize(using = LenientLocalDateDeserializer.class) LocalDate plannedDeploymentDate,
        String resourceClassification) {

    /** True when this line is an approved additional requirement (SLA 008). */
    public boolean isAdditional() {
        return resourceClassification != null && resourceClassification.trim().equalsIgnoreCase("additional");
    }

    /** Convenience: no classification (treated as "planned"). */
    public ActivityResourceConfig(
            String designation, Integer quantity, Double duration, Double monthlyRate, LocalDate plannedDeploymentDate) {
        this(designation, quantity, duration, monthlyRate, plannedDeploymentDate, null);
    }

    /** Convenience for callers/tests that don't supply a planned deployment date or classification. */
    public ActivityResourceConfig(String designation, Integer quantity, Double duration, Double monthlyRate) {
        this(designation, quantity, duration, monthlyRate, null, null);
    }
}
