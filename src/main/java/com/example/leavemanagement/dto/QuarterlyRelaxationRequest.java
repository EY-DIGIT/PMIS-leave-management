package com.example.leavemanagement.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Records UIDAI's final leave-relaxation decision for one resource's quarter — the
 * request/discussion happens entirely outside the system; this is just the outcome.
 *
 * @param relaxationDays converted from unpaid leave into relaxation leave; must be between 0 and
 *     the quarter's original unpaid leave days (inclusive)
 */
public record QuarterlyRelaxationRequest(
        @NotBlank(message = "resourceId is required") String resourceId,
        @NotBlank(message = "projectId is required") String projectId,
        int year,
        int quarter,
        double relaxationDays,
        String remarks) {}
