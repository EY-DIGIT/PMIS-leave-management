package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure implementation of UIDAI leave policy 5.24.1 for a single resource in a single quarter.
 * No persistence, no I/O.
 *
 * <p>Rules:
 * <ul>
 *   <li>5.24.1.a — up to {@value #MAX_PERMISSIBLE_LEAVE} paid leave days per quarter; the rest
 *       are unpaid. Half-day attendance dates (status=HD) each consume <em>0.5</em> of the quota.
 *       Leave is allocated chronologically — earlier absences are paid first.
 *   <li>5.24.1.a.iii — a mid-quarter joiner's allowance is pro-rated by calendar days.
 *   <li>5.24.1.b — sandwich leave: a weekend/holiday is charged as unpaid when the absences
 *       bracketing it are both <em>fully-absent</em> unpaid leave days. Half-days do not trigger
 *       sandwich leave because the employee worked that day.
 *   <li>Carry-forward — a project may allow unused permissible days from the previous quarter to
 *       be added to this quarter's allowance (passed as {@code carriedForwardDays}).
 * </ul>
 */
@Component
public class QuarterLeavePolicy {

    public static final int MAX_PERMISSIBLE_LEAVE = 6;

    /** One leave event: a date and its effective weight (1.0 for full absent, 0.5 for half-day). */
    private record WeightedDay(LocalDate date, double weight) {}

    // ------------------------------------------------------------------
    // Public API — overloads for backward compatibility; all delegate to the full 8-param version
    // ------------------------------------------------------------------

    /** No half-days, default max allowance, no carry-forward. */
    public QuarterLeaveCalculation compute(
            LocalDate quarterStart, LocalDate quarterEnd,
            LocalDate joiningDate,
            Set<LocalDate> absentDates,
            Set<LocalDate> holidays) {
        return compute(quarterStart, quarterEnd, joiningDate, absentDates, Set.of(), holidays,
                MAX_PERMISSIBLE_LEAVE, 0);
    }

    /** No half-days, custom max allowance, no carry-forward. */
    public QuarterLeaveCalculation compute(
            LocalDate quarterStart, LocalDate quarterEnd,
            LocalDate joiningDate,
            Set<LocalDate> absentDates,
            Set<LocalDate> holidays,
            int maxLeavesPerPeriod) {
        return compute(quarterStart, quarterEnd, joiningDate, absentDates, Set.of(), holidays,
                maxLeavesPerPeriod, 0);
    }

    /** No half-days, custom max allowance and carry-forward. */
    public QuarterLeaveCalculation compute(
            LocalDate quarterStart, LocalDate quarterEnd,
            LocalDate joiningDate,
            Set<LocalDate> absentDates,
            Set<LocalDate> holidays,
            int maxLeavesPerPeriod,
            int carriedForwardDays) {
        return compute(quarterStart, quarterEnd, joiningDate, absentDates, Set.of(), holidays,
                maxLeavesPerPeriod, carriedForwardDays);
    }

    /**
     * Full computation with half-day support.
     *
     * @param absentDates dates the resource was fully absent (each counts as 1.0 leave day)
     * @param halfDayDates dates the resource worked a half-day (each counts as 0.5 leave days);
     *     half-days do NOT trigger the sandwich rule
     * @param carriedForwardDays unused permissible leave from the previous quarter
     */
    public QuarterLeaveCalculation compute(
            LocalDate quarterStart,
            LocalDate quarterEnd,
            LocalDate joiningDate,
            Set<LocalDate> absentDates,
            Set<LocalDate> halfDayDates,
            Set<LocalDate> holidays,
            int maxLeavesPerPeriod,
            int carriedForwardDays) {

        Set<LocalDate> holidaySet = holidays == null ? Set.of() : holidays;
        Set<LocalDate> halfSet = halfDayDates == null ? Set.of() : halfDayDates;
        LocalDate effectiveStart =
                (joiningDate != null && joiningDate.isAfter(quarterStart)) ? joiningDate : quarterStart;

        if (effectiveStart.isAfter(quarterEnd)) {
            return new QuarterLeaveCalculation(0, 0, 0.0, 0.0, 0.0, 0, 0.0, 0.0,
                    List.of(), List.of(), List.of(), List.of());
        }

        int basePermissible =
                permissibleLeave(quarterStart, quarterEnd, effectiveStart, joiningDate, maxLeavesPerPeriod);
        int carriedForward = Math.max(0, carriedForwardDays);
        double permissible = basePermissible + carriedForward;

        // Build chronologically sorted list of all leave events with their weights.
        List<WeightedDay> allDays = new ArrayList<>();
        if (absentDates != null) {
            for (LocalDate d : absentDates) {
                if (!d.isBefore(effectiveStart) && !d.isAfter(quarterEnd) && isWorkingDay(d, holidaySet)) {
                    allDays.add(new WeightedDay(d, 1.0));
                }
            }
        }
        for (LocalDate d : halfSet) {
            if (!d.isBefore(effectiveStart) && !d.isAfter(quarterEnd) && isWorkingDay(d, holidaySet)) {
                allDays.add(new WeightedDay(d, 0.5));
            }
        }
        allDays.sort(Comparator.comparing(WeightedDay::date));

        // Allocate permissible quota chronologically.
        double remaining = permissible;
        double leaveTaken = 0.0, paidAccum = 0.0, unpaidAccum = 0.0;
        List<LocalDate> paidDates = new ArrayList<>(), unpaidDates = new ArrayList<>();
        List<LocalDate> unpaidHalfDayDates = new ArrayList<>();
        Set<LocalDate> unpaidFullAbsentSet = new HashSet<>();

        for (WeightedDay wd : allDays) {
            leaveTaken += wd.weight();
            if (remaining >= wd.weight()) {
                remaining -= wd.weight();
                paidAccum += wd.weight();
                if (wd.weight() == 1.0) {
                    paidDates.add(wd.date());
                }
            } else if (remaining > 0.0) {
                paidAccum += remaining;
                unpaidAccum += wd.weight() - remaining;
                remaining = 0.0;
                unpaidDates.add(wd.date());
                unpaidFullAbsentSet.add(wd.date());
            } else {
                unpaidAccum += wd.weight();
                if (wd.weight() == 1.0) {
                    unpaidDates.add(wd.date());
                    unpaidFullAbsentSet.add(wd.date());
                } else {
                    unpaidHalfDayDates.add(wd.date());
                }
            }
        }

        List<LocalDate> sandwichDates =
                sandwichDates(effectiveStart, quarterEnd, holidaySet, unpaidFullAbsentSet);
        int sandwich = sandwichDates.size();
        double lapsed = round2(Math.max(0.0, permissible - paidAccum));

        return new QuarterLeaveCalculation(
                basePermissible + carriedForward,
                carriedForward,
                round2(leaveTaken),
                round2(paidAccum),
                round2(unpaidAccum),
                sandwich,
                round2(unpaidAccum + sandwich),
                lapsed,
                List.copyOf(paidDates),
                List.copyOf(unpaidDates),
                List.copyOf(unpaidHalfDayDates),
                sandwichDates);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int permissibleLeave(
            LocalDate quarterStart, LocalDate quarterEnd,
            LocalDate effectiveStart, LocalDate joiningDate,
            int maxLeaves) {
        if (joiningDate == null || !joiningDate.isAfter(quarterStart)) {
            return maxLeaves;
        }
        long totalDays = ChronoUnit.DAYS.between(quarterStart, quarterEnd) + 1;
        long availableDays = ChronoUnit.DAYS.between(effectiveStart, quarterEnd) + 1;
        int prorated = Math.round((float) maxLeaves * availableDays / totalDays);
        return Math.max(0, Math.min(maxLeaves, prorated));
    }

    /**
     * Weekend/holiday days charged as unpaid: those whose bracketing working days are both
     * fully-absent unpaid leave, plus a trailing run that follows an unpaid leave with no return
     * to work before the quarter ends. Half-day dates do not trigger sandwich.
     */
    private List<LocalDate> sandwichDates(
            LocalDate effectiveStart, LocalDate quarterEnd,
            Set<LocalDate> holidays, Set<LocalDate> unpaidFullAbsentSet) {
        List<LocalDate> charged = new ArrayList<>();
        for (LocalDate day = effectiveStart; !day.isAfter(quarterEnd); day = day.plusDays(1)) {
            if (isWorkingDay(day, holidays)) {
                continue;
            }
            LocalDate before = previousWorkingDay(day, effectiveStart, holidays);
            LocalDate after = nextWorkingDay(day, quarterEnd, holidays);
            boolean beforeUnpaid = before != null && unpaidFullAbsentSet.contains(before);
            boolean afterUnpaid = after != null && unpaidFullAbsentSet.contains(after);
            if (beforeUnpaid && (afterUnpaid || after == null)) {
                charged.add(day);
            }
        }
        return List.copyOf(charged);
    }

    private LocalDate previousWorkingDay(LocalDate day, LocalDate lowerBound, Set<LocalDate> holidays) {
        for (LocalDate d = day.minusDays(1); !d.isBefore(lowerBound); d = d.minusDays(1)) {
            if (isWorkingDay(d, holidays)) return d;
        }
        return null;
    }

    private LocalDate nextWorkingDay(LocalDate day, LocalDate upperBound, Set<LocalDate> holidays) {
        for (LocalDate d = day.plusDays(1); !d.isAfter(upperBound); d = d.plusDays(1)) {
            if (isWorkingDay(d, holidays)) return d;
        }
        return null;
    }

    private boolean isWorkingDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
