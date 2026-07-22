package com.example.leavemanagement.dto;

/**
 * One resource's cost for one calendar month, with a full amount breakup.
 *
 * <p>{@code cost = monthlyRate * (effectivePaidDays / workingDays)}, where {@code effectivePaidDays
 * = min(workingDays, presentDays + paidLeaveDaysApplied + relaxationDaysApplied)}.
 * {@code presentDays} already includes the worked half-day portion (halfDays×0.5).
 *
 * <ul>
 *   <li>{@code presentDays} — effective days worked: P-status days + halfDays×0.5.
 *   <li>{@code paidLeaveDaysApplied} — leave-allowance credit this month:
 *       MIN(absentDays + halfDays×0.5, monthlyLeaveAllowance). Each half-day consumes 0.5
 *       leave-quota days; remaining allowance covers absent days.
 *   <li>{@code perDayRate} — {@code monthlyRate / workingDays}, the daily unit price.
 *   <li>{@code halfDayAmount} — {@code perDayRate * halfDays * 0.5}, amount earned from half-days.
 *   <li>{@code deductedAmount} — {@code monthlyRate - cost}, amount lost to truly unpaid days.
 * </ul>
 *
 * <p>Relaxation leave is a quarterly settlement, not a monthly one — it does not appear here.
 * See {@link com.example.leavemanagement.dto.ResourceCostSummary} for the quarterly
 * {@code relaxationAmount} that is added on top of the sum of monthly costs.
 * {@code monthlyRate} is looked up from the resource's active rate card by {@code rateYear}
 * (e.g. "Year-3"). All amounts are 0 when the resource has no active assignment, no rateYear set,
 * or no rate configured for that year.
 */
public record MonthlyResourceCost(
        String attendanceId,
        String employeeName,
        String projectId,
        String rateYear,
        String period,
        int workingDays,
        double presentDays,
        int halfDays,
        int absentDays,
        double paidLeaveDaysApplied,
        double effectivePaidDays,
        double attendancePercentage,
        double monthlyRate,
        double perDayRate,
        double halfDayAmount,
        double deductedAmount,
        double cost) {}
