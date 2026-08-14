package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * UIDAI SLA 008 (Additional Resource Onboarding) report for one activity. An additional resource is
 * one created when the activity's approved resource requirement increases over its baseline — a brand
 * new designation, or a higher quantity for an already-configured designation. Replacements are NOT
 * additional resources (those are SLA 006 / SLA 009).
 *
 * <p>For each additional resource: K = the designation's planned deployment date; L = the first
 * attendance date of the additional resource on the activity; {@code onboardingDays = L − K} in
 * calendar days. The result is one of "Within 21 Days" ({@code <= 21}), "More than 21 Days and within
 * 28 Days" ({@code 22..28}), "More than 28 Days" ({@code > 28}), or "Pending Onboarding" (no
 * attendance yet). Never a severity level.
 *
 * @param projectId               the project the report is scoped to
 * @param activityId              the activity the report is scoped to
 * @param activityName            the activity's name (from PMIS)
 * @param additionalResourceCount number of additional-resource rows (filled + pending)
 * @param additionalResources     one row per additional resource / unfilled additional slot
 */
public record ActivityAdditionalResourceReport(
        String projectId,
        String activityId,
        String activityName,
        int additionalResourceCount,
        List<AdditionalResource> additionalResources) {

    /**
     * One additional-resource result.
     *
     * @param slaNumber               always "SLA008"
     * @param designation             the additional designation
     * @param originalQuantity        baseline approved quantity for the designation
     * @param currentApprovedQuantity current approved quantity (from the live activity config)
     * @param additionalQuantity      currentApprovedQuantity − originalQuantity
     * @param resId                   the additional resource's attendance id (null for an unfilled slot)
     * @param employeeName            the additional resource's name (null for an unfilled slot)
     * @param plannedDeploymentDate   K — the SLA 008 start date
     * @param actualOnboardingDate    L — first attendance date on the activity (null when pending)
     * @param onboardingDays          L − K in calendar days (null when pending)
     * @param slaResult               "Within 21 Days", "More than 21 Days and within 28 Days",
     *                                "More than 28 Days", or "Pending Onboarding"
     */
    public record AdditionalResource(
            String slaNumber,
            String designation,
            int originalQuantity,
            int currentApprovedQuantity,
            int additionalQuantity,
            String resId,
            String employeeName,
            LocalDate plannedDeploymentDate,
            LocalDate actualOnboardingDate,
            Integer onboardingDays,
            String slaResult) {}
}
