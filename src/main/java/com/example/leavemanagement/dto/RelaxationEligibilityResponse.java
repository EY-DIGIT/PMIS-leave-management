package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Shows which of a resource's unpaid leave dates are still eligible for relaxation approval.
 *
 * @param unpaidLeaveDates all unpaid leave dates from the quarterly settlement
 * @param approvedRelaxationDates dates already approved for relaxation (cannot be re-selected)
 * @param eligibleDates dates still available to be submitted for relaxation
 * @param approvedRelaxationCost total relaxation cost already approved this quarter
 */
public record RelaxationEligibilityResponse(
        String attendanceId,
        String projectId,
        int year,
        int quarter,
        List<LocalDate> unpaidLeaveDates,
        List<LocalDate> approvedRelaxationDates,
        List<LocalDate> eligibleDates,
        double approvedRelaxationCost) {}
