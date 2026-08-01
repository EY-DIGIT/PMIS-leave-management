package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Output of the leave-policy engine for one resource in one quarter.
 *
 * <p>Half-day attendance dates (status=HD) each consume 0.5 of the leave quota, so
 * {@code leaveDaysTaken}, {@code paidLeaveDays}, {@code unpaidLeaveDays}, and
 * {@code lapsedLeaveDays} are {@code double} to represent fractional values.
 * {@code sandwichDays} is always a whole number (weekend/holiday days are full days).
 *
 * @param permissibleLeave total paid days allowed this quarter — the base allowance (6, or
 *     pro-rata for a mid-quarter joiner) plus {@code carriedForwardLeave}
 * @param carriedForwardLeave unused permissible leave brought in from the previous quarter (0
 *     unless the project's leave policy allows carry-forward)
 * @param leaveDaysTaken effective leave consumed: full absent days count 1.0 each, half-day
 *     attendance counts 0.5 each
 * @param paidLeaveDays leave days covered by the permissible allowance (≤ permissibleLeave)
 * @param unpaidLeaveDays effective leave beyond the allowance (leaveDaysTaken − paidLeaveDays)
 * @param sandwichDays weekend/holiday days charged as unpaid under the sandwich rule (only
 *     triggered by fully-absent unpaid days, not by half-days)
 * @param totalUnpaidDays unpaidLeaveDays + sandwichDays (the payable deduction)
 * @param lapsedLeaveDays permissible days left unused this quarter — what the next quarter may
 *     carry in if carry-forward is allowed
 * @param paidLeaveDates fully-absent dates covered by the permissible leave quota
 * @param unpaidLeaveDates fully-absent dates that exceeded the quota
 * @param unpaidHalfDayDates half-day dates whose 0.5 weight exceeded the remaining quota
 * @param sandwichDates non-working days (weekends/holidays) pulled in by the sandwich rule
 */
public record QuarterLeaveCalculation(
        double permissibleLeave,
        int carriedForwardLeave,
        double leaveDaysTaken,
        double paidLeaveDays,
        double unpaidLeaveDays,
        int sandwichDays,
        double totalUnpaidDays,
        double lapsedLeaveDays,
        List<LocalDate> paidLeaveDates,
        List<LocalDate> unpaidLeaveDates,
        List<LocalDate> unpaidHalfDayDates,
        List<LocalDate> sandwichDates) {}
