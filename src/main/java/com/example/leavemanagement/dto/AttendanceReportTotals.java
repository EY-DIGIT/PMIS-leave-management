package com.example.leavemanagement.dto;

/**
 * Aggregate totals across all resources in an attendance report period.
 * Numeric day fields are summed across resources; {@code avgAttendancePercentage} is the mean.
 * {@code workingDays} and {@code holidayDays} are identical for every resource on the same
 * project/calendar, so they are taken from the first resource rather than summed.
 */
public record AttendanceReportTotals(
        int resourceCount,
        int workingDays,
        double presentDays,
        int halfDays,
        int leaveDays,
        int absentDays,
        int wfhDays,
        double leaveTaken,
        double paidLeaveDays,
        double unpaidLeaveDays,
        double avgAttendancePercentage) {}
