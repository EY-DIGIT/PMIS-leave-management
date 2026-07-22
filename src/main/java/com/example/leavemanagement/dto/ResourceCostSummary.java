package com.example.leavemanagement.dto;

import java.util.List;

/**
 * One resource's total cost over a multi-month period (quarter/year).
 *
 * <p>{@code totalCost = sumOfMonthlyCosts + relaxationAmount}. Monthly costs cover only the
 * attendance-based portion (present + paid leave). Relaxation leave is settled per quarter:
 * {@code relaxationAmount = relaxationDays × (quarterlyRate / quarterWorkingDays)}, where
 * {@code quarterlyRate} is the sum of the three monthly rates (handles mid-quarter rate changes)
 * and {@code quarterWorkingDays} is the sum of each month's working days.
 */
public record ResourceCostSummary(
        String attendanceId,
        String employeeName,
        String projectId,
        String period,
        double totalCost,
        double relaxationDays,
        double relaxationAmount,
        List<MonthlyResourceCost> monthlyBreakdown) {}
