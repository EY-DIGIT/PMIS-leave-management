package com.example.leavemanagement.service;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for turning a resource's quarter of absences into a paid/unpaid
 * {@link QuarterLeaveCalculation} under UIDAI leave policy 5.24.1. Both the attendance settlement
 * report and the employee leave-detail report delegate here so the permissible-leave allowance,
 * carry-forward lookback and {@link QuarterLeavePolicy} invocation are computed identically.
 *
 * <p>Full absent days (status=A) count as 1.0 leave day; half-day attendance (status=HD) counts
 * as 0.5. Both are passed to {@link QuarterLeavePolicy} which allocates them chronologically.
 */
@Component
public class QuarterLeaveResolver {

    private final AttendanceRepository attendanceRepository;
    private final PublicHolidayRepository holidayRepository;
    private final LeavePolicyClient leavePolicyClient;
    private final QuarterLeavePolicy policy;
    private final ProjectConfigRepository projectConfigRepository;

    public QuarterLeaveResolver(
            AttendanceRepository attendanceRepository,
            PublicHolidayRepository holidayRepository,
            LeavePolicyClient leavePolicyClient,
            QuarterLeavePolicy policy,
            ProjectConfigRepository projectConfigRepository) {
        this.attendanceRepository = attendanceRepository;
        this.holidayRepository = holidayRepository;
        this.leavePolicyClient = leavePolicyClient;
        this.policy = policy;
        this.projectConfigRepository = projectConfigRepository;
    }

    /**
     * Returns the configured quarter cycle start day (1–28) for the given project/organisation.
     * Falls back to 1 when no config exists, giving standard calendar quarters (Jan 1, Apr 1, etc.).
     */
    public int resolveCycleDay(String projectId, String organisationId) {
        if (projectId == null || organisationId == null) return 1;
        return projectConfigRepository
                .findByProjectIdAndOrganisationId(projectId, organisationId)
                .map(c -> c.getQuarterCycleDay())
                .orElse(1);
    }

    /**
     * Quarter start date. Quarters are always calendar-aligned (Q1=Jan, Q2=Apr, Q3=Jul, Q4=Oct)
     * but begin on {@code cycleDay} of the month rather than the 1st.
     * Example: cycleDay=7, quarter=2, year=2024 → Apr 7, 2024.
     */
    public LocalDate quarterStart(int year, int quarter, int cycleDay) {
        int baseMonth = (quarter - 1) * 3 + 1;
        return LocalDate.of(year, baseMonth, cycleDay);
    }

    /**
     * Quarter end date (inclusive) — exactly 3 months after the quarter start, minus 1 day.
     * Example: cycleDay=7, Q2 2024 start=Apr 7 → end=Jul 6.
     */
    public LocalDate quarterEnd(int year, int quarter, int cycleDay) {
        return quarterStart(year, quarter, cycleDay).plusMonths(3).minusDays(1);
    }

    /**
     * Computes the quarter's paid/unpaid leave breakdown for one resource.
     *
     * @param resourceId the {@link com.example.leavemanagement.entity.MasterResource} id
     * @param projectId the resource's project (or {@code null})
     * @param joiningDate the resource's effective assignment start, for pro-rating (or {@code null})
     * @param year settlement year
     * @param quarter settlement quarter (1–4)
     * @param absentDates dates the resource was fully absent (A) within the quarter
     * @param halfDayDates dates the resource worked a half-day (HD) within the quarter
     */
    public QuarterLeaveCalculation calculate(
            Long resourceId,
            String projectId,
            String organisationId,
            LocalDate joiningDate,
            int year,
            int quarter,
            Set<LocalDate> absentDates,
            Set<LocalDate> halfDayDates) {
        int cycleDay = resolveCycleDay(projectId, organisationId);
        LocalDate quarterStart = quarterStart(year, quarter, cycleDay);
        LocalDate quarterEnd = quarterEnd(year, quarter, cycleDay);
        Set<LocalDate> holidays = holidaysBetween(quarterStart, quarterEnd);

        Optional<LeavePolicyResponse> leavePolicy =
                projectId == null ? Optional.empty() : leavePolicyClient.getLeavePolicy(projectId);
        int maxLeaves = resolveMaxLeaves(projectId, leavePolicy);
        boolean carryForwardAllowed =
                leavePolicy.map(LeavePolicyResponse::carryForwardAllowed).orElse(Boolean.FALSE);
        int carriedForwardDays = (resourceId != null && carryForwardAllowed)
                ? resolveCarriedForwardDays(resourceId, year, quarter, joiningDate, maxLeaves, cycleDay)
                : 0;

        return policy.compute(
                quarterStart, quarterEnd, joiningDate,
                absentDates, halfDayDates,
                holidays, maxLeaves, carriedForwardDays);
    }

    /**
     * Paid-leave allowance for one quarter: scales {@code leavesPerFrequencyCount} to a quarter
     * using {@code leavesFrequency}. Falls back to {@link QuarterLeavePolicy#MAX_PERMISSIBLE_LEAVE}.
     */
    public int resolveMaxLeaves(String projectId, Optional<LeavePolicyResponse> leavePolicy) {
        Integer count = leavePolicy.map(LeavePolicyResponse::leavesPerFrequencyCount).orElse(null);
        if (count != null) {
            return leavesForQuarter(count, leavePolicy.map(LeavePolicyResponse::leavesFrequency).orElse(null));
        }
        return QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE;
    }

    /** Scales a leave allowance expressed at {@code frequency} to a single quarter (3 months). */
    public int leavesForQuarter(int count, String frequency) {
        if (frequency == null) {
            return count;
        }
        return switch (frequency.trim().toUpperCase(Locale.ENGLISH)) {
            case "MONTHLY" -> count * 3;
            case "YEARLY", "ANNUALLY", "ANNUAL" -> Math.round(count / 4f);
            case "QUARTERLY" -> count;
            default -> count;
        };
    }

    /**
     * Unused permissible leave from the previous quarter. Includes both full-absent and half-day
     * dates from that quarter so the lapsed-leave figure is consistent with how this quarter is
     * computed.
     */
    private int resolveCarriedForwardDays(
            Long resourceId, int year, int quarter, LocalDate joiningDate, int maxLeaves,
            int cycleDay) {
        int prevQuarter = quarter == 1 ? 4 : quarter - 1;
        int prevYear = quarter == 1 ? year - 1 : year;
        LocalDate prevStart = quarterStart(prevYear, prevQuarter, cycleDay);
        LocalDate prevEnd = quarterEnd(prevYear, prevQuarter, cycleDay);
        if (joiningDate != null && joiningDate.isAfter(prevEnd)) {
            return 0;
        }

        Set<LocalDate> prevHolidays = holidaysBetween(prevStart, prevEnd);
        List<Attendance> prevAttendance =
                attendanceRepository.findByResourceIdAndAttendanceDateBetween(resourceId, prevStart, prevEnd);

        Set<LocalDate> prevAbsentDates = prevAttendance.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .map(Attendance::getAttendanceDate)
                .collect(Collectors.toSet());

        Set<LocalDate> prevHalfDayDates = prevAttendance.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.HD)
                .map(Attendance::getAttendanceDate)
                .collect(Collectors.toSet());

        QuarterLeaveCalculation prevCalc = policy.compute(
                prevStart, prevEnd, joiningDate,
                prevAbsentDates, prevHalfDayDates,
                prevHolidays, maxLeaves, 0);

        return (int) Math.round(prevCalc.lapsedLeaveDays());
    }

    private Set<LocalDate> holidaysBetween(LocalDate start, LocalDate end) {
        return holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end).stream()
                .map(PublicHoliday::getHolidayDate)
                .collect(Collectors.toSet());
    }


}
