package com.example.leavemanagement.dto;

/**
 * One resource's cost for one calendar month, with a full amount breakup.
 *
 * <p>{@code cost = monthlyRate * (effectivePaidDays / workingDays)}, where {@code effectivePaidDays
 * = min(workingDays, presentDays + halfDays*0.5 + halfDayLeaveCredit + paidAbsentLeaveCredit + relaxationDaysApplied)}.
 *
 * <ul>
 *   <li>{@code paidLeaveDaysApplied} — total leave-allowance credit applied this month
 *       (= halfDayLeaveCredit + paidAbsentLeaveCredit). Half-days consume the monthly allowance
 *       first (each half-day mark uses 0.5 days of entitlement); any remaining allowance then
 *       covers absent days. Only the days/fractions within the entitlement are paid.
 *   <li>{@code perDayRate} — {@code monthlyRate / workingDays}, the daily unit price.
 *   <li>{@code halfDayAmount} — {@code perDayRate * halfDays * 0.5}, amount earned from half-days.
 *   <li>{@code deductedAmount} — {@code monthlyRate - cost}, amount lost to truly unpaid days.
 * </ul>
 *
 * <p>{@code relaxationDaysApplied} is this month's share of the quarter's {@link
 * com.example.leavemanagement.entity.LeaveRelaxation#getRelaxationDays() relaxationDays} (UIDAI's
 * final relaxation decision). All amounts are 0 when the resource has no active assignment, no
 * rateYear set, or no rate configured for that year.
 */
public record MonthlyResourceCost(
        String attendanceId,
        String employeeName,
        String projectId,
        String rateYear,
        String period,
        int workingDays,
        int presentDays,
        int halfDays,
        int absentDays,
        double paidLeaveDaysApplied,
        int relaxationDaysApplied,
        double effectivePaidDays,
        double attendancePercentage,
        double monthlyRate,
        double perDayRate,
        double halfDayAmount,
        double deductedAmount,
        double cost) {}
