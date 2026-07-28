package com.example.leavemanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

/**
 * Records UIDAI's final leave-relaxation decision for one resource's quarter — the
 * request/discussion happens entirely outside the system; this is just the outcome.
 *
 * @param relaxationDates the specific unpaid leave dates to convert into relaxation leave; each
 *     date must be an existing unpaid leave date for this resource's quarter and must not have
 *     been previously approved. Per-day cost = {@code monthlyRate / calendarDaysInMonth}, so
 *     dates from different months are priced independently.
 */
public record QuarterlyRelaxationRequest(
        @NotBlank(message = "resourceId is required") String resourceId,
        @NotBlank(message = "projectId is required") String projectId,
        int year,
        int quarter,
        @NotEmpty(message = "At least one relaxation date must be selected") List<LocalDate> relaxationDates,
        String remarks) {}
