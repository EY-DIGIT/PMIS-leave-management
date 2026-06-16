package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Output of the leave-policy engine for one resource in one quarter.
 *
 * @param permissibleLeave paid days allowed this quarter (6, or pro-rata for a mid-quarter joiner)
 * @param leaveDaysTaken absent working days in the quarter
 * @param paidLeaveDays leave days covered by the permissible allowance
 * @param unpaidLeaveDays absent working days beyond the allowance
 * @param sandwichDays weekend/holiday days charged as unpaid under the sandwich rule
 * @param totalUnpaidDays unpaidLeaveDays + sandwichDays (the payable deduction)
 * @param lapsedLeaveDays permissible days left unused (they lapse, no carry-forward)
 * @param paidLeaveDates the dates counted as paid leave
 * @param unpaidLeaveDates the working-day dates counted as unpaid leave
 * @param sandwichDates the weekend/holiday dates pulled in by the sandwich rule
 */
public record QuarterLeaveCalculation(
        int permissibleLeave,
        int leaveDaysTaken,
        int paidLeaveDays,
        int unpaidLeaveDays,
        int sandwichDays,
        int totalUnpaidDays,
        int lapsedLeaveDays,
        List<LocalDate> paidLeaveDates,
        List<LocalDate> unpaidLeaveDates,
        List<LocalDate> sandwichDates) {}
