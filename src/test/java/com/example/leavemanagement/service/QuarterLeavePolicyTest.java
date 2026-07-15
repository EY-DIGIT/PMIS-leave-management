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
        assertThat(calc.leaveDaysTaken()).isEqualTo(7);
        assertThat(calc.paidLeaveDays()).isEqualTo(6);
        assertThat(calc.unpaidLeaveDays()).isEqualTo(1);
        assertThat(calc.sandwichDays()).isZero(); // Sat/Sun 15-16 prefixed by a PAID leave -> not charged
        assertThat(calc.totalUnpaidDays()).isEqualTo(1);
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
        assertThat(calc.paidLeaveDays()).isEqualTo(6);
        assertThat(calc.unpaidLeaveDays()).isEqualTo(13); // Jun 12,13,14,17..21,24..28
        assertThat(calc.sandwichDays()).isEqualTo(6); // weekends 15-16, 22-23, 29-30
        assertThat(calc.totalUnpaidDays()).isEqualTo(19);
        assertThat(calc.lapsedLeaveDays()).isZero();
    }

    @Test
    void sandwichChargedWhenWeekendBracketedByTwoUnpaidLeaves() {
        // No permissible leave (joined so late it pro-rates to ~0) so both Fri & Mon are unpaid.
        // Simpler: use a full quarter but make the first 6 leaves earlier, then Fri 21 + Mon 24 unpaid.
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

        assertThat(calc.unpaidLeaveDays()).isEqualTo(2);
        assertThat(calc.sandwichDays()).isEqualTo(2); // Sat 22 + Sun 23 bracketed by unpaid leaves
        assertThat(calc.totalUnpaidDays()).isEqualTo(4);
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
        assertThat(calc.paidLeaveDays()).isEqualTo(4);
        assertThat(calc.unpaidLeaveDays()).isZero();
        assertThat(calc.lapsedLeaveDays()).isEqualTo(4);
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
}
