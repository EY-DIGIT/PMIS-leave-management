package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * UIDAI SLA 006 (Resource Replacement Overlap) report for one activity. For each resource-replacement
 * on the activity it computes the overlap between the incoming resource's joining date and the
 * outgoing resource's last working date, counted in <em>working days</em> against the UIDAI
 * working-day/holiday calendar (not raw calendar days), and returns only the SLA result — never a
 * severity level (applying severity is outside this application's scope).
 *
 * @param projectId        the project the report is scoped to
 * @param activityId       the activity the report is scoped to
 * @param activityName     the activity's name (from PMIS)
 * @param replacementCount number of replacements detected on the activity
 * @param replacements     one entry per replacement
 */
public record ActivityReplacementOverlapReport(
        String projectId,
        String activityId,
        String activityName,
        int replacementCount,
        List<ReplacementOverlap> replacements) {

    /**
     * One resource-replacement's overlap result.
     *
     * @param slaNumber              always "SLA006"
     * @param outgoingResId          outgoing resource's attendance id
     * @param outgoingName           outgoing resource's name
     * @param outgoingDesignation    outgoing resource's designation
     * @param outgoingLastWorkingDate outgoing resource's last working date (overlap end)
     * @param incomingResId          incoming resource's attendance id
     * @param incomingName           incoming resource's name
     * @param incomingDesignation    incoming resource's designation
     * @param incomingJoiningDate    incoming resource's joining date (overlap start)
     * @param overlapStartDate       overlap start = incoming joining date
     * @param overlapEndDate         overlap end = outgoing last working date
     * @param overlapWorkingDays     working days in the overlap window per the UIDAI calendar (0 if no overlap)
     * @param slaResult              "Overlap >= 20 Working Days" or "Overlap < 20 Working Days"
     */
    public record ReplacementOverlap(
            String slaNumber,
            String outgoingResId,
            String outgoingName,
            String outgoingDesignation,
            LocalDate outgoingLastWorkingDate,
            String incomingResId,
            String incomingName,
            String incomingDesignation,
            LocalDate incomingJoiningDate,
            LocalDate overlapStartDate,
            LocalDate overlapEndDate,
            int overlapWorkingDays,
            String slaResult) {}
}
