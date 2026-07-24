package com.example.leavemanagement.dto;

/**
 * One resource's attendance totals for a period (month/quarter/year). {@code workingDays} =
 * calendar days in the period minus weekends minus public holidays. {@code attendancePercentage}
 * = presentDays / workingDays * 100 (0 when workingDays is 0).
 *
 * @param attendanceId res_id
 * @param employeeName resource's name
 * @param designation resource's role on the project (from {@code ProjectResource.role}; null if
 *     the resource has no active assignment)
 * @param projectId the project this summary is scoped to (may be null if the resource has no
 *     assignment)
 * @param milestoneId milestone from the most-recent uploaded row in this period (null if no data)
 * @param activityId activity from the most-recent uploaded row in this period (null if no data)
 * @param period human-readable period label, e.g. "July 2026", "Q3 2026", "2026"
 * @param presentDays effective days worked: P-status days + halfDays×0.5 (worked portion of
 *     half-days). Formula: workingDays − (absentDays + halfDays×0.5).
 * @param leaveTaken total effective leave consumed: {@code absentDays + halfDays×0.5}. This is
 *     the raw absence load before the paid/unpaid split — it equals {@code paidLeaveDays +
 *     unpaidLeaveDays}.
 * @param paidLeaveDays effective absences within the period's paid-leave entitlement:
 *     MIN(absentDays + halfDays×0.5, leaveLimit). Each half-day consumes 0.5 leave days.
 *     Zero when no leave policy is found.
 * @param unpaidLeaveDays effective absences beyond the entitlement:
 *     MAX(0, absentDays + halfDays×0.5 − leaveLimit).
 */
public record AttendanceReportSummary(
        String attendanceId,
        String employeeName,
        String designation,
        String projectId,
        String milestoneId,
        String activityId,
        String period,
        int workingDays,
        double presentDays,
        int halfDays,
        int leaveDays,
        int absentDays,
        int weekOffDays,
        int holidayDays,
        int wfhDays,
        double attendancePercentage,
        double leaveTaken,
        double paidLeaveDays,
        double unpaidLeaveDays) {}
