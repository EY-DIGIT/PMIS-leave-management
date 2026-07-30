package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

/**
 * One resource's cost for one billing period (cycle-aligned month), using a calendar-days formula.
 *
 * <p>Cost = {@code monthlyRate × paidCalendarDays / calendarDays}, where
 * {@code paidCalendarDays = calendarDays − unpaidLeaveDays}. Only unpaid leave reduces cost;
 * weekends and public holidays are already priced into the monthly rate.
 *
 * <ul>
 *   <li>{@code fromDate} / {@code toDate} — the billing period start/end (cycle-day aligned).
 *   <li>{@code calendarDays} — total calendar days in the period.
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
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate fromDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate toDate,
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
