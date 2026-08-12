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
            String designation,
            LocalDate joiningDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            double permissibleQuota,
            Set<LocalDate> absentDates,
            Set<LocalDate> halfDayDates) {
        Set<LocalDate> holidays = holidaysBetween(windowStart, windowEnd);

        List<ProjectResource> predecessorChain =
                (resId != null && projectId != null)
                        ? findPredecessorChain(resId, projectId, designation, joiningDate, windowStart, windowEnd)
                        : List.of();

        if (predecessorChain.isEmpty()) {
            // First/only resource of the designation: prorate the pool by this resource's own join date.
            return policy.compute(
                    windowStart, windowEnd, joiningDate,
                    absentDates, halfDayDates,
                    holidays, permissibleQuota, 0);
        }

        // Replacement: the permissible pool belongs to the Activity + Designation. Prorate it ONCE by the
        // designation's first-active date (chain head), consume the predecessors' paid leave, and hand the
        // remainder to this resource with no re-proration — a replacement never gets a fresh entitlement.
        LocalDate headStart = predecessorChain.get(0).getAssignmentStartDate();
        double sharedPermissible = policy.compute(
                windowStart, windowEnd, headStart,
                Set.of(), Set.of(), holidays, permissibleQuota, 0).permissibleLeave();
        double remaining = computeRemainingFromChain(predecessorChain, windowStart, windowEnd, holidays, sharedPermissible);

        // The replacement's OWN leave is counted only from its joining date onward — never inherit the
        // predecessor's leave dates/window. Clip the calc window to the join date so any attendance before
        // the resource joined is excluded (paid/unpaid/sandwich).
        LocalDate effectiveStart = (joiningDate != null && joiningDate.isAfter(windowStart)) ? joiningDate : windowStart;
        return policy.compute(
                effectiveStart, windowEnd, null,
                absentDates, halfDayDates,
                holidays, remaining, 0);
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

    /**
     * The chain of predecessor resources of the same designation that a replacement inherits its leave
     * balance from — inferred from the <b>resource lifecycle</b>, not an explicit link. A predecessor is
     * a resource on the same project with the same designation (role) whose assignment overlaps the leave
     * window and whose Last Working Date (assignmentEndDate) falls strictly before the current resource's
     * Joining Date (assignmentStartDate); the closest such departure (max end date, any gap) is chained.
     * Returned oldest-first.
     */
    private List<ProjectResource> findPredecessorChain(
            String resId, String projectId, String designation, LocalDate joiningDate,
            LocalDate windowStart, LocalDate windowEnd) {
        if (designation == null || joiningDate == null) {
            return List.of();
        }

        List<ProjectResource> candidates = projectResourceRepository
                .findByProjectIdAndRole(projectId, designation).stream()
                .filter(pr -> pr.getAssignmentStartDate() != null
                        && !pr.getAssignmentStartDate().isAfter(windowEnd)
                        && (pr.getAssignmentEndDate() == null || !pr.getAssignmentEndDate().isBefore(windowStart)))
                .toList();

        List<ProjectResource> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(resId);
        LocalDate joinDateCursor = joiningDate;
        while (true) {
            LocalDate joinDate = joinDateCursor;
            ProjectResource predecessor = null;
            for (ProjectResource cand : candidates) {
                LocalDate end = cand.getAssignmentEndDate();
                if (end == null || joinDate == null || !end.isBefore(joinDate)) {
                    continue; // still active, or did not leave before this resource joined
                }
                if (visited.contains(cand.getResource().getResId())) {
                    continue;
                }
                if (predecessor == null || end.isAfter(predecessor.getAssignmentEndDate())) {
                    predecessor = cand; // closest earlier departure
                }
            }
            if (predecessor == null) break;
            visited.add(predecessor.getResource().getResId());
            chain.add(predecessor);
            joinDateCursor = predecessor.getAssignmentStartDate();
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
                    windowStart, windowEnd, null,
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
