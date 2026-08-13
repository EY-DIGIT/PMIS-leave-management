package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Monthly resource-availability report for UIDAI SLA 007 (Minimum Resource Availability). For each
 * resource active on the project during the month, it reports the business days attended and the
 * total working hours logged (from the biometric attendance), plus the derived SLA severity level.
 *
 * @param projectId     the project the report is scoped to
 * @param year          the report year
 * @param month         the report month (1-12)
 * @param period        human-readable month label (e.g. "February 2026")
 * @param resourceCount number of resources in the report
 * @param resources     per-resource availability rows
 */
public record ResourceAvailabilityReport(
        String projectId,
        int year,
        int month,
        String period,
        int resourceCount,
        List<ResourceAvailability> resources) {

    /**
     * One resource's monthly availability.
     *
     * @param attendanceId      the resource's attendance id (res_id)
     * @param employeeName      the resource's name
     * @param designation       the resource's role/designation
     * @param businessDays      days the resource logged attendance in the month (Present + Half-Day + WFH)
     * @param presentDays       weighted present days (Present + 0.5 × Half-Day), for reference
     * @param totalWorkingHours total hours logged across the month (sum of daily In/Out worked hours)
     * @param slaSeverity       SLA 007 applied severity level: 0 (>=16 days & >=144 hrs),
     *                          2 (>=12 days & >=108 hrs), else 4
     */
    public record ResourceAvailability(
            String attendanceId,
            String employeeName,
            String designation,
            int businessDays,
            double presentDays,
            double totalWorkingHours,
            int slaSeverity) {}
}
