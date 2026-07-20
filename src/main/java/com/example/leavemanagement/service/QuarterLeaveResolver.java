package com.example.leavemanagement.service;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for turning a resource's quarter of absences into a paid/unpaid {@link
 * QuarterLeaveCalculation} under UIDAI leave policy 5.24.1. Both the attendance settlement report
 * and the employee leave-detail report delegate here so the permissible-leave allowance
 * (frequency scaling), carry-forward lookback and {@link QuarterLeavePolicy} invocation are
 * computed identically.
 */
@Component
public class QuarterLeaveResolver {

    private final AttendanceRepository attendanceRepository;
    private final PublicHolidayRepository holidayRepository;
    private final LeavePolicyClient leavePolicyClient;
    private final QuarterLeavePolicy policy;

    public QuarterLeaveResolver(
            AttendanceRepository attendanceRepository,
            PublicHolidayRepository holidayRepository,
            LeavePolicyClient leavePolicyClient,
            QuarterLeavePolicy policy) {
        this.attendanceRepository = attendanceRepository;
        this.holidayRepository = holidayRepository;
        this.leavePolicyClient = leavePolicyClient;
        this.policy = policy;
    }

    /**
     * Computes the quarter's paid/unpaid leave breakdown for one resource.
     *
     * @param resourceId the {@link com.example.leavemanagement.entity.MasterResource} id, or
     *     {@code null} when the resource has no record (carry-forward lookback is then skipped)
     * @param projectId the resource's project, or {@code null} (no policy/carry-forward applies)
     * @param joiningDate the resource's effective assignment start (for pro-rating), or {@code null}
     * @param year settlement year
     * @param quarter settlement quarter (1-4)
     * @param absentDates the dates the resource was marked absent (A) within the quarter
     */
    public QuarterLeaveCalculation calculate(
            Long resourceId,
            String projectId,
            LocalDate joiningDate,
            int year,
            int quarter,
            Set<LocalDate> absentDates) {
        LocalDate quarterStart = quarterStart(year, quarter);
        LocalDate quarterEnd = quarterEnd(year, quarter);
        Set<LocalDate> holidays = holidaysBetween(quarterStart, quarterEnd);

        Optional<LeavePolicyResponse> leavePolicy =
                projectId == null ? Optional.empty() : leavePolicyClient.getLeavePolicy(projectId);
        int maxLeaves = resolveMaxLeaves(projectId, leavePolicy);
        boolean carryForwardAllowed = leavePolicy.map(LeavePolicyResponse::carryForwardAllowed).orElse(Boolean.FALSE);
        int carriedForwardDays = (resourceId != null && carryForwardAllowed)
                ? resolveCarriedForwardDays(resourceId, year, quarter, joiningDate, maxLeaves)
                : 0;

        return policy.compute(
                quarterStart, quarterEnd, joiningDate, absentDates, holidays, maxLeaves, carriedForwardDays);
    }

    /**
     * Paid-leave allowance for one quarter: takes the leave policy's {@code leavesPerFrequencyCount}
     * and scales it to a quarter using {@code leavesFrequency}. Falls back to {@link ProjectConfig},
     * then to {@link QuarterLeavePolicy#MAX_PERMISSIBLE_LEAVE}.
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
            return count; // no frequency given — treat the count as already per-quarter
        }
        return switch (frequency.trim().toUpperCase(Locale.ENGLISH)) {
            case "MONTHLY" -> count * 3; // 3 months in a quarter
            case "YEARLY", "ANNUALLY", "ANNUAL" -> Math.round(count / 4f); // 4 quarters in a year
            case "QUARTERLY" -> count;
            default -> count; // unknown frequency — treat as per-quarter
        };
    }

    /**
     * Unused permissible leave from the previous quarter, to add into this quarter's allowance.
     * Only one quarter of lookback: a prior quarter's own carry-in isn't chained further back.
     */
    private int resolveCarriedForwardDays(
            Long resourceId, int year, int quarter, LocalDate joiningDate, int maxLeaves) {
        int prevQuarter = quarter == 1 ? 4 : quarter - 1;
        int prevYear = quarter == 1 ? year - 1 : year;
        LocalDate prevStart = quarterStart(prevYear, prevQuarter);
        LocalDate prevEnd = quarterEnd(prevYear, prevQuarter);
        if (joiningDate != null && joiningDate.isAfter(prevEnd)) {
            return 0; // resource didn't exist yet in the previous quarter
        }

        Set<LocalDate> prevHolidays = holidaysBetween(prevStart, prevEnd);
        Set<LocalDate> prevAbsentDates =
                attendanceRepository.findByResourceIdAndAttendanceDateBetween(resourceId, prevStart, prevEnd).stream()
                        .filter(a -> a.getStatus() == AttendanceStatus.A)
                        .map(Attendance::getAttendanceDate)
                        .collect(Collectors.toSet());

        QuarterLeaveCalculation prevCalc =
                policy.compute(prevStart, prevEnd, joiningDate, prevAbsentDates, prevHolidays, maxLeaves);
        return prevCalc.lapsedLeaveDays();
    }

    private Set<LocalDate> holidaysBetween(LocalDate start, LocalDate end) {
        return holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end).stream()
                .map(PublicHoliday::getHolidayDate)
                .collect(Collectors.toSet());
    }

    private LocalDate quarterStart(int year, int quarter) {
        return LocalDate.of(year, quarter * 3 - 2, 1);
    }

    private LocalDate quarterEnd(int year, int quarter) {
        LocalDate firstOfLastMonth = LocalDate.of(year, quarter * 3, 1);
        return firstOfLastMonth.withDayOfMonth(firstOfLastMonth.lengthOfMonth());
    }
}
