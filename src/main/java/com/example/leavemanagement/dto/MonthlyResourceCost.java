package com.example.leavemanagement.dto;

/**
 * One resource's cost for one calendar month: {@code cost = monthlyRate * (effectivePresentDays /
 * workingDays)}, where {@code effectivePresentDays = min(workingDays, presentDays +
 * relaxationDaysApplied)}. {@code relaxationDaysApplied} is this month's share of the quarter's
 * {@link com.example.leavemanagement.entity.LeaveRelaxation#getRelaxationDays() relaxationDays}
 * (UIDAI's final relaxation decision, which never changes paid leave but forgives some unpaid
 * leave) — applied via sequential fill, oldest month in the quarter first, capping at each
 * month's own absent-day count before spilling into the next month. {@code monthlyRate} is
 * looked up from the resource's active {@link
 * com.example.leavemanagement.entity.ProjectResource#getRateCardByYear() rate card} using its
 * current {@link com.example.leavemanagement.entity.ProjectResource#getRateYear() rateYear}
 * (e.g. "Year-3"). {@code monthlyRate}/{@code cost} are 0 when the resource has no active
 * assignment, no rateYear set, or no rate configured for that year.
 */
public record MonthlyResourceCost(
        String attendanceId,
        String employeeName,
        String projectId,
        String rateYear,
        String period,
        int workingDays,
        int presentDays,
        int relaxationDaysApplied,
        double attendancePercentage,
        double monthlyRate,
        double cost) {}
