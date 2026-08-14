package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * UIDAI SLA 009 (Delay in Onboarding of Replacement Resource) report for one activity. For each
 * replacement it computes the onboarding delay as {@code Actual Mobilization Date − Date of
 * Notification} in <em>calendar days</em> (the SLA says "21 Days", not "21 working days"), and
 * returns only the SLA result — never a severity level. When the notification date is missing the
 * result is "Manual" (the SLA allows manual capture from the biometric attendance system).
 *
 * @param projectId        the project the report is scoped to
 * @param activityId       the activity the report is scoped to
 * @param activityName     the activity's name (from PMIS)
 * @param replacementCount number of replacements detected on the activity
 * @param replacements     one entry per replacement
 */
public record ActivityReplacementOnboardingReport(
        String projectId,
        String activityId,
        String activityName,
        int replacementCount,
        List<ReplacementOnboarding> replacements) {

    /**
     * One replacement's onboarding-delay result.
     *
     * @param slaNumber           always "SLA009"
     * @param outgoingResId       outgoing resource's attendance id
     * @param outgoingName        outgoing resource's name
     * @param outgoingDesignation outgoing resource's designation
     * @param incomingResId       incoming (replacement) resource's attendance id
     * @param incomingName        incoming resource's name
     * @param incomingDesignation incoming resource's designation
     * @param notificationDate    date the replacement was notified (null ⇒ result is "Manual")
     * @param mobilizationDate    actual mobilization = incoming resource's joining date
     * @param onboardingDays      calendar days between notification and mobilization (null when notification missing)
     * @param slaResult           "Within 21 Days", "More than 21 Days", or "Manual"
     */
    public record ReplacementOnboarding(
            String slaNumber,
            String outgoingResId,
            String outgoingName,
            String outgoingDesignation,
            String incomingResId,
            String incomingName,
            String incomingDesignation,
            LocalDate notificationDate,
            LocalDate mobilizationDate,
            Integer onboardingDays,
            String slaResult) {}
}
