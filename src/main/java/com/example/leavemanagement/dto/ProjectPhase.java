package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDate;

/**
 * One phase of a project as returned by the projects service
 * ({@code GET /api/v3/projects/{projectId}/phases} → {@code data.phases[]}). Only
 * {@code isResourceBased} phases are billable per-resource; their date ranges merge into the
 * project's resource-based period, which gates cost (days outside the merged window are not
 * chargeable) and anchors the rate-year windows.
 *
 * <p>Dates arrive as ISO datetimes with an offset (e.g. {@code 2027-05-10T00:00:00+05:30}); the
 * {@link LenientLocalDateDeserializer} keeps only the {@code yyyy-MM-dd} calendar-date prefix.
 *
 * @param phase            the phase label/number (e.g. "1", "D11", "2")
 * @param startDate        the phase's planned start (calendar date)
 * @param endDate          the phase's planned end (calendar date)
 * @param resourceBased    whether this phase is resource-based (billable per resource)
 * @param transactionBased whether this phase is transaction-based (informational)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectPhase(
        @JsonProperty("phase") String phase,
        @JsonProperty("startDate") @JsonDeserialize(using = LenientLocalDateDeserializer.class) LocalDate startDate,
        @JsonProperty("endDate") @JsonDeserialize(using = LenientLocalDateDeserializer.class) LocalDate endDate,
        @JsonProperty("isResourceBased") boolean resourceBased,
        @JsonProperty("isTransactionBased") boolean transactionBased) {
}
