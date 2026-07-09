package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A project's leave policy, fetched from the external leave-policy API — never hardcoded here. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeavePolicyResponse(
        String leaveFrequency, Double halfDayHours, Double fullDayHours, Boolean sandwichLeaveApplicable) {}
