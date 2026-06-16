package com.example.leavemanagement.dto;

import java.time.LocalDate;

/**
 * Per-resource quarterly leave settlement: identity plus the policy calculation.
 *
 * @param joiningDate the resource's joining date if known (drives pro-rata), else null
 */
public record ResourceQuarterSettlement(
        String attendanceId,
        String employeeName,
        LocalDate joiningDate,
        QuarterLeaveCalculation calculation) {}
