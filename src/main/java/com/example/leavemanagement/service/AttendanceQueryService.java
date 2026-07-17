package com.example.leavemanagement.service;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.AttendanceReportSummary;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.ResourceCostSummary;
import com.example.leavemanagement.dto.ResourceQuarterSettlement;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.LeaveRelaxation;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectConfig;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.AttendanceValidationException;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.LeaveRelaxationRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Persists attendance (one row per resource per working day) and answers questions over it: the
 * report dashboards (monthly/employee/quarterly/yearly) and the payroll-grade quarterly
 * leave-policy settlement (UIDAI 5.24.1).
 *
 * <p>Weekends and public holidays are never persisted per resource — they're the same for
 * everyone on a given day, so they're derived at report time from the day-of-week and {@link
 * PublicHoliday} instead of being duplicated into every resource's row set.
 */
@Service
public class AttendanceQueryService {

    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final int DEFAULT_FULL_DAY_MINUTES = 8 * 60;
    private static final int DEFAULT_HALF_DAY_MINUTES = 4 * 60;

    private final AttendanceExcelParser parser;
    private final AttendanceRepository attendanceRepository;
    private final PublicHolidayRepository holidayRepository;
    private final MasterResourceRepository masterResourceRepository;
    private final ProjectResourceRepository projectResourceRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final LeavePolicyClient leavePolicyClient;
    private final LeaveRelaxationRepository leaveRelaxationRepository;
    private final QuarterLeaveResolver quarterLeaveResolver;

    public AttendanceQueryService(
            AttendanceExcelParser parser,
            AttendanceRepository attendanceRepository,
            PublicHolidayRepository holidayRepository,
            MasterResourceRepository masterResourceRepository,
            ProjectResourceRepository projectResourceRepository,
            ProjectConfigRepository projectConfigRepository,
            LeavePolicyClient leavePolicyClient,
            LeaveRelaxationRepository leaveRelaxationRepository,
            QuarterLeaveResolver quarterLeaveResolver) {
        this.parser = parser;
        this.attendanceRepository = attendanceRepository;
        this.holidayRepository = holidayRepository;
        this.masterResourceRepository = masterResourceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.leavePolicyClient = leavePolicyClient;
        this.leaveRelaxationRepository = leaveRelaxationRepository;
        this.quarterLeaveResolver = quarterLeaveResolver;
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    /**
     * Parses an attendance sheet for the given Attendance Start Date / Attendance End Date period
     * and replaces each uploaded resource's rows in that range. Weekday, non-holiday days are
     * classified P/HD (worked, per the project's full/half-day minute thresholds) or A (no punch
     * data — there's no leave-request source yet, so an unexplained absence is Absent, not Leave).
     * Weekends and public holidays are not persisted — see the class doc.
     */
    @Transactional
    public AttendanceUploadResult upload(
            String projectId,
            String milestoneId,
            LocalDate startDate,
            LocalDate endDate,
            String rateYear,
            MultipartFile file) {
        validatePeriod(startDate, endDate);
        List<EmployeeAttendanceByDate> parsed = parser.parse(file, startDate, endDate);
        Map<String, LeavePolicyResponse> leavePolicies =
                validateResourcesAndFetchLeavePolicies(attendanceIds(parsed), projectId);

        int[] thresholds = resolveThresholds(projectId, leavePolicies.get(projectId));
        Set<LocalDate> holidays = holidaysBetween(startDate, endDate);

        int stored = 0;
        for (EmployeeAttendanceByDate employee : parsed) {
            MasterResource resource = masterResourceRepository.findByResId(employee.attendanceId()).orElseThrow();
            attendanceRepository.deleteByResourceIdAndAttendanceDateBetween(resource.getId(), startDate, endDate);
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (isWeekend(date) || holidays.contains(date)) {
                    continue; // derived at report time, not persisted
                }
                Integer workedMinutes = employee.workedMinutesByDate().get(date);
                Attendance row;
                if (workedMinutes != null && workedMinutes > 0) {
                    AttendanceStatus status = workedMinutes >= thresholds[0] ? AttendanceStatus.P : AttendanceStatus.HD;
                    row = new Attendance(resource, projectId, milestoneId, date, status);
                    row.setWorkingHours(Math.round(workedMinutes / 60.0 * 100) / 100.0);
                } else {
                    row = new Attendance(resource, projectId, milestoneId, date, AttendanceStatus.A);
                }
                attendanceRepository.save(row);
            }
            stored++;
        }
        applyRateYear(parsed, projectId, rateYear);
        return new AttendanceUploadResult(startDate, endDate, parsed.size(), stored, leavePolicies);
    }

    private List<String> attendanceIds(List<EmployeeAttendanceByDate> parsed) {
        return parsed.stream().map(EmployeeAttendanceByDate::attendanceId).toList();
    }

    /** Validates Attendance Start Date <= Attendance End Date. */
    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new BadRequestException("Attendance Start Date and Attendance End Date are required.");
        }
        if (startDate.isAfter(endDate)) {
            throw new BadRequestException("Attendance Start Date cannot be greater than Attendance End Date.");
        }
    }

    /**
     * Full/half-day minute thresholds: prefers the real leave policy's {@code fullDay}/{@code
     * halfDay} (hours), falls back to the (largely unmaintained) {@link ProjectConfig} table, then
     * to an 8h/4h default.
     */
    private int[] resolveThresholds(String projectId, LeavePolicyResponse leavePolicy) {
        if (leavePolicy != null && leavePolicy.fullDay() != null && leavePolicy.halfDay() != null) {
            return new int[] {leavePolicy.fullDay() * 60, leavePolicy.halfDay() * 60};
        }
        return projectConfigRepository
                .findById(projectId)
                .map(c -> new int[] {c.getFullDayMinutes(), c.getHalfDayMinutes()})
                .orElse(new int[] {DEFAULT_FULL_DAY_MINUTES, DEFAULT_HALF_DAY_MINUTES});
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private Set<LocalDate> holidaysBetween(LocalDate start, LocalDate end) {
        return holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end).stream()
                .map(PublicHoliday::getHolidayDate)
                .collect(Collectors.toSet());
    }

    /**
     * For every row in the uploaded sheet, checks that its Attendance ID exists as an active
     * {@link MasterResource} belonging to the upload's {@code projectId}, then fetches that
     * project's leave policy from the external leave-policy API. If any row fails validation (or
     * the policy fetch fails), the whole upload is rejected with every collected error and nothing
     * is persisted.
     */
    private Map<String, LeavePolicyResponse> validateResourcesAndFetchLeavePolicies(
            List<String> attendanceIds, String projectId) {
        List<String> errors = new ArrayList<>();

        for (String attendanceId : attendanceIds) {
            Optional<MasterResource> resource = masterResourceRepository.findByResId(attendanceId);
            if (resource.isEmpty()) {
                errors.add("Resource " + attendanceId + " does not exist.");
                continue;
            }
            Optional<ProjectResource> assignment =
                    projectResourceRepository.findByResourceIdAndActiveTrue(resource.get().getId());
            if (assignment.isEmpty()) {
                errors.add("Resource " + attendanceId + " is inactive.");
                continue;
            }
            String actualProjectId = assignment.get().getProjectId();
            if (!actualProjectId.equals(projectId)) {
                errors.add("Resource " + attendanceId + " belongs to project " + actualProjectId
                        + ", not " + projectId + ".");
            }
        }

        Map<String, LeavePolicyResponse> leavePoliciesByProject = new LinkedHashMap<>();
        if (errors.isEmpty()) {
            leavePolicyClient
                    .getLeavePolicy(projectId)
                    .ifPresentOrElse(
                            policy -> leavePoliciesByProject.put(projectId, policy),
                            () -> errors.add("Could not fetch leave policy for project " + projectId + "."));
        }

        if (!errors.isEmpty()) {
            throw new AttendanceValidationException(errors);
        }
        return leavePoliciesByProject;
    }

    /**
     * Applies the upload's Rate_Year (e.g. "Year-1") to every uploaded resource's active
     * assignment on this project. A blank/absent rateYear leaves assignments untouched — it
     * doesn't have to be given on every upload.
     */
    private void applyRateYear(List<EmployeeAttendanceByDate> parsed, String projectId, String rateYear) {
        if (rateYear == null || rateYear.isBlank()) {
            return;
        }
        for (EmployeeAttendanceByDate employee : parsed) {
            projectResourceRepository
                    .findByResource_ResIdAndProjectIdAndActiveTrue(employee.attendanceId(), projectId)
                    .ifPresent(assignment -> {
                        assignment.setRateYear(rateYear);
                        projectResourceRepository.save(assignment);
                    });
        }
    }

    // ------------------------------------------------------------------
    // Reports
    // ------------------------------------------------------------------

    /** Dashboard: one summary per resource currently active on the project, for one month. */
    @Transactional(readOnly = true)
    public List<AttendanceReportSummary> monthlyReport(String projectId, int year, int month) {
        validateMonthAndYear(year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return projectDashboard(projectId, start, end, start.format(MONTH_YEAR));
    }

    /** One resource's summary for one month. */
    @Transactional(readOnly = true)
    public AttendanceReportSummary employeeReport(String resourceId, int year, int month) {
        validateMonthAndYear(year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return employeeSummary(resourceId, start, end, start.format(MONTH_YEAR));
    }

    /**
     * Quarterly report: pass {@code resourceId} for a single resource's summary, or {@code
     * projectId} for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public List<AttendanceReportSummary> quarterlyReport(String projectId, String resourceId, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }
        List<Integer> months = List.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
        LocalDate start = LocalDate.of(year, months.get(0), 1);
        LocalDate end = LocalDate.of(year, months.get(2), 1)
                .withDayOfMonth(LocalDate.of(year, months.get(2), 1).lengthOfMonth());
        return scopedReport(projectId, resourceId, start, end, "Q" + quarter + " " + year);
    }

    /**
     * Yearly report: pass {@code resourceId} for a single resource's summary, or {@code
     * projectId} for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public List<AttendanceReportSummary> yearlyReport(String projectId, String resourceId, int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        return scopedReport(projectId, resourceId, start, end, String.valueOf(year));
    }

    private List<AttendanceReportSummary> scopedReport(
            String projectId, String resourceId, LocalDate start, LocalDate end, String periodLabel) {
        if (resourceId != null && !resourceId.isBlank()) {
            return List.of(employeeSummary(resourceId, start, end, periodLabel));
        }
        if (projectId == null || projectId.isBlank()) {
            throw new BadRequestException("projectId or resourceId is required");
        }
        return projectDashboard(projectId, start, end, periodLabel);
    }

    private List<AttendanceReportSummary> projectDashboard(
            String projectId, LocalDate start, LocalDate end, String periodLabel) {
        return projectResourceRepository.findByProjectIdAndActiveTrue(projectId).stream()
                .map(ProjectResource::getResource)
                .map(resource -> buildSummary(resource, projectId, start, end, periodLabel))
                .toList();
    }

    private AttendanceReportSummary employeeSummary(
            String resourceId, LocalDate start, LocalDate end, String periodLabel) {
        MasterResource resource = masterResourceRepository
                .findByResId(resourceId)
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + resourceId));
        String projectId = projectResourceRepository
                .findByResourceIdAndActiveTrue(resource.getId())
                .map(ProjectResource::getProjectId)
                .orElse(null);
        return buildSummary(resource, projectId, start, end, periodLabel);
    }

    private AttendanceReportSummary buildSummary(
            MasterResource resource, String projectId, LocalDate start, LocalDate end, String periodLabel) {
        int totalDays = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        Set<LocalDate> holidays = holidaysBetween(start, end);
        int weekOffDays = 0;
        int holidayDays = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (isWeekend(date)) {
                weekOffDays++;
            } else if (holidays.contains(date)) {
                holidayDays++;
            }
        }
        int workingDays = totalDays - weekOffDays - holidayDays;

        Map<AttendanceStatus, Long> counts =
                attendanceRepository.findByResourceIdAndAttendanceDateBetween(resource.getId(), start, end).stream()
                        .collect(Collectors.groupingBy(Attendance::getStatus, Collectors.counting()));

        int presentDays = counts.getOrDefault(AttendanceStatus.P, 0L).intValue();
        int halfDays = counts.getOrDefault(AttendanceStatus.HD, 0L).intValue();
        int leaveDays = counts.getOrDefault(AttendanceStatus.L, 0L).intValue();
        int absentDays = counts.getOrDefault(AttendanceStatus.A, 0L).intValue();
        int wfhDays = counts.getOrDefault(AttendanceStatus.WFH, 0L).intValue();
        double attendancePercentage =
                workingDays > 0 ? Math.round(presentDays * 10000.0 / workingDays) / 100.0 : 0d;

        return new AttendanceReportSummary(
                resource.getResId(),
                resource.getName(),
                projectId,
                periodLabel,
                workingDays,
                presentDays,
                halfDays,
                leaveDays,
                absentDays,
                weekOffDays,
                holidayDays,
                wfhDays,
                attendancePercentage);
    }

    private void validateMonthAndYear(int year, int month) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("month must be between 1 and 12");
        }
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
    }

    // ------------------------------------------------------------------
    // Resource cost calculator: monthlyRate (from the resource's active assignment's rate card,
    // keyed by its current rateYear) x attendance ratio for the period. Quarterly/yearly totals
    // are the sum of each covered month's cost computed separately, not a single ratio blended
    // over the whole period — so a rate-year change or a month with no attendance is reflected
    // correctly.
    // ------------------------------------------------------------------

    /** Dashboard: one month's cost per resource currently active on the project. */
    @Transactional(readOnly = true)
    public List<MonthlyResourceCost> monthlyCostReport(String projectId, int year, int month) {
        validateMonthAndYear(year, month);
        LocalDate monthStart = LocalDate.of(year, month, 1);
        String periodLabel = monthStart.format(MONTH_YEAR);
        return projectResourceRepository.findByProjectIdAndActiveTrue(projectId).stream()
                .map(ProjectResource::getResource)
                .map(resource -> buildMonthlyCost(resource, projectId, monthStart, periodLabel))
                .toList();
    }

    /** One resource's cost for one month. */
    @Transactional(readOnly = true)
    public MonthlyResourceCost employeeMonthlyCost(String resourceId, int year, int month) {
        validateMonthAndYear(year, month);
        MasterResource resource = masterResourceRepository
                .findByResId(resourceId)
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + resourceId));
        String projectId = projectResourceRepository
                .findByResourceIdAndActiveTrue(resource.getId())
                .map(ProjectResource::getProjectId)
                .orElse(null);
        LocalDate monthStart = LocalDate.of(year, month, 1);
        return buildMonthlyCost(resource, projectId, monthStart, monthStart.format(MONTH_YEAR));
    }

    /**
     * Quarterly cost: pass {@code resourceId} for a single resource's summary, or {@code
     * projectId} for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public List<ResourceCostSummary> quarterlyCostReport(String projectId, String resourceId, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }
        List<Integer> months = List.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
        return costSummaryReport(projectId, resourceId, year, months, "Q" + quarter + " " + year);
    }

    /**
     * Yearly cost: pass {@code resourceId} for a single resource's summary, or {@code projectId}
     * for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public List<ResourceCostSummary> yearlyCostReport(String projectId, String resourceId, int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
        List<Integer> months = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        return costSummaryReport(projectId, resourceId, year, months, String.valueOf(year));
    }

    private List<ResourceCostSummary> costSummaryReport(
            String projectId, String resourceId, int year, List<Integer> months, String periodLabel) {
        if (resourceId != null && !resourceId.isBlank()) {
            MasterResource resource = masterResourceRepository
                    .findByResId(resourceId)
                    .orElseThrow(() -> new NotFoundException("No resource with res_id " + resourceId));
            String resolvedProjectId = projectId != null && !projectId.isBlank()
                    ? projectId
                    : projectResourceRepository
                            .findByResourceIdAndActiveTrue(resource.getId())
                            .map(ProjectResource::getProjectId)
                            .orElse(null);
            return List.of(buildCostSummary(resource, resolvedProjectId, year, months, periodLabel));
        }
        if (projectId == null || projectId.isBlank()) {
            throw new BadRequestException("projectId or resourceId is required");
        }
        return projectResourceRepository.findByProjectIdAndActiveTrue(projectId).stream()
                .map(ProjectResource::getResource)
                .map(resource -> buildCostSummary(resource, projectId, year, months, periodLabel))
                .toList();
    }

    private ResourceCostSummary buildCostSummary(
            MasterResource resource, String projectId, int year, List<Integer> months, String periodLabel) {
        List<MonthlyResourceCost> monthly = months.stream()
                .map(month -> {
                    LocalDate monthStart = LocalDate.of(year, month, 1);
                    return buildMonthlyCost(resource, projectId, monthStart, monthStart.format(MONTH_YEAR));
                })
                .toList();
        double totalCost = round2(monthly.stream().mapToDouble(MonthlyResourceCost::cost).sum());
        return new ResourceCostSummary(resource.getResId(), resource.getName(), projectId, periodLabel, totalCost, monthly);
    }

    private MonthlyResourceCost buildMonthlyCost(
            MasterResource resource, String projectId, LocalDate monthStart, String periodLabel) {
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        AttendanceReportSummary attendance = buildSummary(resource, projectId, monthStart, monthEnd, periodLabel);

        ProjectResource assignment = projectId == null
                ? null
                : projectResourceRepository
                        .findByResource_ResIdAndProjectIdAndActiveTrue(resource.getResId(), projectId)
                        .orElse(null);
        String rateYear = assignment != null ? assignment.getRateYear() : null;
        Double monthlyRate = (assignment != null && rateYear != null)
                ? assignment.getRateCardByYear().get(rateYear)
                : null;
        double rate = monthlyRate != null ? monthlyRate : 0d;

        int relaxationDaysApplied =
                relaxationDaysAppliedToMonth(resource, projectId, monthStart, attendance.absentDays());
        // Days actually paid for: full present days (P) + half-days (HD) at 0.5 each + any
        // relaxation-forgiven absent days, capped at the month's working days.
        double effectivePaidDays = Math.min(
                attendance.workingDays(),
                attendance.presentDays()
                        + attendance.halfDays() * 0.5
                        + relaxationDaysApplied);
        // Per-day unit price and derived breakup amounts (0 when there's no rate / no working days).
        double perDayRate = (monthlyRate != null && attendance.workingDays() > 0)
                ? round2(monthlyRate / attendance.workingDays())
                : 0d;
        double halfDayAmount = round2(perDayRate * attendance.halfDays() * 0.5);
        double cost = (monthlyRate != null && attendance.workingDays() > 0)
                ? round2(monthlyRate * effectivePaidDays / attendance.workingDays())
                : 0d;
        // Amount lost to unpaid days = full month rate minus what's actually payable.
        double deductedAmount = monthlyRate != null ? round2(rate - cost) : 0d;

        return new MonthlyResourceCost(
                resource.getResId(),
                resource.getName(),
                projectId,
                rateYear,
                periodLabel,
                attendance.workingDays(),
                attendance.presentDays(),
                attendance.halfDays(),
                attendance.absentDays(),
                relaxationDaysApplied,
                round2(effectivePaidDays),
                attendance.attendancePercentage(),
                rate,
                perDayRate,
                halfDayAmount,
                deductedAmount,
                cost);
    }

    /**
     * This month's share of its quarter's recorded {@link LeaveRelaxation#getRelaxationDays()},
     * via sequential fill: starting from the quarter's earliest month, each month absorbs
     * relaxation days up to its own absent-day count before any remainder spills into the next
     * month. Relaxation is recorded per quarter, not per day, so this is a deterministic way to
     * spread it across months such that monthly costs sum exactly to the quarterly total.
     */
    private int relaxationDaysAppliedToMonth(
            MasterResource resource, String projectId, LocalDate monthStart, int currentMonthAbsentDays) {
        if (projectId == null) {
            return 0;
        }
        int year = monthStart.getYear();
        int month = monthStart.getMonthValue();
        int quarter = (month - 1) / 3 + 1;

        LeaveRelaxation relaxation = leaveRelaxationRepository
                .findByResource_ResIdAndProjectIdAndYearAndQuarter(resource.getResId(), projectId, year, quarter)
                .orElse(null);
        if (relaxation == null || relaxation.getRelaxationDays() <= 0) {
            return 0;
        }

        int remaining = relaxation.getRelaxationDays();
        int firstMonthOfQuarter = quarter * 3 - 2;
        int lastMonthOfQuarter = quarter * 3;
        for (int m = firstMonthOfQuarter; m <= lastMonthOfQuarter; m++) {
            int absentThisMonth;
            if (m == month) {
                absentThisMonth = currentMonthAbsentDays;
            } else {
                LocalDate ms = LocalDate.of(year, m, 1);
                LocalDate me = ms.withDayOfMonth(ms.lengthOfMonth());
                absentThisMonth = countAbsentDays(resource.getId(), ms, me);
            }
            int applied = Math.min(remaining, absentThisMonth);
            if (m == month) {
                return applied;
            }
            remaining -= applied;
        }
        return 0;
    }

    private int countAbsentDays(Long resourceId, LocalDate start, LocalDate end) {
        return (int) attendanceRepository.findByResourceIdAndAttendanceDateBetween(resourceId, start, end).stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .count();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ------------------------------------------------------------------
    // Payroll: quarterly leave-policy settlement (UIDAI 5.24.1) — preserved separately from the
    // simple day-count reports above, since paid/unpaid/sandwich-leave charging is payroll-grade
    // logic, not just a count of status='L' rows.
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public QuarterLeaveReport quarterlySettlement(int year, int quarter) {
        return quarterlySettlement(year, quarter, null);
    }

    @Transactional(readOnly = true)
    public QuarterLeaveReport quarterlySettlement(int year, int quarter, String projectId) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }

        List<Integer> months = List.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
        LocalDate quarterStart = LocalDate.of(year, months.get(0), 1);
        LocalDate quarterEnd = LocalDate.of(year, months.get(2), 1)
                .withDayOfMonth(LocalDate.of(year, months.get(2), 1).lengthOfMonth());

        List<Attendance> rows = (projectId == null || projectId.isBlank())
                ? attendanceRepository.findByAttendanceDateBetween(quarterStart, quarterEnd)
                : attendanceRepository.findByProjectIdAndAttendanceDateBetween(projectId, quarterStart, quarterEnd);
        Set<Integer> monthsWithData = rows.stream()
                .map(a -> a.getAttendanceDate().getMonthValue())
                .collect(Collectors.toCollection(HashSet::new));

        Map<Long, List<Attendance>> byResource =
                rows.stream().collect(Collectors.groupingBy(a -> a.getResource().getId()));

        List<ResourceQuarterSettlement> settlements = new ArrayList<>();
        for (List<Attendance> resourceRows : byResource.values()) {
            MasterResource resource = resourceRows.get(0).getResource();
            String resourceProjectId = resourceRows.get(0).getProjectId();

            Set<LocalDate> absentDates = resourceRows.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.A)
                    .map(Attendance::getAttendanceDate)
                    .collect(Collectors.toSet());

            LocalDate joiningDate = projectResourceRepository
                    .findByResourceIdAndActiveTrue(resource.getId())
                    .map(ProjectResource::getAssignmentStartDate)
                    .orElse(resource.getDateOfJoining());

            QuarterLeaveCalculation calculation = quarterLeaveResolver.calculate(
                    resource.getId(), resourceProjectId, joiningDate, year, quarter, absentDates);

            settlements.add(
                    new ResourceQuarterSettlement(resource.getResId(), resource.getName(), joiningDate, calculation));
        }
        settlements.sort(Comparator.comparing(ResourceQuarterSettlement::attendanceId));

        return new QuarterLeaveReport(
                year,
                quarter,
                quarterStart,
                quarterEnd,
                monthsWithData.stream().sorted().toList(),
                settlements.size(),
                settlements);
    }
}
