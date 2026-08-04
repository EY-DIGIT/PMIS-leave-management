package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.Map;

public record ActivityResponse(
        String activityId,
        String activityName,
        String projectId,
        String milestoneId,
        String organisationId,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate startDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate endDate,
        Map<String, Integer> designationRequirements,
        int totalRequiredResources) {}
