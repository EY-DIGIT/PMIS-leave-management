package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * A resource merged with one project assignment (its current one for GET/search, or one of its
 * historical assignments for GET .../history). {@code projectId}/{@code designationType}/{@code
 * rateCardByYear}/{@code assignmentStartDate}/{@code assignmentEndDate} are null/empty and {@code
 * active} is false when the resource has no assignment (e.g. never assigned, or between projects).
 */
public record ResourceResponse(
        Long id,
        String resId,
        String name,
        String emailId,
        String location,
        String category,
        String categoryDetails,
        LocalDate dateOfJoining,
        LocalDate lastDate,
        String projectId,
        String designationType,
        Map<String, Double> rateCardByYear,
        String rateYear,
        boolean active,
        LocalDate assignmentStartDate,
        LocalDate assignmentEndDate,
        String replacedByResId) {}
