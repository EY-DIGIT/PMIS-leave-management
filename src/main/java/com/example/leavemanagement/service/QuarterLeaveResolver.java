package com.example.leavemanagement.service;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for turning a resource's absences over an <b>activity window</b> into a
 * paid/unpaid {@link QuarterLeaveCalculation} under UIDAI leave policy 5.24.1. The window is the
 * activity's execution period {@code [startDate, endDate]} (or any arbitrary date range) — not a
 * fixed calendar quarter.
 *
 * <p>The permissible leave quota is the project's quarterly allowance (default 6) prorated linearly
 * by the window's duration in months ({@link #activityLeaveQuota}). The quota is shared across the
 * chain of resources that held the same designation within the window (replacement inheritance).
 *
 * <p>Full absent days (status=A) count as 1.0 leave day; half-day attendance (status=HD) counts as
 * 0.5. Both are passed to {@link QuarterLeavePolicy} which allocates them chronologically.
 */
@Component
public class QuarterLeaveResolver {

    private final AttendanceRepository attendanceRepository;
    private final PublicHolidayRepository holidayRepository;
    private final LeavePolicyClient leavePolicyClient;
    private final QuarterLeavePolicy policy;
    private final ProjectResourceRepository projectResourceRepository;

    public QuarterLeaveResolver(
            AttendanceRepository attendanceRepository,
            PublicHolidayRepository holidayRepository,
            LeavePolicyClient leavePolicyClient,
            QuarterLeavePolicy policy,
            ProjectResourceRepository projectResourceRepository) {
        this.attendanceRepository = attendanceRepository;
        this.holidayRepository = holidayRepository;
        this.leavePolicyClient = leavePolicyClient;
        this.policy = policy;
        this.projectResourceRepository = projectResourceRepository;
    }

    /**
     * Permissible paid-leave quota for a window of {@code durationMonths} months: the project's
     * quarterly allowance scaled linearly (6 per quarter → 2 per month). e.g. 3.0 mo → 6, 1.5 mo → 3,
     * 2.0 mo → 4.
     */
    public double activityLeaveQuota(String projectId, double durationMonths) {
        Optional<LeavePolicyResponse> leavePolicy =
                projectId == null ? Optional.empty() : leavePolicyClient.getLeavePolicy(projectId);
        int quarterlyAllowance = resolveMaxLeaves(projectId, leavePolicy);
        return Math.round(quarterlyAllowance * durationMonths / 3.0);
    }

    /**
     * Computes the paid/unpaid leave breakdown for one resource over an activity window
     * {@code [windowStart, windowEnd]}. The {@code permissibleQuota} (see {@link #activityLeaveQuota})
     * is shared with any predecessor resources of the same designation whose assignments overlap the
     * window — their already-consumed paid leave is deducted first (replacement inheritance).
     */
    public QuarterLeaveCalculation calculateForWindow(
            String resId,
            Long resourceId,
            String projectId,
            String organisationId,
            LocalDate joiningDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            double permissibleQuota,
            Set<LocalDate> absentDates,
            Set<LocalDate> halfDayDates) {
        Set<LocalDate> holidays = holidaysBetween(windowStart, windowEnd);

        List<ProjectResource> predecessorChain =
                (resId != null && projectId != null)
                        ? findPredecessorChain(resId, projectId, windowStart, windowEnd)
                        : List.of();
        double effectiveQuota = predecessorChain.isEmpty()
                ? permissibleQuota
                : computeRemainingFromChain(predecessorChain, windowStart, windowEnd, holidays, permissibleQuota);

        return policy.compute(
                windowStart, windowEnd, joiningDate,
                absentDates, halfDayDates,
                holidays, effectiveQuota, 0);
    }

    /**
     * The project's paid-leave allowance for one quarter (3 months): scales
     * {@code leavesPerFrequencyCount} by {@code leavesFrequency}. Falls back to
     * {@link QuarterLeavePolicy#MAX_PERMISSIBLE_LEAVE} (6) when no policy is configured.
     */
    public int resolveMaxLeaves(String projectId, Optional<LeavePolicyResponse> leavePolicy) {
        Integer count = leavePolicy.map(LeavePolicyResponse::leavesPerFrequencyCount).orElse(null);
        if (count != null) {
            return leavesForQuarter(count, leavePolicy.map(LeavePolicyResponse::leavesFrequency).orElse(null));
        }
        return QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE;
    }

    private int leavesForQuarter(int count, String frequency) {
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

    private List<ProjectResource> findPredecessorChain(
            String resId, String projectId, LocalDate windowStart, LocalDate windowEnd) {
        List<ProjectResource> chain = new ArrayList<>();
        String lookup = resId;
        Set<String> visited = new HashSet<>();
        visited.add(lookup);
        while (true) {
            Optional<ProjectResource> pred =
                    projectResourceRepository.findByReplacedByResIdAndProjectId(lookup, projectId);
            if (pred.isEmpty()) break;
            ProjectResource pr = pred.get();
            LocalDate start = pr.getAssignmentStartDate();
            LocalDate end = pr.getAssignmentEndDate();
            boolean overlapsWindow = !start.isAfter(windowEnd)
                    && (end == null || !end.isBefore(windowStart));
            if (!overlapsWindow) break;
            String predResId = pr.getResource().getResId();
            if (!visited.add(predResId)) break;
            chain.add(pr);
            lookup = predResId;
        }
        Collections.reverse(chain);
        return chain;
    }

    private double computeRemainingFromChain(
            List<ProjectResource> chain, LocalDate windowStart, LocalDate windowEnd,
            Set<LocalDate> holidays, double maxLeaves) {
        double remaining = maxLeaves;
        for (ProjectResource pr : chain) {
            List<Attendance> predAttendance = attendanceRepository
                    .findByResourceIdAndAttendanceDateBetween(
                            pr.getResource().getId(), windowStart, windowEnd);
            Set<LocalDate> predAbsent = predAttendance.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.A)
                    .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
            Set<LocalDate> predHalfDays = predAttendance.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.HD)
                    .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
            QuarterLeaveCalculation predCalc = policy.compute(
                    windowStart, windowEnd, pr.getAssignmentStartDate(),
                    predAbsent, predHalfDays, holidays, remaining, 0);
            remaining = Math.max(0.0, remaining - predCalc.paidLeaveDays());
        }
        return remaining;
    }

    private Set<LocalDate> holidaysBetween(LocalDate start, LocalDate end) {
        return holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end).stream()
                .map(PublicHoliday::getHolidayDate)
                .collect(Collectors.toSet());
    }
}
