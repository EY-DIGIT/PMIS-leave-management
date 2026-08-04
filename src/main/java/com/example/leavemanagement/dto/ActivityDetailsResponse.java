package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The projects service's activity configuration — the source of truth for attendance validation and
 * activity-scoped calculations. Deserialized directly from {@code GET
 * /projects/api/v3/activities/{activityId}}: the activity's own {@code startDate}/{@code endDate}
 * window plus the planned {@link ActivityResourceConfig} lines. The derived maps key each planned
 * attribute by designation for lookup during validation, leave, and costing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityDetailsResponse(
        String activityName,
        LocalDate startDate,
        LocalDate endDate,
        List<ActivityResourceConfig> resources) {

    public boolean hasResources() {
        return resources != null && !resources.isEmpty();
    }

    public Map<String, Integer> requiredByDesignation() {
        return resources == null ? Map.of() : resources.stream()
                .filter(r -> r.designation() != null && r.quantity() != null)
                .collect(Collectors.toMap(
                        ActivityResourceConfig::designation,
                        ActivityResourceConfig::quantity,
                        Integer::sum,
                        java.util.LinkedHashMap::new));
    }

    public Map<String, Double> durationByDesignation() {
        return resources == null ? Map.of() : resources.stream()
                .filter(r -> r.designation() != null && r.duration() != null)
                .collect(Collectors.toMap(
                        ActivityResourceConfig::designation,
                        ActivityResourceConfig::duration,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }

    public Map<String, Double> monthlyRateByDesignation() {
        return resources == null ? Map.of() : resources.stream()
                .filter(r -> r.designation() != null && r.monthlyRate() != null)
                .collect(Collectors.toMap(
                        ActivityResourceConfig::designation,
                        ActivityResourceConfig::monthlyRate,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }

    public int totalRequired() {
        return requiredByDesignation().values().stream().mapToInt(Integer::intValue).sum();
    }
}
