package com.example.leavemanagement.service;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.AttendanceReportResult;
import com.example.leavemanagement.dto.AttendanceReportSummary;
import com.example.leavemanagement.dto.AttendanceReportTotals;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.ResourceCostResult;
import com.example.leavemanagement.dto.ResourceCostSummary;
import com.example.leavemanagement.dto.ResourceCostTotals;
import com.example.leavemanagement.dto.ResourceQuarterSettlement;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.LeaveRelaxation;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.AttendanceValidationException;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.LeaveRelaxationRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AttendanceQueryService.class);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final int DEFAULT_FULL_DAY_MINUTES = 8 * 60;
    private static final int DEFAULT_HALF_DAY_MINUTES = 4 * 60;

    private final AttendanceExcelParser parser;
    private final AttendanceRepository attendanceRepository;
    private final PublicHolidayRepository holidayRepository;
    private final MasterResourceRepository masterResourceRepository;
    private final ProjectResourceRepository projectResourceRepository;
    private final LeavePolicyClient leavePolicyClient;
    private final LeaveRelaxationRepository leaveRelaxationRepository;
    private final QuarterLeaveResolver quarterLeaveResolver;
    private final AttendancePeriodValidator periodValidator;

    public AttendanceQueryService(
            AttendanceExcelParser parser,
            AttendanceRepository attendanceRepository,
            PublicHolidayRepository holidayRepository,
            MasterResourceRepository masterResourceRepository,
            ProjectResourceRepository projectResourceRepository,
            LeavePolicyClient leavePolicyClient,
            LeaveRelaxationRepository leaveRelaxationRepository,
            QuarterLeaveResolver quarterLeaveResolver,
            AttendancePeriodValidator periodValidator) {
        this.parser = parser;
        this.attendanceRepository = attendanceRepository;
        this.holidayRepository = holidayRepository;
        this.masterResourceRepository = masterResourceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.leavePolicyClient = leavePolicyClient;
        this.leaveRelaxationRepository = leaveRelaxationRepository;
        this.quarterLeaveResolver = quarterLeaveResolver;
        this.periodValidator = periodValidator;
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
            String activityId,
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

        // Bulk-delete all existing rows for this project+period before inserting the new file's data.
        // A @Modifying JPQL query is used (not a derived delete) so the single DELETE SQL is flushed
        // to the DB immediately — preventing uk_attendance_resource_date violations on re-upload.
        attendanceRepository.bulkDeleteByProjectIdAndDateBetween(projectId, startDate, endDate);

        int stored = 0;
        for (EmployeeAttendanceByDate employee : parsed) {
            MasterResource resource = masterResourceRepository.findByResId(employee.attendanceId()).orElseThrow();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (isWeekend(date) || holidays.contains(date)) {
                    continue; // derived at report time, not persisted
                }
                Integer workedMinutes = employee.workedMinutesByDate().get(date);
                Attendance row;
                if (workedMinutes != null && workedMinutes > 0) {
                    AttendanceStatus status = workedMinutes >= thresholds[0] ? AttendanceStatus.P : AttendanceStatus.HD;
                    row = new Attendance(resource, projectId, milestoneId, activityId, date, status);
                    row.setWorkingHours(Math.round(workedMinutes / 60.0 * 100) / 100.0);
                } else {
                    row = new Attendance(resource, projectId, milestoneId, activityId, date, AttendanceStatus.A);
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

    /** Validates Attendance Start Date <= Attendance End Date and the period is not a future month. */
    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new BadRequestException("Attendance Start Date and Attendance End Date are required.");
        }
        if (startDate.isAfter(endDate)) {
            throw new BadRequestException("Attendance Start Date cannot be greater than Attendance End Date.");
        }
        LocalDate today = LocalDate.now();
        if (startDate.getYear() > today.getYear()
                || (startDate.getYear() == today.getYear()
                        && startDate.getMonthValue() > today.getMonthValue())) {
            throw new BadRequestException(
                    "Cannot upload attendance for a future month. Current month is "
                            + today.getMonth().getDisplayName(
                                    java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                            + " " + today.getYear() + ".");
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
        return new int[] {DEFAULT_FULL_DAY_MINUTES, DEFAULT_HALF_DAY_MINUTES};
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

        if (!errors.isEmpty()) {
            throw new AttendanceValidationException(errors);
        }

        Map<String, LeavePolicyResponse> leavePoliciesByProject = new LinkedHashMap<>();
        leavePolicyClient
                .getLeavePolicy(projectId)
                .ifPresentOrElse(
                        policy -> leavePoliciesByProject.put(projectId, policy),
                        () -> log.warn("Leave policy unavailable for project '{}', upload proceeds with default thresholds", projectId));
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
    public AttendanceReportResult monthlyReport(String projectId, int year, int month) {
        validateMonthAndYear(year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        String label = start.format(MONTH_YEAR);
        periodValidator.validate(start, end, projectId, null, label);
        return projectDashboard(projectId, start, end, label, 1);
    }

    /** One resource's summary for one month. */
    @Transactional(readOnly = true)
    public AttendanceReportResult employeeReport(String resourceId, int year, int month) {
        validateMonthAndYear(year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        String label = start.format(MONTH_YEAR);
        periodValidator.validate(start, end, null, resourceId, label);
        AttendanceReportSummary summary = employeeSummary(resourceId, start, end, label, 1);
        List<AttendanceReportSummary> rows = List.of(summary);
        return new AttendanceReportResult(summary.period(), 1, buildAttendanceTotals(rows), rows);
    }

    /**
     * Quarterly report: pass {@code resourceId} for a single resource's summary, or {@code
     * projectId} for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public AttendanceReportResult quarterlyReport(String projectId, String resourceId, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }
        List<Integer> months = List.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
        LocalDate start = LocalDate.of(year, months.get(0), 1);
        LocalDate end = LocalDate.of(year, months.get(2), 1)
                .withDayOfMonth(LocalDate.of(year, months.get(2), 1).lengthOfMonth());
        return scopedReport(projectId, resourceId, start, end, "Q" + quarter + " " + year, 3);
    }

    /**
     * Yearly report: pass {@code resourceId} for a single resource's summary, or {@code
     * projectId} for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public AttendanceReportResult yearlyReport(String projectId, String resourceId, int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        return scopedReport(projectId, resourceId, start, end, String.valueOf(year), 12);
    }

    private AttendanceReportResult scopedReport(
            String projectId, String resourceId, LocalDate start, LocalDate end,
            String periodLabel, int numberOfMonths) {
        periodValidator.validate(start, end, projectId, resourceId, periodLabel);
        if (resourceId != null && !resourceId.isBlank()) {
            AttendanceReportSummary summary =
                    employeeSummary(resourceId, start, end, periodLabel, numberOfMonths);
            List<AttendanceReportSummary> rows = List.of(summary);
            return new AttendanceReportResult(periodLabel, 1, buildAttendanceTotals(rows), rows);
        }
        if (projectId == null || projectId.isBlank()) {
            throw new BadRequestException("projectId or resourceId is required");
        }
        return projectDashboard(projectId, start, end, periodLabel, numberOfMonths);
    }

    private AttendanceReportResult projectDashboard(
            String projectId, LocalDate start, LocalDate end, String periodLabel, int numberOfMonths) {
        int leaveLimit = resolveLeaveLimit(projectId, numberOfMonths);
        List<AttendanceReportSummary> rows = projectResourceRepository.findByProjectIdAndActiveTrue(projectId).stream()
                .map(pr -> buildSummary(pr.getResource(), projectId, start, end, periodLabel, leaveLimit, pr.getRole()))
                .toList();
        return new AttendanceReportResult(periodLabel, rows.size(), buildAttendanceTotals(rows), rows);
    }

    private AttendanceReportSummary employeeSummary(
            String resourceId, LocalDate start, LocalDate end, String periodLabel, int numberOfMonths) {
        MasterResource resource = masterResourceRepository
                .findByResId(resourceId)
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + resourceId));
        ProjectResource assignment = projectResourceRepository
                .findByResourceIdAndActiveTrue(resource.getId())
                .orElse(null);
        String projectId   = assignment != null ? assignment.getProjectId() : null;
        String designation = assignment != null ? assignment.getRole() : null;
        int leaveLimit = resolveLeaveLimit(projectId, numberOfMonths);
        return buildSummary(resource, projectId, start, end, periodLabel, leaveLimit, designation);
    }

    private AttendanceReportTotals buildAttendanceTotals(List<AttendanceReportSummary> rows) {
        if (rows.isEmpty()) {
            return new AttendanceReportTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int workingDays        = rows.get(0).workingDays(); // same for all on the same project/calendar
        double presentSum      = rows.stream().mapToDouble(AttendanceReportSummary::presentDays).sum();
        int halfSum            = rows.stream().mapToInt(AttendanceReportSummary::halfDays).sum();
        int leaveSum           = rows.stream().mapToInt(AttendanceReportSummary::leaveDays).sum();
        int absentSum          = rows.stream().mapToInt(AttendanceReportSummary::absentDays).sum();
        int wfhSum             = rows.stream().mapToInt(AttendanceReportSummary::wfhDays).sum();
        double leaveTakenSum   = rows.stream().mapToDouble(AttendanceReportSummary::leaveTaken).sum();
        double paidSum         = rows.stream().mapToDouble(AttendanceReportSummary::paidLeaveDays).sum();
        double unpaidSum       = rows.stream().mapToDouble(AttendanceReportSummary::unpaidLeaveDays).sum();
        double avgAtt          = Math.round(
                rows.stream().mapToDouble(AttendanceReportSummary::attendancePercentage).average().orElse(0) * 100)
                / 100.0;
        return new AttendanceReportTotals(
                rows.size(), workingDays, presentSum, halfSum, leaveSum, absentSum, wfhSum,
                leaveTakenSum, paidSum, unpaidSum, avgAtt);
    }

    /**
     * Converts the leave policy to a limit for the given number of months.
     * Monthly policy × numberOfMonths: e.g. 2/month × 3 = 6 for a quarter.
     */
    private int resolveLeaveLimit(String projectId, int numberOfMonths) {
        if (projectId == null || projectId.isBlank()) return 0;
        Optional<LeavePolicyResponse> policy = leavePolicyClient.getLeavePolicy(projectId);
        return resolveMonthlyLeaveAllowance(projectId, policy) * numberOfMonths;
    }

    private AttendanceReportSummary buildSummary(
            MasterResource resource, String projectId, LocalDate start, LocalDate end, String periodLabel,
            int leaveLimit, String designation) {
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

        List<Attendance> rows =
                attendanceRepository.findByResourceIdAndAttendanceDateBetween(resource.getId(), start, end);

        // milestoneId / activityId come from the most-recent row's upload metadata.
        // All rows in a single upload share the same values; take the last non-null found.
        String milestoneId = null;
        String activityId  = null;
        for (Attendance row : rows) {
            if (row.getMilestoneId() != null) milestoneId = row.getMilestoneId();
            if (row.getActivityId()  != null) activityId  = row.getActivityId();
        }

        Map<AttendanceStatus, Long> counts = rows.stream()
                        .collect(Collectors.groupingBy(Attendance::getStatus, Collectors.counting()));

        int presentDaysRaw = counts.getOrDefault(AttendanceStatus.P, 0L).intValue();
        int halfDays = counts.getOrDefault(AttendanceStatus.HD, 0L).intValue();
        int leaveDays = counts.getOrDefault(AttendanceStatus.L, 0L).intValue();
        int absentDays = counts.getOrDefault(AttendanceStatus.A, 0L).intValue();
        int wfhDays = counts.getOrDefault(AttendanceStatus.WFH, 0L).intValue();

        // presentDays = fully-present days + worked portion of half-days (0.5 each).
        // effectiveLeaveTaken = absentDays + halfDays×0.5 → presentDays = workingDays − leaveTaken.
        double presentDays = presentDaysRaw + halfDays * 0.5;

        // leaveLimit is pre-scaled to the period (2/month → 6 for quarterly, 24 for yearly).
        double effectiveAbsent = absentDays + halfDays * 0.5;
        double paidLeaveDays   = Math.min(effectiveAbsent, leaveLimit);
        double unpaidLeaveDays = Math.max(0.0, effectiveAbsent - leaveLimit);

        // Attendance % = (effective present days + paid-leave covered days) / working days.
        // presentDays already includes the worked half-day portion (HD×0.5).
        double attendancePercentage = workingDays > 0
                ? Math.round((presentDays + paidLeaveDays) * 10000.0 / workingDays) / 100.0
                : 0d;

        return new AttendanceReportSummary(
                resource.getResId(),
                resource.getName(),
                designation,
                projectId,
                milestoneId,
                activityId,
                periodLabel,
                workingDays,
                presentDays,
                halfDays,
                leaveDays,
                absentDays,
                weekOffDays,
                holidayDays,
                wfhDays,
                attendancePercentage,
                effectiveAbsent,
                paidLeaveDays,
                unpaidLeaveDays);
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
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        String periodLabel = monthStart.format(MONTH_YEAR);
        periodValidator.validate(monthStart, monthEnd, projectId, null, periodLabel);
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
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        periodValidator.validate(monthStart, monthEnd, projectId, resourceId, monthStart.format(MONTH_YEAR));
        return buildMonthlyCost(resource, projectId, monthStart, monthStart.format(MONTH_YEAR));
    }

    /**
     * Quarterly cost: pass {@code resourceId} for a single resource's summary, or {@code
     * projectId} for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public ResourceCostResult quarterlyCostReport(String projectId, String resourceId, int year, int quarter) {
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
    public ResourceCostResult yearlyCostReport(String projectId, String resourceId, int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
        List<Integer> months = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        return costSummaryReport(projectId, resourceId, year, months, String.valueOf(year));
    }

    private ResourceCostResult costSummaryReport(
            String projectId, String resourceId, int year, List<Integer> months, String periodLabel) {
        LocalDate periodStart = LocalDate.of(year, months.get(0), 1);
        LocalDate periodEnd = LocalDate.of(year, months.get(months.size() - 1), 1);
        periodEnd = periodEnd.withDayOfMonth(periodEnd.lengthOfMonth());
        periodValidator.validate(periodStart, periodEnd, projectId, resourceId, periodLabel);
        List<ResourceCostSummary> rows;
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
            rows = List.of(buildCostSummary(resource, resolvedProjectId, year, months, periodLabel));
        } else {
            if (projectId == null || projectId.isBlank()) {
                throw new BadRequestException("projectId or resourceId is required");
            }
            rows = projectResourceRepository.findByProjectIdAndActiveTrue(projectId).stream()
                    .map(ProjectResource::getResource)
                    .map(resource -> buildCostSummary(resource, projectId, year, months, periodLabel))
                    .toList();
        }
        return new ResourceCostResult(periodLabel, rows.size(), buildCostTotals(rows), rows);
    }

    private ResourceCostTotals buildCostTotals(List<ResourceCostSummary> rows) {
        double totalCost             = round2(rows.stream().mapToDouble(ResourceCostSummary::totalCost).sum());
        double totalRelaxationAmount = round2(rows.stream().mapToDouble(ResourceCostSummary::relaxationAmount).sum());
        double totalDeducted         = round2(rows.stream()
                .flatMap(r -> r.monthlyBreakdown().stream())
                .mapToDouble(MonthlyResourceCost::deductedAmount)
                .sum());
        return new ResourceCostTotals(rows.size(), totalCost, totalRelaxationAmount, totalDeducted);
    }

    private ResourceCostSummary buildCostSummary(
            MasterResource resource, String projectId, int year, List<Integer> months, String periodLabel) {
        List<MonthlyResourceCost> monthly = months.stream()
                .map(month -> buildMonthlyCost(resource, projectId, LocalDate.of(year, month, 1),
                        LocalDate.of(year, month, 1).format(MONTH_YEAR)))
                .toList();

        double sumMonthlyCost = monthly.stream().mapToDouble(MonthlyResourceCost::cost).sum();

        // Relaxation is a quarterly settlement, not monthly. For each quarter covered by the
        // months list, compute: perDayRate = quarterlyRate / quarterWorkingDays, then
        // relaxationAmount = relaxationDays × perDayRate.
        Set<Integer> quarters = months.stream()
                .map(m -> (m - 1) / 3 + 1)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        double totalRelaxationDays   = 0;
        double totalRelaxationAmount = 0;
        for (int quarter : quarters) {
            // Collect the monthly cost rows that belong to this quarter.
            List<MonthlyResourceCost> quarterMonths = new ArrayList<>();
            for (int i = 0; i < months.size(); i++) {
                if ((months.get(i) - 1) / 3 + 1 == quarter) {
                    quarterMonths.add(monthly.get(i));
                }
            }
            double quarterWorkingDays = quarterMonths.stream().mapToInt(MonthlyResourceCost::workingDays).sum();
            double quarterlyRate      = quarterMonths.stream().mapToDouble(MonthlyResourceCost::monthlyRate).sum();

            if (quarterWorkingDays > 0 && quarterlyRate > 0 && projectId != null) {
                LeaveRelaxation relaxation = leaveRelaxationRepository
                        .findByResource_ResIdAndProjectIdAndYearAndQuarter(
                                resource.getResId(), projectId, year, quarter)
                        .orElse(null);
                if (relaxation != null && relaxation.getRelaxationDays() > 0) {
                    double perDayRate = quarterlyRate / quarterWorkingDays;
                    totalRelaxationDays   += relaxation.getRelaxationDays();
                    totalRelaxationAmount += relaxation.getRelaxationDays() * perDayRate;
                }
            }
        }

        double totalCost = round2(sumMonthlyCost + totalRelaxationAmount);
        return new ResourceCostSummary(
                resource.getResId(), resource.getName(), projectId, periodLabel,
                totalCost, totalRelaxationDays, round2(totalRelaxationAmount), monthly);
    }

    private MonthlyResourceCost buildMonthlyCost(
            MasterResource resource, String projectId, LocalDate monthStart, String periodLabel) {
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

        // Resolve monthly leave allowance BEFORE building the summary so the summary's
        // paidLeaveDays / unpaidLeaveDays fields reflect the correct monthly limit.
        int monthlyLeaveAllowance = 0;
        if (projectId != null) {
            Optional<LeavePolicyResponse> leavePolicy = leavePolicyClient.getLeavePolicy(projectId);
            monthlyLeaveAllowance = resolveMonthlyLeaveAllowance(projectId, leavePolicy);
        }
        ProjectResource assignment = projectId == null
                ? null
                : projectResourceRepository
                        .findByResource_ResIdAndProjectIdAndActiveTrue(resource.getResId(), projectId)
                        .orElse(null);
        AttendanceReportSummary attendance = buildSummary(
                resource, projectId, monthStart, monthEnd, periodLabel, monthlyLeaveAllowance,
                assignment != null ? assignment.getRole() : null);
        String rateYear = assignment != null ? assignment.getRateYear() : null;
        Double monthlyRate = (assignment != null && rateYear != null)
                ? assignment.getRateCardByYear().get(rateYear)
                : null;
        double rate = monthlyRate != null ? monthlyRate : 0d;

        // Relaxation is settled quarterly, not monthly — it is NOT added to effectivePaidDays here.
        // See buildCostSummary for the quarterly relaxationAmount calculation.
        double paidLeaveDaysApplied = attendance.paidLeaveDays();
        double effectivePaidDays = Math.min(
                attendance.workingDays(),
                attendance.presentDays()        // P-days + HD×0.5 (worked portion)
                        + paidLeaveDaysApplied); // leave-covered absent + half-day leave portion
        // Per-day unit price and derived breakup amounts (0 when there's no rate / no working days).
        double perDayRate = (monthlyRate != null && attendance.workingDays() > 0)
                ? round2(monthlyRate / attendance.workingDays())
                : 0d;
        double halfDayAmount = round2(perDayRate * attendance.halfDays() * 0.5);
        double cost = (monthlyRate != null && attendance.workingDays() > 0)
                ? round2(monthlyRate * effectivePaidDays / attendance.workingDays())
                : 0d;
        // Amount lost to unpaid days (before relaxation) = full month rate minus attendance-based cost.
        double deductedAmount = monthlyRate != null ? round2(rate - cost) : 0d;

        return new MonthlyResourceCost(
                resource.getResId(),
                resource.getName(),
                projectId,
                attendance.milestoneId(),
                attendance.activityId(),
                rateYear,
                periodLabel,
                attendance.workingDays(),
                attendance.presentDays(),
                attendance.halfDays(),
                attendance.absentDays(),
                paidLeaveDaysApplied,
                round2(effectivePaidDays),
                attendance.attendancePercentage(),
                rate,
                perDayRate,
                halfDayAmount,
                deductedAmount,
                cost);
    }

    /**
     * Monthly paid-leave allowance per resource: uses {@code leavesPerFrequencyCount} directly
     * when {@code leavesFrequency=MONTHLY}; divides quarterly/yearly counts proportionally.
     * Falls back to {@link ProjectConfig}, then to 0 (no policy = no credit).
     */
    private int resolveMonthlyLeaveAllowance(String projectId, Optional<LeavePolicyResponse> leavePolicy) {
        Integer count = leavePolicy.map(LeavePolicyResponse::leavesPerFrequencyCount).orElse(null);
        if (count != null) {
            String frequency = leavePolicy.map(LeavePolicyResponse::leavesFrequency).orElse(null);
            if (frequency == null) return count;
            return switch (frequency.trim().toUpperCase(Locale.ENGLISH)) {
                case "MONTHLY" -> count;                        // e.g. 2/month → 2
                case "QUARTERLY" -> Math.round(count / 3f);    // e.g. 6/quarter → 2/month
                case "YEARLY", "ANNUALLY", "ANNUAL" -> Math.round(count / 12f); // e.g. 24/year → 2/month
                default -> count;
            };
        }
        return 0;
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

        periodValidator.validate(quarterStart, quarterEnd, projectId, null, "Q" + quarter + " " + year);
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

            Set<LocalDate> halfDayDates = resourceRows.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.HD)
                    .map(Attendance::getAttendanceDate)
                    .collect(Collectors.toSet());

            LocalDate joiningDate = projectResourceRepository
                    .findByResourceIdAndActiveTrue(resource.getId())
                    .map(ProjectResource::getAssignmentStartDate)
                    .orElse(resource.getDateOfJoining());

            QuarterLeaveCalculation calculation = quarterLeaveResolver.calculate(
                    resource.getId(), resourceProjectId, joiningDate, year, quarter,
                    absentDates, halfDayDates);

            String milestoneId = null;
            String activityId  = null;
            for (Attendance row : resourceRows) {
                if (row.getMilestoneId() != null) milestoneId = row.getMilestoneId();
                if (row.getActivityId()  != null) activityId  = row.getActivityId();
            }

            settlements.add(new ResourceQuarterSettlement(
                    resource.getResId(), resource.getName(), milestoneId, activityId, joiningDate, calculation));
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
