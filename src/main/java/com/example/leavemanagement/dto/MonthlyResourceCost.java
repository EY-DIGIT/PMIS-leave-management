package com.example.leavemanagement.dto;

/**
 * One resource's cost for one calendar month, with a full amount breakup.
 *
 * <p>{@code cost = monthlyRate * (effectivePaidDays / workingDays)}, where {@code effectivePaidDays
 * = min(workingDays, presentDays + halfDays*0.5 + relaxationDaysApplied)}.
 *
 * <ul>
 *   <li>{@code perDayRate} — {@code monthlyRate / workingDays}, the daily unit price.
 *   <li>{@code halfDayAmount} — {@code perDayRate * halfDays * 0.5}, amount earned from half-days.
 *   <li>{@code deductedAmount} — {@code monthlyRate - cost}, amount lost to unpaid (absent) days.
 * </ul>
 *
 * <p>{@code relaxationDaysApplied} is this month's share of the quarter's {@link
 * com.example.leavemanagement.entity.LeaveRelaxation#getRelaxationDays() relaxationDays} (UIDAI's
 * final relaxation decision, which never changes paid leave but forgives some unpaid leave) —
 * applied via sequential fill, oldest month in the quarter first, capping at each month's own
 * absent-day count before spilling into the next month. {@code monthlyRate} is looked up from the
 * resource's active {@link com.example.leavemanagement.entity.ProjectResource#getRateCardByYear()
 * rate card} using its current {@link
 * com.example.leavemanagement.entity.ProjectResource#getRateYear() rateYear} (e.g. "Year-3"). All
 * amounts are 0 when the resource has no active assignment, no rateYear set, or no rate configured
 * for that year.
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
        int relaxationDaysApplied,
        double effectivePaidDays,
        double attendancePercentage,
        double monthlyRate,
        double perDayRate,
        double halfDayAmount,
        double deductedAmount,
        double cost) {}
