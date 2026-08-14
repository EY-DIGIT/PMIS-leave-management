package com.example.leavemanagement.dto;

import java.time.LocalDate;

/**
 * One designation of an activity's captured SLA 008 baseline (original approved requirement).
 *
 * @param designation           the designation
 * @param quantity              the baseline (original) approved quantity
 * @param plannedDeploymentDate the designation's planned deployment date (SLA 008 K)
 */
public record ActivityBaselineRow(
        String designation,
        int quantity,
        LocalDate plannedDeploymentDate) {}
