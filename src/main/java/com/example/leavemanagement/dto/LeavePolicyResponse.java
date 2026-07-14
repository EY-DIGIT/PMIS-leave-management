package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A project's leave policy — the {@code leaveConfig} block of the projects service's {@code GET
 * /projects/api/v3/projects/{projectId}} response. Field names match the API's JSON exactly so
 * Jackson can bind it directly, no mapping layer needed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeavePolicyResponse(
        Integer halfDay,
        Integer fullDay,
        Boolean saturdayWorking,
        Boolean sundayWorking,
        Boolean attendanceCaptured,
        Boolean sandwichLeaveApplied,
        Integer leavesPerFrequencyCount,
        String leavesFrequency,
        Boolean proratedLeavesApplied) {}
