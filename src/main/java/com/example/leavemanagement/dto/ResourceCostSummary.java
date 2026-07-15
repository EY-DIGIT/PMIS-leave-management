package com.example.leavemanagement.dto;

import java.util.List;

/**
 * One resource's total cost over a multi-month period (quarter/year) — the sum of each covered
 * month's cost, computed separately per month so a mid-period rate-year change (or a month with
 * no attendance) is reflected correctly rather than blended across the whole period.
 */
public record ResourceCostSummary(
        String attendanceId,
        String employeeName,
        String projectId,
        String period,
        double totalCost,
        List<MonthlyResourceCost> monthlyBreakdown) {}
