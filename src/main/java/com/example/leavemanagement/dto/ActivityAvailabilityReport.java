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
     * One monthly cycle's availability aggregated over every resource on the activity. Cycles are
     * aligned to the activity start day (e.g. 07-Jan → 06-Feb, 07-Feb → 06-Mar), not calendar months.
     *
     * @param year              the cycle start year
     * @param month             the cycle start month (1-12)
     * @param period            cycle range label (e.g. "07-Jan-2026 to 06-Feb-2026")
     * @param fromDate          cycle start (activity-start-aligned)
     * @param toDate            cycle end (day before the next cycle start, clamped to activity end)
     * @param resourceCount     distinct resources that logged attendance in the cycle
     * @param totalBusinessDays total business days attended across all resources (Present + Half-Day + WFH)
     * @param totalPresentDays  total weighted present days across all resources (Present + 0.5 × Half-Day)
     * @param totalWorkingHours total working hours logged across all resources in the cycle
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
