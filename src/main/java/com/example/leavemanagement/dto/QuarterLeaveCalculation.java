package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Output of the leave-policy engine for one resource in one quarter.
 *
 * @param permissibleLeave total paid days allowed this quarter — the base allowance (6, or
 *     pro-rata for a mid-quarter joiner) plus {@code carriedForwardLeave}
 * @param carriedForwardLeave unused permissible leave brought in from the previous quarter (0
 *     unless the project's leave policy allows carry-forward)
 * @param leaveDaysTaken absent working days in the quarter
 * @param paidLeaveDays leave days covered by the permissible allowance
 * @param unpaidLeaveDays absent working days beyond the allowance
 * @param sandwichDays weekend/holiday days charged as unpaid under the sandwich rule
 * @param totalUnpaidDays unpaidLeaveDays + sandwichDays (the payable deduction)
 * @param lapsedLeaveDays permissible days (including any carry-in) left unused this quarter —
 *     these are what the <em>next</em> quarter may carry in, if carry-forward is allowed
 * @param paidLeaveDates the dates counted as paid leave
 * @param unpaidLeaveDates the working-day dates counted as unpaid leave
 * @param sandwichDates the weekend/holiday dates pulled in by the sandwich rule
 */
public record QuarterLeaveCalculation(
        int permissibleLeave,
        int carriedForwardLeave,
        int leaveDaysTaken,
        int paidLeaveDays,
        int unpaidLeaveDays,
        int sandwichDays,
        int totalUnpaidDays,
        int lapsedLeaveDays,
        List<LocalDate> paidLeaveDates,
        List<LocalDate> unpaidLeaveDates,
        List<LocalDate> sandwichDates) {}
