package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

/**
 * One resource's cost for one billing period (cycle-aligned month).
 *
 * <p>{@code calendarDays} = effective days billed from {@code max(fromDate, joiningDate)} to
 * {@code toDate}. {@code perDayRate} is based on the full period length so the rate card meaning
 * is preserved. {@code deductedAmount} covers only unpaid leave; the pre-joining gap is handled
 * through {@code calendarDays}. Relaxation is settled quarterly — see {@link
 * com.example.leavemanagement.dto.ResourceCostSummary}.
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
        double billableDays,
        double monthlyRate,
        double perDayRate,
        double deductedAmount,
        double cost) {}
