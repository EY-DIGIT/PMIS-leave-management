package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Activity-scoped monthly resource-availability report for UIDAI SLA 007 (Minimum Resource
 * Availability). Filtered by project + activity, it returns — per resource — a calendar-month
 * breakdown of the business days attended and total working hours logged (from the activity's
 * biometric attendance), each month carrying its own SLA severity level, plus the cumulative totals
 * across the activity.
 *
 * @param projectId         the project the report is scoped to
 * @param activityId        the activity the report is scoped to
 * @param activityName      the activity's name (from PMIS)
 * @param period            the activity window label
 * @param activityStartDate the activity start date
 * @param activityEndDate   the activity end date
 * @param resourceCount     number of resources in the report
 * @param resources         per-resource availability with a monthly breakup
 */
public record ActivityAvailabilityReport(
        String projectId,
        String activityId,
        String activityName,
        String period,
        LocalDate activityStartDate,
        LocalDate activityEndDate,
        int resourceCount,
        List<ResourceAvailability> resources) {

    /**
     * One resource's availability across the activity, with a per-month breakdown.
     *
     * @param attendanceId      the resource's attendance id (res_id)
     * @param employeeName      the resource's name
     * @param designation       the resource's role/designation
     * @param totalBusinessDays business days attended across all captured months
     * @param totalPresentDays  weighted present days across all captured months
     * @param totalWorkingHours total working hours logged across all captured months
     * @param monthlyBreakup    one entry per calendar month that has attendance for this activity
     */
    public record ResourceAvailability(
            String attendanceId,
            String employeeName,
            String designation,
            int totalBusinessDays,
            double totalPresentDays,
            double totalWorkingHours,
            List<MonthlyAvailability> monthlyBreakup) {}

    /**
     * One calendar month's availability for a resource within the activity.
     *
     * @param year              the month's year
     * @param month             the month (1-12)
     * @param period            human-readable month label (e.g. "February 2026")
     * @param fromDate          first captured day in the month (clamped to activity/assignment window)
     * @param toDate            last captured day in the month (clamped to the uploaded window)
     * @param businessDays      days attended in the month (Present + Half-Day + WFH)
     * @param presentDays       weighted present days (Present + 0.5 × Half-Day)
     * @param totalWorkingHours total hours logged in the month
     * @param slaSeverity       SLA 007 severity for the month: 0 (>=16 days & >=144 hrs),
     *                          2 (>=12 days & >=108 hrs), else 4
     */
    public record MonthlyAvailability(
            int year,
            int month,
            String period,
            LocalDate fromDate,
            LocalDate toDate,
            int businessDays,
            double presentDays,
            double totalWorkingHours,
            int slaSeverity) {}
}
