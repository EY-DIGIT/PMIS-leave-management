package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDate;

/**
 * One phase of a project as returned by the projects service. Only {@code resourceBased} phases are
 * billable per-resource; their date ranges merge into the project's resource-based period, which gates
 * cost (days outside the merged window are not chargeable).
 *
 * @param name          the phase name (optional, for diagnostics)
 * @param startDate     the phase's planned start
 * @param endDate       the phase's planned end
 * @param resourceBased whether this phase is resource-based (billable per resource)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectPhase(
        @JsonProperty("name") String name,
        @JsonDeserialize(using = LenientLocalDateDeserializer.class) LocalDate startDate,
        @JsonDeserialize(using = LenientLocalDateDeserializer.class) LocalDate endDate,
        @JsonProperty("isResourceBased") boolean resourceBased) {
}
