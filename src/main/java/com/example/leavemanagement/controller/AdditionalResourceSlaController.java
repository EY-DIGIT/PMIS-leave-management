package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.ActivityAdditionalResourceReport;
import com.example.leavemanagement.dto.ActivityBaselineRow;
import com.example.leavemanagement.service.AdditionalResourceSlaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * UIDAI SLA 008 — Additional Resource Onboarding. Kept separate from the main attendance endpoints so
 * it doesn't affect the existing attendance/leave/cost flows.
 */
@RestController
@RequestMapping("/api/attendance/report/activity")
@Tag(name = "SLA 008 - Additional Resource Onboarding",
        description = "Baseline capture and additional-resource onboarding-delay report (UIDAI SLA 008)")
public class AdditionalResourceSlaController {

    private final AdditionalResourceSlaService service;

    public AdditionalResourceSlaController(AdditionalResourceSlaService service) {
        this.service = service;
    }

    @Operation(
            summary = "Capture the SLA 008 baseline (original approved requirement) for an activity",
            description = "Snapshots the activity's current live configuration (designation → quantity + "
                    + "planned deployment date) as the baseline to compare future increases against. Call this "
                    + "at original-config time, BEFORE any additional requirement is approved. If a baseline "
                    + "already exists it is preserved unless force=true (which re-captures from the current config).")
    @PostMapping("/baseline")
    public List<ActivityBaselineRow> captureBaseline(
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId,
            @Parameter(description = "Re-capture from the current config, overwriting the existing baseline")
                    @RequestParam(defaultValue = "false") boolean force) {
        return service.captureBaseline(activityId, force);
    }

    @Operation(
            summary = "Get the captured SLA 008 baseline for an activity",
            description = "Returns the stored baseline (original approved requirement) per designation. Empty "
                    + "if no baseline has been captured yet.")
    @GetMapping("/baseline")
    public List<ActivityBaselineRow> getBaseline(
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId) {
        return service.getBaseline(activityId);
    }

    @Operation(
            summary = "Additional resource onboarding (UIDAI SLA 008)",
            description = "For each additional resource on the activity — a new designation, or a quantity "
                    + "increase over the captured baseline (replacements excluded) — computes the onboarding "
                    + "delay as (first attendance date L − planned deployment date K) in CALENDAR days and "
                    + "returns the result: 'Within 21 Days' (<= 21), 'More than 21 Days and within 28 Days' "
                    + "(22-28), 'More than 28 Days' (> 28), or 'Pending Onboarding' (no attendance yet). No "
                    + "severity. If no baseline exists yet, the current config is snapshotted as the baseline "
                    + "first (so only later increases are reported).")
    @GetMapping("/additional-resource-onboarding")
    public ActivityAdditionalResourceReport additionalResourceOnboarding(
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId) {
        return service.additionalResourceOnboarding(projectId, activityId);
    }
}
