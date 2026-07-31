package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Shows which of a resource's unpaid leave dates are still eligible for relaxation approval.
 *
 * @param unpaidFullDayDates fully-absent dates that exceeded the paid-leave quota
 * @param unpaidHalfDayDates half-day dates whose 0.5 weight exceeded the remaining quota
 * @param sandwichDates weekend/holiday days sandwiched between unpaid full-absent days
 * @param allUnpaidDates combined sorted list of all three categories above
 * @param approvedRelaxationDates dates already approved for relaxation (cannot be re-selected)
 * @param eligibleDates dates still available to be submitted for relaxation
 * @param approvedRelaxationCost total relaxation cost already approved this quarter
 */
public record RelaxationEligibilityResponse(
        String attendanceId,
        String projectId,
        int year,
        int quarter,
        List<LocalDate> unpaidFullDayDates,
        List<LocalDate> unpaidHalfDayDates,
        List<LocalDate> sandwichDates,
        List<LocalDate> allUnpaidDates,
        List<LocalDate> approvedRelaxationDates,
        List<LocalDate> eligibleDates,
        double approvedRelaxationCost) {}
