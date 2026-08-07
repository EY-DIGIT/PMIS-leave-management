package com.example.leavemanagement.dto;

import java.util.List;

/**
 * One resource's total cost over a multi-month period (quarter/year), computed using calendar days.
 *
 * <p>Quarterly formula:
 * <ol>
 *   <li>{@code plannedPeriodCost = monthlyRate × months} (e.g. rate × 3 for a quarter).
 *   <li>{@code perDayCost = plannedPeriodCost / calendarDays}.
 *   <li>{@code paidCalendarDays = calendarDays − unpaidLeaveDays} (from quarterly leave settlement).
 *   <li>{@code periodCost = paidCalendarDays × perDayCost}.
 *   <li>{@code relaxationCost = relaxationDays × perDayCost}.
 *   <li>{@code totalCost = periodCost + relaxationCost}.
 * </ol>
 * Only unpaid leave reduces cost. Paid leave and sandwich-charged non-working days that fall within
 * the permissible quota do not impact the cost.
 */
public record ResourceCostSummary(
        String attendanceId,
        String employeeName,
        String projectId,
        String period,
        int calendarDays,
        int activeCalendarDays,
        double plannedPeriodCost,
        double perDayCost,
        double unpaidLeaveDays,
        double paidCalendarDays,
        double billableDays,
        double deductedAmount,
        double periodCost,
        double relaxationDays,
        double relaxationCost,
        double totalCost,
        List<MonthlyResourceCost> monthlyBreakdown) {}
