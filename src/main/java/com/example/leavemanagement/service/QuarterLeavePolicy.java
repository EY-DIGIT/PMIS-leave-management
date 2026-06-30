package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure implementation of UIDAI leave policy 5.24.1 for a single resource in a
 * single quarter. No persistence, no I/O — given the quarter window, the
 * resource's joining date, their absent dates and the public-holiday calendar,
 * it returns the paid/unpaid breakdown.
 *
 * <p>Rules implemented:
 *
 * <ul>
 *   <li>5.24.1.a — up to {@value #MAX_PERMISSIBLE_LEAVE} paid leave days per quarter; the rest are
 *       unpaid. Unused days lapse (no carry-forward).
 *   <li>5.24.1.a.iii — a mid-quarter joiner's allowance is pro-rated by calendar days.
 *   <li>5.24.1.b — sandwich leave: a weekend/holiday is charged as unpaid when the
 *       absences bracketing it are both unpaid leave (or it trails an open unpaid
 *       stretch at quarter end). It is not charged when either side is a paid leave
 *       or a worked day.
 * </ul>
 */
@Component
public class QuarterLeavePolicy {

    public static final int MAX_PERMISSIBLE_LEAVE = 6;

    /**
     * @param quarterStart first day of the quarter (inclusive)
     * @param quarterEnd last day of the quarter (inclusive)
     * @param joiningDate the resource's joining date, or {@code null} if on/before the quarter start
     * @param absentDates every date the resource was marked absent (weekends included is fine —
     *     only absences on working days become leave)
     * @param holidays public holidays in the quarter
     */
    public QuarterLeaveCalculation compute(
            LocalDate quarterStart,
            LocalDate quarterEnd,
            LocalDate joiningDate,
            Set<LocalDate> absentDates,
            Set<LocalDate> holidays) {

        Set<LocalDate> holidaySet = holidays == null ? Set.of() : holidays;
        LocalDate effectiveStart =
                (joiningDate != null && joiningDate.isAfter(quarterStart)) ? joiningDate : quarterStart;

        // Joined after the quarter ended -> nothing applies.
        if (effectiveStart.isAfter(quarterEnd)) {
            return new QuarterLeaveCalculation(0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }

        int permissible = permissibleLeave(quarterStart, quarterEnd, effectiveStart, joiningDate);

        List<LocalDate> leaveDates = absentDates.stream()
                .filter(d -> !d.isBefore(effectiveStart) && !d.isAfter(quarterEnd))
                .filter(d -> isWorkingDay(d, holidaySet))
                .sorted()
                .toList();

        int paidCount = Math.min(leaveDates.size(), permissible);
        List<LocalDate> paidDates = List.copyOf(leaveDates.subList(0, paidCount));
        List<LocalDate> unpaidDates = List.copyOf(leaveDates.subList(paidCount, leaveDates.size()));
        Set<LocalDate> unpaidSet = new HashSet<>(unpaidDates);

        List<LocalDate> sandwichDates =
                sandwichDates(effectiveStart, quarterEnd, holidaySet, unpaidSet);

        int unpaidLeave = unpaidDates.size();
        int sandwich = sandwichDates.size();
        int lapsed = Math.max(0, permissible - paidCount);

        return new QuarterLeaveCalculation(
                permissible,
                leaveDates.size(),
                paidCount,
                unpaidLeave,
                sandwich,
                unpaidLeave + sandwich,
                lapsed,
                paidDates,
                unpaidDates,
                sandwichDates);
    }

    public QuarterLeaveCalculation compute(
            LocalDate quarterStart,
            LocalDate quarterEnd,
            LocalDate joiningDate,
            Set<LocalDate> absentDates,
            Set<LocalDate> holidays,
            int maxLeavesPerPeriod) {

        Set<LocalDate> holidaySet = holidays == null ? Set.of() : holidays;
        LocalDate effectiveStart =
                (joiningDate != null && joiningDate.isAfter(quarterStart)) ? joiningDate : quarterStart;

        if (effectiveStart.isAfter(quarterEnd)) {
            return new QuarterLeaveCalculation(0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }

        int permissible = permissibleLeave(quarterStart, quarterEnd, effectiveStart, joiningDate, maxLeavesPerPeriod);

        List<LocalDate> leaveDates = absentDates.stream()
                .filter(d -> !d.isBefore(effectiveStart) && !d.isAfter(quarterEnd))
                .filter(d -> isWorkingDay(d, holidaySet))
                .sorted()
                .toList();

        int paidCount = Math.min(leaveDates.size(), permissible);
        List<LocalDate> paidDates = List.copyOf(leaveDates.subList(0, paidCount));
        List<LocalDate> unpaidDates = List.copyOf(leaveDates.subList(paidCount, leaveDates.size()));
        Set<LocalDate> unpaidSet = new HashSet<>(unpaidDates);

        List<LocalDate> sandwichDates =
                sandwichDates(effectiveStart, quarterEnd, holidaySet, unpaidSet);

        int unpaidLeave = unpaidDates.size();
        int sandwich = sandwichDates.size();
        int lapsed = Math.max(0, permissible - paidCount);

        return new QuarterLeaveCalculation(
                permissible,
                leaveDates.size(),
                paidCount,
                unpaidLeave,
                sandwich,
                unpaidLeave + sandwich,
                lapsed,
                paidDates,
                unpaidDates,
                sandwichDates);
    }

    private int permissibleLeave(
            LocalDate quarterStart, LocalDate quarterEnd, LocalDate effectiveStart, LocalDate joiningDate) {
        return permissibleLeave(quarterStart, quarterEnd, effectiveStart, joiningDate, MAX_PERMISSIBLE_LEAVE);
    }

    private int permissibleLeave(
            LocalDate quarterStart, LocalDate quarterEnd, LocalDate effectiveStart, LocalDate joiningDate, int maxLeaves) {
        if (joiningDate == null || !joiningDate.isAfter(quarterStart)) {
            return maxLeaves;
        }
        long totalDays = ChronoUnit.DAYS.between(quarterStart, quarterEnd) + 1;
        long availableDays = ChronoUnit.DAYS.between(effectiveStart, quarterEnd) + 1;
        int prorated = Math.round((float) maxLeaves * availableDays / totalDays);
        return Math.max(0, Math.min(maxLeaves, prorated));
    }

    /**
     * Weekend/holiday days charged as unpaid: those whose bracketing working days
     * are both unpaid leave, plus a trailing run that follows an unpaid leave with
     * no return to work before the quarter ends.
     */
    private List<LocalDate> sandwichDates(
            LocalDate effectiveStart, LocalDate quarterEnd, Set<LocalDate> holidays, Set<LocalDate> unpaidSet) {
        List<LocalDate> charged = new ArrayList<>();
        for (LocalDate day = effectiveStart; !day.isAfter(quarterEnd); day = day.plusDays(1)) {
            if (isWorkingDay(day, holidays)) {
                continue; // sandwich only applies to weekend/holiday days
            }
            LocalDate before = previousWorkingDay(day, effectiveStart, holidays);
            LocalDate after = nextWorkingDay(day, quarterEnd, holidays);
            boolean beforeUnpaid = before != null && unpaidSet.contains(before);
            boolean afterUnpaid = after != null && unpaidSet.contains(after);
            if (beforeUnpaid && (afterUnpaid || after == null)) {
                charged.add(day);
            }
        }
        return List.copyOf(charged);
    }

    private LocalDate previousWorkingDay(LocalDate day, LocalDate lowerBound, Set<LocalDate> holidays) {
        for (LocalDate d = day.minusDays(1); !d.isBefore(lowerBound); d = d.minusDays(1)) {
            if (isWorkingDay(d, holidays)) {
                return d;
            }
        }
        return null;
    }

    private LocalDate nextWorkingDay(LocalDate day, LocalDate upperBound, Set<LocalDate> holidays) {
        for (LocalDate d = day.plusDays(1); !d.isAfter(upperBound); d = d.plusDays(1)) {
            if (isWorkingDay(d, holidays)) {
                return d;
            }
        }
        return null;
    }

    private boolean isWorkingDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
    }
}
