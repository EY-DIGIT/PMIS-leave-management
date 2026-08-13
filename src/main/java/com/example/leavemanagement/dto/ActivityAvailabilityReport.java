package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Activity-scoped monthly availability report (UIDAI SLA 007 input). Filtered by project + activity,
 * it returns a calendar-month breakdown where each month <em>aggregates across all resources</em>:
 * the total business days attended and total working hours logged that month (from the activity's
 * biometric attendance), plus how many resources contributed.
 *
 * @param projectId         the project the report is scoped to
 * @param activityId        the activity the report is scoped to
 * @param activityName      the activity's name (from PMIS)
 * @param period            the activity window label
 * @param activityStartDate the activity start date
 * @param activityEndDate   the activity end date
 * @param monthCount        number of captured months
 * @param months            per-month aggregated availability, oldest first
 */
public record ActivityAvailabilityReport(
        String projectId,
        String activityId,
        String activityName,
        String period,
        LocalDate activityStartDate,
        LocalDate activityEndDate,
        int monthCount,
        List<MonthlyAvailability> months) {

    /**
     * One calendar month's availability aggregated over every resource on the activity.
     *
     * @param year              the month's year
     * @param month             the month (1-12)
     * @param period            human-readable month label (e.g. "February 2026")
     * @param fromDate          first captured day in the month (clamped to the activity window)
     * @param toDate            last captured day in the month (clamped to the uploaded window)
     * @param resourceCount     distinct resources that logged attendance in the month
     * @param totalBusinessDays total business days attended across all resources (Present + Half-Day + WFH)
     * @param totalPresentDays  total weighted present days across all resources (Present + 0.5 × Half-Day)
     * @param totalWorkingHours total working hours logged across all resources in the month
     */
    public record MonthlyAvailability(
            int year,
            int month,
            String period,
            LocalDate fromDate,
            LocalDate toDate,
            int resourceCount,
            int totalBusinessDays,
            double totalPresentDays,
            double totalWorkingHours) {}
}
