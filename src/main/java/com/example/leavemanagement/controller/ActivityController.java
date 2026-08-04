package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.ActivityRequest;
import com.example.leavemanagement.dto.ActivityResponse;
import com.example.leavemanagement.service.ActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activities")
@Tag(name = "Activity", description = "Register activity date bounds and resource requirements for attendance upload validation")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Operation(
            summary = "Register or update an activity's date range",
            description = "Called by the UI (which already has activity data from PMIS) to store the activity's "
                    + "date bounds so attendance uploads can be validated against them. "
                    + "Creates the record if it does not exist; updates dates if it does.")
    @PutMapping("/{activityId}")
    public ActivityResponse register(
            @Parameter(description = "Activity id from PMIS") @PathVariable String activityId,
            @Valid @RequestBody ActivityRequest request) {
        return activityService.upsert(activityId, request);
    }

    @Operation(
            summary = "Sync designation requirements from external API",
            description = "Called by the external API integration to push the designation requirements "
                    + "(role name → required count) for an activity. "
                    + "These are used to validate that uploaded attendance resources match the configured roles.")
    @PutMapping("/{activityId}/designation-requirements")
    public ActivityResponse syncRequirements(
            @PathVariable String activityId,
            @RequestBody Map<String, Integer> requirements) {
        return activityService.syncDesignationRequirements(activityId, requirements);
    }

    @Operation(summary = "Get the registered configuration for an activity")
    @GetMapping("/{activityId}")
    public ActivityResponse get(@PathVariable String activityId) {
        return activityService.findById(activityId);
    }
}
