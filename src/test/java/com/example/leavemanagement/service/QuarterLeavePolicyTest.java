package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarterLeavePolicyTest {

    private final QuarterLeavePolicy policy = new QuarterLeavePolicy();

    // The RFP illustrations use Apr-Jun 2024 (a full quarter, joined 1 Apr).
    private static final LocalDate Q_START = LocalDate.of(2024, 4, 1);
    private static final LocalDate Q_END = LocalDate.of(2024, 6, 30);

    @Test
    void illustration1_oneUnpaidDay_weekendNotSandwichedBecausePrefixIsPaidLeave() {
        // Apr: 2 leaves, May: 3 leaves, June: Fri 14 (6th = paid), Mon 17 (7th = unpaid).
        Set<LocalDate> absent = new HashSet<>(Set.of(
                LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 4, 2),
                LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 2),
                LocalDate.of(2024, 5, 3),
                LocalDate.of(2024, 6, 14),
                LocalDate.of(2024, 6, 17)));

        QuarterLeaveCalculation calc = policy.compute(Q_START, Q_END, null, absent, Set.of());

        assertThat(calc.permissibleLeave()).isEqualTo(6);
        assertThat(calc.leaveDaysTaken()).isEqualTo(7.0);
        assertThat(calc.paidLeaveDays()).isEqualTo(6.0);
        assertThat(calc.unpaidLeaveDays()).isEqualTo(1.0);
        assertThat(calc.sandwichDays()).isZero(); // Sat/Sun 15-16 prefixed by a PAID leave -> not charged
        assertThat(calc.totalUnpaidDays()).isEqualTo(1.0);
        assertThat(calc.unpaidLeaveDates()).containsExactly(LocalDate.of(2024, 6, 17));
    }

    @Test
    void illustration2_nineteenUnpaid_includingSandwichedWeekends() {
        // Apr: 2 leaves, May: 2 leaves, June: present 1-7 then on leave from 10 to month end.
        Set<LocalDate> absent = new HashSet<>(Set.of(
                LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 4, 2),
                LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 2)));
        // every weekday from 10 Jun to 30 Jun
        for (int day = 10; day <= 30; day++) {
            LocalDate d = LocalDate.of(2024, 6, day);
            switch (d.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> {
                    /* weekend: not a weekday absence; sandwich rule handles it */
                }
                default -> absent.add(d);
            }
        }

        QuarterLeaveCalculation calc = policy.compute(Q_START, Q_END, null, absent, Set.of());

        assertThat(calc.permissibleLeave()).isEqualTo(6);
        assertThat(calc.paidLeaveDays()).isEqualTo(6.0);
        assertThat(calc.unpaidLeaveDays()).isEqualTo(13.0); // Jun 12,13,14,17..21,24..28
        assertThat(calc.sandwichDays()).isEqualTo(6); // weekends 15-16, 22-23, 29-30
        assertThat(calc.totalUnpaidDays()).isEqualTo(19.0);
        assertThat(calc.lapsedLeaveDays()).isZero();
    }

    @Test
    void sandwichChargedWhenWeekendBracketedByTwoUnpaidLeaves() {
        Set<LocalDate> absent = new HashSet<>(Set.of(
                LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 4, 2),
                LocalDate.of(2024, 4, 3),
                LocalDate.of(2024, 4, 4),
                LocalDate.of(2024, 4, 5),
                LocalDate.of(2024, 4, 8), // 6 paid
                LocalDate.of(2024, 6, 21), // Fri unpaid (7th)
                LocalDate.of(2024, 6, 24))); // Mon unpaid (8th)

        QuarterLeaveCalculation calc = policy.compute(Q_START, Q_END, null, absent, Set.of());

        assertThat(calc.unpaidLeaveDays()).isEqualTo(2.0);
        assertThat(calc.sandwichDays()).isEqualTo(2); // Sat 22 + Sun 23 bracketed by unpaid leaves
        assertThat(calc.totalUnpaidDays()).isEqualTo(4.0);
    }

    @Test
    void proRatesPermissibleLeaveForMidQuarterJoiner() {
        // Joined 1 May 2024 -> 61 of 91 days available -> round(6 * 61/91) = 4.
        QuarterLeaveCalculation calc =
                policy.compute(Q_START, Q_END, LocalDate.of(2024, 5, 1), Set.of(), Set.of());
        assertThat(calc.permissibleLeave()).isEqualTo(4);
    }

    @Test
    void joinedAfterQuarterEndYieldsNothing() {
        QuarterLeaveCalculation calc =
                policy.compute(Q_START, Q_END, LocalDate.of(2024, 7, 1), Set.of(), Set.of());
        assertThat(calc.permissibleLeave()).isZero();
        assertThat(calc.totalUnpaidDays()).isZero();
    }

    @Test
    void carriedForwardDaysAddToThisQuartersAllowance() {
        // 4 leave days taken, base allowance 6, 2 carried in from last quarter -> permissible 8,
        // all 4 leave days paid, nothing unpaid, 4 days lapse (8 allowed - 4 used).
        Set<LocalDate> absent = Set.of(
                LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 2), LocalDate.of(2024, 4, 3),
                LocalDate.of(2024, 4, 4));

        QuarterLeaveCalculation calc = policy.compute(
                Q_START, Q_END, null, absent, Set.of(), QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE, 2);

        assertThat(calc.carriedForwardLeave()).isEqualTo(2);
        assertThat(calc.permissibleLeave()).isEqualTo(8); // 6 base + 2 carried in
        assertThat(calc.paidLeaveDays()).isEqualTo(4.0);
        assertThat(calc.unpaidLeaveDays()).isZero();
        assertThat(calc.lapsedLeaveDays()).isEqualTo(4.0);
    }

    @Test
    void negativeCarriedForwardDaysAreTreatedAsZero() {
        QuarterLeaveCalculation calc = policy.compute(
                Q_START, Q_END, null, Set.of(), Set.of(), QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE, -3);

        assertThat(calc.carriedForwardLeave()).isZero();
        assertThat(calc.permissibleLeave()).isEqualTo(6);
    }

    @Test
    void defaultOverloadsCarryNoLeaveForward() {
        QuarterLeaveCalculation calc = policy.compute(Q_START, Q_END, null, Set.of(), Set.of());
        assertThat(calc.carriedForwardLeave()).isZero();

        QuarterLeaveCalculation calcWithMax =
                policy.compute(Q_START, Q_END, null, Set.of(), Set.of(), 4);
        assertThat(calcWithMax.carriedForwardLeave()).isZero();
        assertThat(calcWithMax.permissibleLeave()).isEqualTo(4);
    }

    @Test
    void halfDayCountsAsHalfLeaveDay() {
        // 2 full absent + 2 half-days = 2 + 1.0 = 3.0 effective leave days. All within quota (6).
        Set<LocalDate> absent = Set.of(
                LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 4, 2));
        Set<LocalDate> halfDays = Set.of(
                LocalDate.of(2024, 4, 3),
                LocalDate.of(2024, 4, 4));

        QuarterLeaveCalculation calc = policy.compute(
                Q_START, Q_END, null, absent, halfDays, Set.of(),
                QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE, 0);

        assertThat(calc.leaveDaysTaken()).isEqualTo(3.0);
        assertThat(calc.paidLeaveDays()).isEqualTo(3.0);
        assertThat(calc.unpaidLeaveDays()).isZero();
        assertThat(calc.lapsedLeaveDays()).isEqualTo(3.0); // 6 - 3 = 3 lapsed
        // paidLeaveDates contains only full-absent days; half-days are tracked by the caller via
        // halfDayDates to keep the three date lists mutually exclusive.
        assertThat(calc.paidLeaveDates()).containsExactlyInAnyOrder(
                LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 2));
        assertThat(calc.unpaidLeaveDates()).isEmpty();
    }

    @Test
    void halfDayDoesNotTriggerSandwichLeave() {
        // Half-day on Fri, full absent on Mon — the weekend in between should NOT be sandwiched
        // because the employee worked Friday (half-day).
        Set<LocalDate> absent = Set.of(
                LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 2),
                LocalDate.of(2024, 4, 3), LocalDate.of(2024, 4, 4),
                LocalDate.of(2024, 4, 5), LocalDate.of(2024, 4, 8), // 6 paid — quota exhausted
                LocalDate.of(2024, 6, 24)); // Mon unpaid (full absent)
        Set<LocalDate> halfDays = Set.of(LocalDate.of(2024, 6, 21)); // Fri half-day (unpaid, beyond quota)

        QuarterLeaveCalculation calc = policy.compute(
                Q_START, Q_END, null, absent, halfDays, Set.of(),
                QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE, 0);

        // Half-day (Fri) + full absent (Mon) together surround the weekend, but sandwich should
        // NOT fire because Fri is a half-day (employee worked), not a full absent.
        assertThat(calc.sandwichDays()).isZero();
        assertThat(calc.unpaidLeaveDays()).isEqualTo(1.5); // 0.5 (half-day) + 1.0 (full Mon)
    }
}
