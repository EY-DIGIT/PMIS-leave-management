package com.example.leavemanagement.dto;

/**
 * One resource's attendance totals for a period (month/quarter/year). {@code workingDays} =
 * calendar days in the period minus weekends minus public holidays. {@code attendancePercentage}
 * = presentDays / workingDays * 100 (0 when workingDays is 0).
 *
 * @param attendanceId res_id
 * @param employeeName resource's name
 * @param projectId the project this summary is scoped to (may be null if the resource has no
 *     assignment)
 * @param period human-readable period label, e.g. "July 2026", "Q3 2026", "2026"
 * @param paidLeaveDays full absent days within the period's paid-leave entitlement:
 *     MIN(absentDays, leaveLimit). Half-days are partial work days and do not consume leave quota.
 *     Zero when no leave policy is found.
 * @param unpaidLeaveDays absent days beyond the entitlement: MAX(0, absentDays - leaveLimit).
 */
public record AttendanceReportSummary(
        String attendanceId,
        String employeeName,
        String projectId,
        String period,
        int workingDays,
        int presentDays,
        int halfDays,
        int leaveDays,
        int absentDays,
        int weekOffDays,
        int holidayDays,
        int wfhDays,
        double attendancePercentage,
        double paidLeaveDays,
        double unpaidLeaveDays) {}
