package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.ActivityAdditionalResourceReport;
import com.example.leavemanagement.service.AdditionalResourceSlaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
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
        description = "Additional-resource onboarding-delay report (UIDAI SLA 008)")
public class AdditionalResourceSlaController {

    private final AdditionalResourceSlaService service;

    public AdditionalResourceSlaController(AdditionalResourceSlaService service) {
        this.service = service;
    }

    @Operation(
            summary = "Additional resource onboarding (UIDAI SLA 008)",
            description = "For each additional resource on the activity — a config line whose "
                    + "resourceClassification is 'additional' (replacements excluded) — computes the "
                    + "onboarding delay as (first attendance date L − planned deployment date K) in CALENDAR "
                    + "days and returns the result: 'Within 21 Days' (<= 21), 'More than 21 Days and within "
                    + "28 Days' (22-28), 'More than 28 Days' (> 28), or 'Pending Onboarding' (no attendance "
                    + "yet). No severity.")
    @GetMapping("/additional-resource-onboarding")
    public ActivityAdditionalResourceReport additionalResourceOnboarding(
            @Parameter(description = "Project id") @RequestParam String projectId,
            @Parameter(description = "Activity id from PMIS") @RequestParam String activityId) {
        return service.additionalResourceOnboarding(projectId, activityId);
    }
}
