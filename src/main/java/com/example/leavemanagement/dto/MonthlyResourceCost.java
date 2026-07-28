package com.example.leavemanagement.dto;

/**
 * One resource's cost for one calendar month, using a calendar-days-based formula.
 *
 * <p>Cost = {@code monthlyRate × paidCalendarDays / calendarDays}, where
 * {@code paidCalendarDays = calendarDays − unpaidLeaveDays}. Only unpaid leave reduces cost;
 * weekends and public holidays are already priced into the monthly rate.
 *
 * <ul>
 *   <li>{@code calendarDays} — total calendar days in the month (28–31).
 *   <li>{@code unpaidLeaveDays} — effective absences beyond the monthly leave entitlement.
 *   <li>{@code paidCalendarDays} — {@code calendarDays − unpaidLeaveDays}.
 *   <li>{@code perDayRate} — {@code monthlyRate / calendarDays}.
 *   <li>{@code deductedAmount} — {@code unpaidLeaveDays × perDayRate} = {@code monthlyRate − cost}.
 * </ul>
 *
 * <p>Relaxation leave is settled quarterly — it does not appear here.
 * See {@link com.example.leavemanagement.dto.ResourceCostSummary} for the quarterly
 * {@code relaxationCost} that is added on top.
 */
public record MonthlyResourceCost(
        String attendanceId,
        String employeeName,
        String projectId,
        String milestoneId,
        String activityId,
        String rateYear,
        String period,
        // Attendance breakdown (informational)
        int workingDays,
        double presentDays,
        int halfDays,
        int absentDays,
        double paidLeaveDays,
        // Calendar-days cost calculation
        int calendarDays,
        double unpaidLeaveDays,
        double paidCalendarDays,
        double monthlyRate,
        double perDayRate,
        double deductedAmount,
        double cost) {}
