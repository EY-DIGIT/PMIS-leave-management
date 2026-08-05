package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * Resource-replacement summary for one activity, derived from the resources that actually have
 * attendance uploaded under the activity. A replacement is counted whenever more distinct resources
 * worked a designation than its configured quantity:
 * {@code replacementCount = max(0, distinctResourceCount - configuredQuantity)}.
 */
public record ActivityReplacementReport(
        String activityId,
        String activityName,
        String projectId,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityStartDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityEndDate,
        int totalReplacements,
        List<DesignationReplacement> designations) {

    public record DesignationReplacement(
            String designation,
            int configuredQuantity,
            int distinctResourceCount,
            int replacementCount,
            List<ReplacementResource> resources) {}

    public record ReplacementResource(
            String resourceId,
            String employeeName,
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate joiningDate,
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate lastWorkingDate,
            boolean active) {}
}
