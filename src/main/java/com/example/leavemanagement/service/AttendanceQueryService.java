package com.example.leavemanagement.service;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.ActivityAttendanceReportResult;
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
import com.example.leavemanagement.entity.Activity;
import com.example.leavemanagement.repository.ActivityRepository;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.LeaveRelaxationRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.repository.ProjectYearMappingRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
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
    private final ProjectYearMappingRepository yearMappingRepository;
    private final ActivityRepository activityRepository;

    public AttendanceQueryService(
            AttendanceExcelParser parser,
            AttendanceRepository attendanceRepository,
            PublicHolidayRepository holidayRepository,
            MasterResourceRepository masterResourceRepository,
            ProjectResourceRepository projectResourceRepository,
            LeavePolicyClient leavePolicyClient,
            LeaveRelaxationRepository leaveRelaxationRepository,
            QuarterLeaveResolver quarterLeaveResolver,
            AttendancePeriodValidator periodValidator,
            ProjectYearMappingRepository yearMappingRepository,
            ActivityRepository activityRepository) {
        this.parser = parser;
        this.attendanceRepository = attendanceRepository;
        this.holidayRepository = holidayRepository;
        this.masterResourceRepository = masterResourceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.leavePolicyClient = leavePolicyClient;
        this.leaveRelaxationRepository = leaveRelaxationRepository;
        this.quarterLeaveResolver = quarterLeaveResolver;
        this.periodValidator = periodValidator;
        this.yearMappingRepository = yearMappingRepository;
        this.activityRepository = activityRepository;
    }

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
            String organisationId,
            String milestoneId,
            String activityId,
            LocalDate startDate,
            LocalDate endDate,
            String rateYear,
            MultipartFile file) {
        validatePeriod(startDate, endDate);
        Activity activity = (activityId != null && !activityId.isBlank())
                ? activityRepository.findByActivityId(activityId).orElse(null)
                : null;
        if (activity != null) {
            if (startDate.isBefore(activity.getStartDate()) || endDate.isAfter(activity.getEndDate())) {
                throw new BadRequestException(
                        "Upload period " + startDate + " to " + endDate
                                + " is outside the activity date range "
                                + activity.getStartDate() + " to " + activity.getEndDate() + ".");
            }
        }
        List<EmployeeAttendanceByDate> parsed = parser.parse(file, startDate, endDate);
        Map<String, LeavePolicyResponse> leavePolicies =
                validateResourcesAndFetchLeavePolicies(attendanceIds(parsed), projectId);

        if (activity != null) {
            validateActivityResources(activity, startDate, endDate, parsed, projectId);
        }

        int[] thresholds = resolveThresholds(projectId, leavePolicies.get(projectId));
        Set<LocalDate> holidays = holidaysBetween(startDate, endDate);

        attendanceRepository.bulkDeleteByProjectIdAndDateBetween(projectId, startDate, endDate);

        int stored = 0;
        for (EmployeeAttendanceByDate employee : parsed) {
            MasterResource resource = masterResourceRepository.findByResId(employee.attendanceId()).orElseThrow();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (isWeekend(date) || holidays.contains(date)) {
                    continue;
                }
                Integer workedMinutes = employee.workedMinutesByDate().get(date);
                Attendance row;
                if (workedMinutes != null && workedMinutes > 0) {
                    AttendanceStatus status = workedMinutes >= thresholds[0] ? AttendanceStatus.P : AttendanceStatus.HD;
                    row = new Attendance(resource, projectId, organisationId, milestoneId, activityId, date, status);
                    row.setWorkingHours(Math.round(workedMinutes / 60.0 * 100) / 100.0);
                } else {
                    row = new Attendance(resource, projectId, organisationId, milestoneId, activityId, date, AttendanceStatus.A);
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
     * Validates that the uploaded resources match the activity's designation requirements.
     *
     * <p>Two levels of validation:
     * <ol>
     *   <li>Per-resource: each resource must have a designation that is configured for the activity,
     *       and must have been active during the upload period.
     *   <li>Count: per-designation uploaded count must not exceed the configured count; total
     *       uploaded must not exceed total configured.
     * </ol>
     * If no designation requirements are configured for the activity, only individual resource
     * checks (period overlap) are performed.
     */
    private void validateActivityResources(
            Activity activity, LocalDate startDate, LocalDate endDate,
            List<EmployeeAttendanceByDate> parsed, String projectId) {
        Map<String, Integer> required = activity.getDesignationRequirements();
        boolean hasRequirements = required != null && !required.isEmpty();

        List<String> resourceErrors = new ArrayList<>();
        Map<String, Integer> uploadedByDesignation = new LinkedHashMap<>();

        for (EmployeeAttendanceByDate employee : parsed) {
            ProjectResource assignment = projectResourceRepository
                    .findByResource_ResIdAndProjectIdAndActiveTrue(employee.attendanceId(), projectId)
                    .orElse(null);
            if (assignment == null) {
                continue; // already caught by validateResourcesAndFetchLeavePolicies
            }

            LocalDate joinDate = assignment.getAssignmentStartDate();
            LocalDate endDateAssignment = assignment.getAssignmentEndDate();
            if (joinDate != null && joinDate.isAfter(endDate)) {
                resourceErrors.add("Resource " + employee.attendanceId()
                        + " (joining date " + joinDate + ") joined after the attendance period end date " + endDate + ".");
                continue;
            }
            if (endDateAssignment != null && endDateAssignment.isBefore(startDate)) {
                resourceErrors.add("Resource " + employee.attendanceId()
                        + " (end date " + endDateAssignment + ") left before the attendance period start date " + startDate + ".");
                continue;
            }

            String designation = assignment.getRole();
            if (hasRequirements && (designation == null || !required.containsKey(designation))) {
                resourceErrors.add("Resource " + employee.attendanceId()
                        + " has designation '" + designation
                        + "' which is not configured for activity '" + activity.getActivityName() + "'.");
                continue;
            }

            if (designation != null) {
                uploadedByDesignation.merge(designation, 1, Integer::sum);
            }
        }

        if (!resourceErrors.isEmpty()) {
            throw new AttendanceValidationException(resourceErrors);
        }

        if (!hasRequirements) {
            return;
        }

        int totalRequired = required.values().stream().mapToInt(Integer::intValue).sum();
        int totalUploaded = parsed.size();

        List<String> countErrors = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : uploadedByDesignation.entrySet()) {
            String desig = entry.getKey();
            int uploadedCount = entry.getValue();
            int configuredCount = required.getOrDefault(desig, 0);
            if (uploadedCount > configuredCount) {
                countErrors.add(desig + ":\n  Configured Resources : " + configuredCount
                        + "\n  Uploaded Resources   : " + uploadedCount);
            }
        }

        if (!countErrors.isEmpty() || totalUploaded > totalRequired) {
            StringBuilder msg = new StringBuilder();
            msg.append("Attendance upload failed.\n\nActivity: ").append(activity.getActivityName()).append("\n\n");
            for (String e : countErrors) {
                msg.append(e).append("\n\n");
            }
            msg.append("Total Configured Resources = ").append(totalRequired).append("\n");
            msg.append("Total Uploaded Resources   = ").append(totalUploaded).append("\n\n");
            msg.append("Please upload attendance only for the resources assigned to the selected activity.");
            throw new BadRequestException(msg.toString());
        }
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

    /** Dashboard: one summary per resource currently active on the project, for one month. */
    @Transactional(readOnly = true)
    public AttendanceReportResult monthlyReport(String projectId, int year, int month) {
        validateMonthAndYear(year, month);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        String label = start.format(MONTH_YEAR);
        periodValidator.validate(start, end, projectId, null, label);
        return projectDashboard(projectId, null, start, end, label, 1);
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
        return toResult(label, start, end, rows);
    }

    @Transactional(readOnly = true)
    public AttendanceReportResult quarterlyReport(
            String projectId, String resourceId, String organisationId,
            String milestoneId, String activityId, Integer year, Integer quarter) {

        boolean hasActivity  = activityId  != null && !activityId.isBlank();
        boolean hasMilestone = milestoneId != null && !milestoneId.isBlank();

        if (hasActivity) {
            Activity activity = activityRepository.findByActivityId(activityId)
                    .orElseThrow(() -> new NotFoundException("No activity registered with id '" + activityId + "'"));
            LocalDate aStart = activity.getStartDate();
            LocalDate aEnd   = activity.getEndDate();
            LocalDate reportStart = attendanceRepository
                    .findMinDateByActivityIdAndDateBetween(activityId, aStart, aEnd).orElse(aStart);
            LocalDate reportEnd = attendanceRepository
                    .findMaxDateByActivityIdAndDateBetween(activityId, aStart, aEnd).orElse(aEnd);
            int q = (aStart.getMonthValue() - 1) / 3 + 1;
            String periodLabel = "Q" + q + " " + aStart.getYear();
            int leaveLimit = resolveLeaveLimit(projectId, 3);
            List<AttendanceReportSummary> rows = buildRowsFromResources(
                    attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(
                            activityId, reportStart, reportEnd),
                    projectId, organisationId, reportStart, reportEnd, periodLabel, leaveLimit);
            return toResult(periodLabel, reportStart, reportEnd, rows);
        }

        if (year == null || quarter == null) {
            throw new BadRequestException("year and quarter are required when activityId is not provided");
        }
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }

        String periodLabel = "Q" + quarter + " " + year;
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, organisationId);
        LocalDate qStart = quarterLeaveResolver.quarterStart(year, quarter, cycleDay);
        LocalDate qEnd   = quarterLeaveResolver.quarterEnd(year, quarter, cycleDay);

        if (hasMilestone) {
            if (projectId == null || projectId.isBlank()) {
                throw new BadRequestException("projectId is required when filtering by milestoneId");
            }
            LocalDate reportStart = attendanceRepository
                    .findMinDateByProjectIdAndMilestoneIdAndDateBetween(projectId, milestoneId, qStart, qEnd).orElse(qStart);
            LocalDate reportEnd = attendanceRepository
                    .findMaxDateByProjectIdAndMilestoneIdAndDateBetween(projectId, milestoneId, qStart, qEnd).orElse(qEnd);
            int leaveLimit = resolveLeaveLimit(projectId, 3);
            List<AttendanceReportSummary> rows = buildRowsFromResources(
                    attendanceRepository.findDistinctResourcesByProjectIdAndMilestoneIdAndDateBetween(
                            projectId, milestoneId, reportStart, reportEnd),
                    projectId, organisationId, reportStart, reportEnd, periodLabel, leaveLimit);
            return toResult(periodLabel, reportStart, reportEnd, rows);
        }

        LocalDate reportStart;
        LocalDate reportEnd;
        if (resourceId != null && !resourceId.isBlank()) {
            reportStart = attendanceRepository.findMinDateByResIdAndDateBetween(resourceId, qStart, qEnd).orElse(qStart);
            reportEnd   = attendanceRepository.findMaxDateByResIdAndDateBetween(resourceId, qStart, qEnd).orElse(qEnd);
        } else {
            if (projectId == null || projectId.isBlank()) {
                throw new BadRequestException("projectId or resourceId is required");
            }
            reportStart = attendanceRepository.findMinDateByProjectIdAndDateBetween(projectId, qStart, qEnd).orElse(qStart);
            reportEnd   = attendanceRepository.findMaxDateByProjectIdAndDateBetween(projectId, qStart, qEnd).orElse(qEnd);
        }
        return scopedReport(projectId, resourceId, organisationId, reportStart, reportEnd, periodLabel, 3);
    }

    private List<AttendanceReportSummary> buildRowsFromResources(
            List<MasterResource> resources, String projectId, String organisationId,
            LocalDate reportStart, LocalDate reportEnd, String periodLabel, int leaveLimit) {
        return resources.stream()
                .map(resource -> {
                    ProjectResource assignment = resolveAssignmentForProject(resource.getResId(), projectId);
                    if (organisationId != null && !organisationId.isBlank()
                            && (assignment == null || !organisationId.equals(assignment.getOrganisationId()))) {
                        return null;
                    }
                    String designation    = assignment != null ? assignment.getRole() : null;
                    LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
                    boolean active        = assignment != null && assignment.isActive();
                    LocalDate lastDate    = assignment != null ? assignment.getAssignmentEndDate() : null;
                    return buildSummary(resource, projectId, reportStart, reportEnd, periodLabel,
                            leaveLimit, designation, joiningDate, active, lastDate);
                })
                .filter(s -> s != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public AttendanceReportResult yearlyReport(String projectId, String resourceId, int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        return scopedReport(projectId, resourceId, null, start, end, String.valueOf(year), 12);
    }

    @Transactional(readOnly = true)
    public ActivityAttendanceReportResult activityReport(
            String projectId, String milestoneId, String activityId, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }
        Activity activity = activityRepository.findByActivityId(activityId)
                .orElseThrow(() -> new NotFoundException("No activity registered with id '" + activityId + "'"));

        int configuredCount = activity.getDesignationRequirements() == null ? 0
                : activity.getDesignationRequirements().values().stream().mapToInt(Integer::intValue).sum();

        String periodLabel = "Q" + quarter + " " + year;
        String organisationId = activity.getOrganisationId();
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, organisationId);
        LocalDate qStart = quarterLeaveResolver.quarterStart(year, quarter, cycleDay);
        LocalDate qEnd   = quarterLeaveResolver.quarterEnd(year, quarter, cycleDay);

        LocalDate reportStart = attendanceRepository
                .findMinDateByActivityIdAndDateBetween(activityId, qStart, qEnd).orElse(qStart);
        LocalDate reportEnd = attendanceRepository
                .findMaxDateByActivityIdAndDateBetween(activityId, qStart, qEnd).orElse(qEnd);

        List<MasterResource> uploadedResources = attendanceRepository
                .findDistinctResourcesByActivityIdAndDateBetween(activityId, reportStart, reportEnd);

        int leaveLimit = resolveLeaveLimit(projectId, 3);

        List<AttendanceReportSummary> rows = uploadedResources.stream()
                .map(resource -> {
                    ProjectResource assignment = resolveAssignmentForProject(resource.getResId(), projectId);
                    String designation    = assignment != null ? assignment.getRole() : null;
                    LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
                    boolean active        = assignment != null && assignment.isActive();
                    LocalDate lastDate    = assignment != null ? assignment.getAssignmentEndDate() : null;
                    return buildSummary(resource, projectId, reportStart, reportEnd, periodLabel,
                            leaveLimit, designation, joiningDate, active, lastDate);
                })
                .toList();

        int calendarDays = (int) (reportEnd.toEpochDay() - reportStart.toEpochDay()) + 1;
        return new ActivityAttendanceReportResult(
                activityId, activity.getActivityName(), projectId, milestoneId,
                periodLabel, reportStart, reportEnd, calendarDays,
                configuredCount, uploadedResources.size(),
                buildAttendanceTotals(rows, calendarDays), rows);
    }

    private AttendanceReportResult scopedReport(
            String projectId, String resourceId, String organisationId, LocalDate start, LocalDate end,
            String periodLabel, int numberOfMonths) {
        periodValidator.validate(start, end, projectId, resourceId, periodLabel);
        if (resourceId != null && !resourceId.isBlank()) {
            AttendanceReportSummary summary =
                    employeeSummary(resourceId, start, end, periodLabel, numberOfMonths);
            List<AttendanceReportSummary> rows = List.of(summary);
            return toResult(periodLabel, start, end, rows);
        }
        if (projectId == null || projectId.isBlank()) {
            throw new BadRequestException("projectId or resourceId is required");
        }
        return projectDashboard(projectId, organisationId, start, end, periodLabel, numberOfMonths);
    }

    private AttendanceReportResult projectDashboard(
            String projectId, String organisationId, LocalDate start, LocalDate end, String periodLabel, int numberOfMonths) {
        int leaveLimit = resolveLeaveLimit(projectId, numberOfMonths);
        List<AttendanceReportSummary> rows = latestAssignmentsActiveDuring(projectId, start, end).stream()
                .filter(pr -> organisationId == null || organisationId.isBlank()
                        || organisationId.equals(pr.getOrganisationId()))
                .map(pr -> buildSummary(pr.getResource(), projectId, start, end, periodLabel, leaveLimit,
                        pr.getRole(), pr.getAssignmentStartDate(), pr.isActive(), pr.getAssignmentEndDate()))
                .toList();
        return toResult(periodLabel, start, end, rows);
    }

    private AttendanceReportSummary employeeSummary(
            String resourceId, LocalDate start, LocalDate end, String periodLabel, int numberOfMonths) {
        MasterResource resource = masterResourceRepository
                .findByResId(resourceId)
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + resourceId));
        ProjectResource assignment = resolveAssignment(resource);
        String projectId   = assignment != null ? assignment.getProjectId() : null;
        String designation = assignment != null ? assignment.getRole() : null;
        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        boolean active = assignment != null && assignment.isActive();
        LocalDate lastWorkingDate = assignment != null ? assignment.getAssignmentEndDate() : null;
        int leaveLimit = resolveLeaveLimit(projectId, numberOfMonths);
        return buildSummary(resource, projectId, start, end, periodLabel, leaveLimit, designation, joiningDate, active, lastWorkingDate);
    }

    private AttendanceReportResult toResult(String periodLabel, LocalDate start, LocalDate end, List<AttendanceReportSummary> rows) {
        int calendarDays = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        return new AttendanceReportResult(periodLabel, start, end, calendarDays, rows.size(), buildAttendanceTotals(rows, calendarDays), rows);
    }

    private ProjectResource resolveAssignment(MasterResource resource) {
        return projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId())
                .orElseGet(() -> projectResourceRepository
                        .findByResourceIdOrderByAssignmentStartDateAsc(resource.getId())
                        .stream().reduce((a, b) -> b).orElse(null));
    }

    private ProjectResource resolveAssignmentForProject(String resId, String projectId) {
        return projectResourceRepository
                .findByResource_ResIdAndProjectIdAndActiveTrue(resId, projectId)
                .orElseGet(() -> projectResourceRepository
                        .findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc(resId, projectId)
                        .stream().findFirst().orElse(null));
    }

    private List<ProjectResource> latestAssignmentsActiveDuring(
            String projectId, LocalDate start, LocalDate end) {
        List<ProjectResource> all = projectResourceRepository.findByProjectIdActiveDuring(projectId, start, end);
        Map<Long, ProjectResource> latestByResource = new LinkedHashMap<>();
        all.stream()
                .sorted(Comparator.comparing(ProjectResource::getAssignmentStartDate))
                .forEach(pr -> latestByResource.put(pr.getResource().getId(), pr));
        return new ArrayList<>(latestByResource.values());
    }

    private AttendanceReportTotals buildAttendanceTotals(List<AttendanceReportSummary> rows, int calendarDays) {
        if (rows.isEmpty()) {
            return new AttendanceReportTotals(0, calendarDays, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int workingDays        = rows.get(0).workingDays();
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
                rows.size(), calendarDays, workingDays, presentSum, halfSum, leaveSum, absentSum, wfhSum,
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
            int leaveLimit, String designation, LocalDate joiningDate, boolean active, LocalDate lastWorkingDate) {
        LocalDate effectiveStart = (joiningDate != null && joiningDate.isAfter(start)) ? joiningDate : start;
        LocalDate effectiveEnd = (lastWorkingDate != null && lastWorkingDate.isBefore(end)) ? lastWorkingDate : end;
        int totalDays = (int) (effectiveEnd.toEpochDay() - effectiveStart.toEpochDay()) + 1;
        Set<LocalDate> holidays = holidaysBetween(effectiveStart, effectiveEnd);
        int weekOffDays = 0;
        int holidayDays = 0;
        for (LocalDate date = effectiveStart; !date.isAfter(effectiveEnd); date = date.plusDays(1)) {
            if (isWeekend(date)) {
                weekOffDays++;
            } else if (holidays.contains(date)) {
                holidayDays++;
            }
        }
        int workingDays = totalDays - weekOffDays - holidayDays;

        List<Attendance> rows =
                attendanceRepository.findByResourceIdAndAttendanceDateBetween(resource.getId(), effectiveStart, effectiveEnd);

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

        double presentDays = presentDaysRaw + halfDays * 0.5;

        double effectiveAbsent = absentDays + halfDays * 0.5;
        double paidLeaveDays   = leaveLimit > 0 ? Math.min(effectiveAbsent, leaveLimit) : 0.0;
        double unpaidLeaveDays = leaveLimit > 0 ? Math.max(0.0, effectiveAbsent - leaveLimit) : effectiveAbsent;

        double attendancePercentage = workingDays > 0
                ? Math.round((presentDays + paidLeaveDays) * 10000.0 / workingDays) / 100.0
                : 0d;

        int calendarDays = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        return new AttendanceReportSummary(
                resource.getResId(),
                resource.getName(),
                designation,
                projectId,
                milestoneId,
                activityId,
                joiningDate,
                lastWorkingDate,
                active,
                periodLabel,
                start,
                end,
                calendarDays,
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

    /** Dashboard: one month's cost per resource active on the project during that month. */
    @Transactional(readOnly = true)
    public List<MonthlyResourceCost> monthlyCostReport(String projectId, int year, int month) {
        validateMonthAndYear(year, month);
        String orgId = projectResourceRepository.findByProjectId(projectId)
                .stream().findFirst().map(ProjectResource::getOrganisationId).orElse(null);
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, orgId);
        LocalDate fromDate = LocalDate.of(year, month, cycleDay);
        LocalDate toDate = fromDate.plusMonths(1).minusDays(1);
        String periodLabel = fromDate.format(MONTH_YEAR);
        periodValidator.validate(fromDate, toDate, projectId, null, periodLabel);
        int quarter = (month - 1) / 3 + 1;
        return latestAssignmentsActiveDuring(projectId, fromDate, toDate).stream()
                .map(ProjectResource::getResource)
                .map(resource -> {
                    QuarterLeaveCalculation calc = computeQuarterlyLeave(resource, projectId, year, quarter);
                    return buildMonthlyCost(resource, projectId, fromDate, toDate, periodLabel,
                            new HashSet<>(calc.unpaidLeaveDates()),
                            new HashSet<>(calc.unpaidHalfDayDates()));
                })
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
        String orgId = projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId())
                .map(ProjectResource::getOrganisationId).orElse(null);
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, orgId);
        LocalDate fromDate = LocalDate.of(year, month, cycleDay);
        LocalDate toDate = fromDate.plusMonths(1).minusDays(1);
        periodValidator.validate(fromDate, toDate, projectId, resourceId, fromDate.format(MONTH_YEAR));
        int quarter = (month - 1) / 3 + 1;
        QuarterLeaveCalculation calc = computeQuarterlyLeave(resource, projectId, year, quarter);
        return buildMonthlyCost(resource, projectId, fromDate, toDate, fromDate.format(MONTH_YEAR),
                new HashSet<>(calc.unpaidLeaveDates()), new HashSet<>(calc.unpaidHalfDayDates()));
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
                    : Optional.ofNullable(resolveAssignment(resource))
                            .map(ProjectResource::getProjectId)
                            .orElse(null);
            rows = List.of(buildCostSummary(resource, resolvedProjectId, year, months, periodLabel));
        } else {
            if (projectId == null || projectId.isBlank()) {
                throw new BadRequestException("projectId or resourceId is required");
            }
            rows = latestAssignmentsActiveDuring(projectId, periodStart, periodEnd).stream()
                    .map(ProjectResource::getResource)
                    .map(resource -> buildCostSummary(resource, projectId, year, months, periodLabel))
                    .toList();
        }
        return new ResourceCostResult(periodLabel, rows.size(), buildCostTotals(rows), rows);
    }

    private ResourceCostTotals buildCostTotals(List<ResourceCostSummary> rows) {
        double totalCost         = round2(rows.stream().mapToDouble(ResourceCostSummary::totalCost).sum());
        double totalRelaxation   = round2(rows.stream().mapToDouble(ResourceCostSummary::relaxationCost).sum());
        double totalDeducted     = round2(rows.stream().mapToDouble(ResourceCostSummary::deductedAmount).sum());
        return new ResourceCostTotals(rows.size(), totalCost, totalRelaxation, totalDeducted);
    }

    private ResourceCostSummary buildCostSummary(
            MasterResource resource, String projectId, int year, List<Integer> months, String periodLabel) {
        String assignmentOrgId = projectId == null ? null
                : Optional.ofNullable(resolveAssignmentForProject(resource.getResId(), projectId))
                        .map(ProjectResource::getOrganisationId).orElse(null);
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, assignmentOrgId);

        Set<Integer> quarters = months.stream().map(m -> (m - 1) / 3 + 1)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<Integer, Set<LocalDate>> unpaidFullByQuarter = new HashMap<>();
        Map<Integer, Set<LocalDate>> unpaidHalfByQuarter = new HashMap<>();
        for (int quarter : quarters) {
            QuarterLeaveCalculation calc = computeQuarterlyLeave(resource, projectId, year, quarter);
            unpaidFullByQuarter.put(quarter, new HashSet<>(calc.unpaidLeaveDates()));
            unpaidHalfByQuarter.put(quarter, new HashSet<>(calc.unpaidHalfDayDates()));
        }

        List<MonthlyResourceCost> monthly = months.stream()
                .map(month -> {
                    int quarter = (month - 1) / 3 + 1;
                    LocalDate fromDate = LocalDate.of(year, month, cycleDay);
                    LocalDate toDate = fromDate.plusMonths(1).minusDays(1);
                    return buildMonthlyCost(resource, projectId, fromDate, toDate,
                            fromDate.format(MONTH_YEAR),
                            unpaidFullByQuarter.getOrDefault(quarter, Set.of()),
                            unpaidHalfByQuarter.getOrDefault(quarter, Set.of()));
                })
                .toList();

        int totalCalendarDays = monthly.stream().mapToInt(MonthlyResourceCost::calendarDays).sum();
        double totalPlannedCost = monthly.stream().mapToDouble(m -> m.cost() + m.deductedAmount()).sum();
        double totalUnpaidLeaveDays = monthly.stream().mapToDouble(MonthlyResourceCost::unpaidLeaveDays).sum();
        double totalDeductedAmount = monthly.stream().mapToDouble(MonthlyResourceCost::deductedAmount).sum();

        double totalRelaxationDays = 0;
        double totalRelaxationCost = 0;
        for (int quarter : quarters) {
            if (projectId != null) {
                LeaveRelaxation relaxation = leaveRelaxationRepository
                        .findByResource_ResIdAndProjectIdAndYearAndQuarter(
                                resource.getResId(), projectId, year, quarter)
                        .orElse(null);
                if (relaxation != null && relaxation.getRelaxationDays() > 0) {
                    totalRelaxationDays += relaxation.getRelaxationDays();
                    totalRelaxationCost += relaxation.getRelaxationCost();
                }
            }
        }

        double perDayCost = totalCalendarDays > 0 ? round2(totalPlannedCost / totalCalendarDays) : 0d;
        double paidCalDays = totalCalendarDays - totalUnpaidLeaveDays;
        double deductedAmount = round2(totalDeductedAmount);
        double periodCost = round2(totalPlannedCost - totalDeductedAmount);
        double relaxationCost = round2(totalRelaxationCost);
        double totalCost = round2(periodCost + relaxationCost);

        return new ResourceCostSummary(
                resource.getResId(), resource.getName(), projectId, periodLabel,
                totalCalendarDays, round2(totalPlannedCost), perDayCost,
                totalUnpaidLeaveDays, round2(paidCalDays),
                deductedAmount, periodCost,
                totalRelaxationDays, relaxationCost, totalCost,
                monthly);
    }

    private QuarterLeaveCalculation computeQuarterlyLeave(
            MasterResource resource, String projectId, int year, int quarter) {
        ProjectResource assignment = projectId == null ? null
                : resolveAssignmentForProject(resource.getResId(), projectId);
        String orgId = assignment != null ? assignment.getOrganisationId() : null;
        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, orgId);
        LocalDate qStart = quarterLeaveResolver.quarterStart(year, quarter, cycleDay);
        LocalDate qEnd = quarterLeaveResolver.quarterEnd(year, quarter, cycleDay);
        List<Attendance> qRows = attendanceRepository
                .findByResourceIdAndAttendanceDateBetween(resource.getId(), qStart, qEnd);
        Set<LocalDate> absentDates = qRows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        Set<LocalDate> halfDayDates = qRows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.HD)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        return quarterLeaveResolver.calculate(
                resource.getResId(), resource.getId(), projectId, orgId,
                joiningDate, year, quarter, absentDates, halfDayDates);
    }

    private MonthlyResourceCost buildMonthlyCost(
            MasterResource resource, String projectId, LocalDate fromDate, LocalDate toDate,
            String periodLabel, Set<LocalDate> quarterlyUnpaidFull, Set<LocalDate> quarterlyUnpaidHalf) {
        ProjectResource assignment = projectId == null
                ? null
                : resolveAssignmentForProject(resource.getResId(), projectId);
        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        LocalDate lastWorkingDate = assignment != null ? assignment.getAssignmentEndDate() : null;
        boolean active = assignment != null && assignment.isActive();
        AttendanceReportSummary attendance = buildSummary(
                resource, projectId, fromDate, toDate, periodLabel, 0,
                assignment != null ? assignment.getRole() : null, joiningDate, active, lastWorkingDate);
        String rateYear = resolveRateYear(
                projectId,
                assignment != null ? assignment.getOrganisationId() : null,
                fromDate,
                assignment != null ? assignment.getRateYear() : null);
        Double monthlyRate = (assignment != null && rateYear != null)
                ? assignment.getRateCardByYear().get(rateYear)
                : null;
        double rate = monthlyRate != null ? monthlyRate : 0d;

        int fullCalendarDays = (int) (toDate.toEpochDay() - fromDate.toEpochDay()) + 1;
        LocalDate effectiveFrom = (joiningDate != null && joiningDate.isAfter(fromDate)) ? joiningDate : fromDate;
        LocalDate effectiveTo = (lastWorkingDate != null && lastWorkingDate.isBefore(toDate)) ? lastWorkingDate : toDate;
        int calendarDays = Math.max(0, (int) (effectiveTo.toEpochDay() - effectiveFrom.toEpochDay()) + 1);

        double unpaidLeaveDays =
                quarterlyUnpaidFull.stream().filter(d -> !d.isBefore(fromDate) && !d.isAfter(toDate)).count()
                + quarterlyUnpaidHalf.stream().filter(d -> !d.isBefore(fromDate) && !d.isAfter(toDate)).count() * 0.5;
        double effectiveAbsent = attendance.absentDays() + attendance.halfDays() * 0.5;
        double paidLeaveDaysMonthly = Math.max(0.0, effectiveAbsent - unpaidLeaveDays);

        double perDayRate = monthlyRate != null ? round2(monthlyRate / fullCalendarDays) : 0d;
        double deductedAmount = monthlyRate != null ? round2(unpaidLeaveDays * monthlyRate / fullCalendarDays) : 0d;
        double cost = monthlyRate != null ? round2((double) calendarDays * monthlyRate / fullCalendarDays - deductedAmount) : 0d;
        double paidCalendarDays = calendarDays - unpaidLeaveDays;

        return new MonthlyResourceCost(
                resource.getResId(),
                resource.getName(),
                projectId,
                attendance.milestoneId(),
                attendance.activityId(),
                rateYear,
                periodLabel,
                fromDate,
                toDate,
                attendance.workingDays(),
                attendance.presentDays(),
                attendance.halfDays(),
                attendance.absentDays(),
                paidLeaveDaysMonthly,
                calendarDays,
                unpaidLeaveDays,
                round2(paidCalendarDays),
                rate,
                perDayRate,
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
                case "MONTHLY" -> count;
                case "QUARTERLY" -> Math.round(count / 3f);
                case "YEARLY", "ANNUALLY", "ANNUAL" -> Math.round(count / 12f);
                default -> count;
            };
        }
        return 0;
    }

    /**
     * Returns the rate year label (e.g. "Year-3") for the given project/organisation and date,
     * using the project-year date ranges stored in {@code project_year_mapping}. Falls back to the
     * manually-set {@code fallbackRateYear} from the {@code ProjectResource} assignment when no
     * mapping exists (e.g. the designation rate was uploaded without project start/end dates).
     */
    private String resolveRateYear(
            String projectId, String organisationId, LocalDate date, String fallbackRateYear) {
        if (projectId != null && organisationId != null && date != null) {
            return yearMappingRepository.findEffectiveOn(projectId, organisationId, date)
                    .map(com.example.leavemanagement.entity.ProjectYearMapping::getRateYear)
                    .orElse(fallbackRateYear);
        }
        return fallbackRateYear;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final java.time.format.DateTimeFormatter PERIOD_DATE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    /**
     * Attendance report scoped to an arbitrary date range (activity upload period).
     * Leave is calculated using the cumulative quarter balance, so prior uploads within the same
     * quarter are accounted for automatically.
     */
    @Transactional(readOnly = true)
    public AttendanceReportResult periodReport(
            String projectId, String organisationId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new BadRequestException("Valid startDate and endDate are required.");
        }
        String periodLabel = startDate.format(PERIOD_DATE_FMT) + " to " + endDate.format(PERIOD_DATE_FMT);
        periodValidator.validate(startDate, endDate, projectId, null, periodLabel);
        List<AttendanceReportSummary> rows = latestAssignmentsActiveDuring(projectId, startDate, endDate).stream()
                .filter(pr -> organisationId == null || organisationId.isBlank()
                        || organisationId.equals(pr.getOrganisationId()))
                .map(pr -> buildPeriodSummary(
                        pr.getResource(), projectId, startDate, endDate, periodLabel,
                        pr.getRole(), pr.getAssignmentStartDate(), pr.isActive(), pr.getAssignmentEndDate()))
                .toList();
        return toResult(periodLabel, startDate, endDate, rows);
    }

    private AttendanceReportSummary buildPeriodSummary(
            MasterResource resource, String projectId, LocalDate start, LocalDate end,
            String periodLabel, String designation, LocalDate joiningDate,
            boolean active, LocalDate lastWorkingDate) {
        LocalDate effectiveStart = (joiningDate != null && joiningDate.isAfter(start)) ? joiningDate : start;
        LocalDate effectiveEnd = (lastWorkingDate != null && lastWorkingDate.isBefore(end)) ? lastWorkingDate : end;
        int totalDays = Math.max(0, (int) (effectiveEnd.toEpochDay() - effectiveStart.toEpochDay()) + 1);
        Set<LocalDate> holidays = holidaysBetween(effectiveStart, effectiveEnd);
        int weekOffDays = 0;
        int holidayDays = 0;
        for (LocalDate d = effectiveStart; !d.isAfter(effectiveEnd); d = d.plusDays(1)) {
            if (isWeekend(d)) weekOffDays++;
            else if (holidays.contains(d)) holidayDays++;
        }
        int workingDays = totalDays - weekOffDays - holidayDays;

        List<Attendance> rows =
                attendanceRepository.findByResourceIdAndAttendanceDateBetween(resource.getId(), effectiveStart, effectiveEnd);

        String milestoneId = null;
        String activityId = null;
        for (Attendance row : rows) {
            if (row.getMilestoneId() != null) milestoneId = row.getMilestoneId();
            if (row.getActivityId() != null) activityId = row.getActivityId();
        }

        Map<AttendanceStatus, Long> counts =
                rows.stream().collect(Collectors.groupingBy(Attendance::getStatus, Collectors.counting()));
        int presentDaysRaw = counts.getOrDefault(AttendanceStatus.P, 0L).intValue();
        int halfDays = counts.getOrDefault(AttendanceStatus.HD, 0L).intValue();
        int leaveDays = counts.getOrDefault(AttendanceStatus.L, 0L).intValue();
        int absentDays = counts.getOrDefault(AttendanceStatus.A, 0L).intValue();
        int wfhDays = counts.getOrDefault(AttendanceStatus.WFH, 0L).intValue();
        double presentDays = presentDaysRaw + halfDays * 0.5;

        ProjectResource assignment = resolveAssignmentForProject(resource.getResId(), projectId);
        String orgId = assignment != null ? assignment.getOrganisationId() : null;
        Set<LocalDate> absentSet = rows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        Set<LocalDate> halfDaySet = rows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.HD)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());

        QuarterLeaveCalculation leaveCalc = quarterLeaveResolver.calculateForPeriod(
                resource.getResId(), resource.getId(), projectId, orgId,
                joiningDate, effectiveStart, effectiveEnd, absentSet, halfDaySet);

        double effectiveAbsent = absentDays + halfDays * 0.5;
        double paidLeaveDays = leaveCalc.paidLeaveDays();
        double unpaidLeaveDays = leaveCalc.unpaidLeaveDays();
        double attendancePercentage = workingDays > 0
                ? Math.round((presentDays + paidLeaveDays) * 10000.0 / workingDays) / 100.0
                : 0d;

        int calendarDays = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        return new AttendanceReportSummary(
                resource.getResId(), resource.getName(), designation, projectId,
                milestoneId, activityId, joiningDate, lastWorkingDate, active,
                periodLabel, start, end, calendarDays, workingDays, presentDays, halfDays,
                leaveDays, absentDays, weekOffDays, holidayDays, wfhDays, attendancePercentage,
                effectiveAbsent, paidLeaveDays, unpaidLeaveDays);
    }

    /**
     * Resource cost report scoped to an arbitrary date range (activity upload period).
     * Per-day rate is computed per-month-segment so cross-month periods are priced correctly.
     * Leave is period-scoped via {@link QuarterLeaveResolver#calculateForPeriod}.
     */
    @Transactional(readOnly = true)
    public ResourceCostResult periodCostReport(
            String projectId, String organisationId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new BadRequestException("Valid startDate and endDate are required.");
        }
        String periodLabel = startDate.format(PERIOD_DATE_FMT) + " to " + endDate.format(PERIOD_DATE_FMT);
        periodValidator.validate(startDate, endDate, projectId, null, periodLabel);
        List<ResourceCostSummary> rows = latestAssignmentsActiveDuring(projectId, startDate, endDate).stream()
                .filter(pr -> organisationId == null || organisationId.isBlank()
                        || organisationId.equals(pr.getOrganisationId()))
                .map(pr -> buildPeriodCostSummary(pr.getResource(), projectId, startDate, endDate, periodLabel))
                .toList();
        return new ResourceCostResult(periodLabel, rows.size(), buildCostTotals(rows), rows);
    }

    private ResourceCostSummary buildPeriodCostSummary(
            MasterResource resource, String projectId, LocalDate periodStart, LocalDate periodEnd,
            String periodLabel) {
        ProjectResource assignment = resolveAssignmentForProject(resource.getResId(), projectId);
        String orgId = assignment != null ? assignment.getOrganisationId() : null;
        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        LocalDate lastWorkingDate = assignment != null ? assignment.getAssignmentEndDate() : null;
        boolean active = assignment != null && assignment.isActive();
        String rateYear = resolveRateYear(projectId, orgId, periodStart,
                assignment != null ? assignment.getRateYear() : null);

        LocalDate effectiveFrom = (joiningDate != null && joiningDate.isAfter(periodStart)) ? joiningDate : periodStart;
        LocalDate effectiveTo = (lastWorkingDate != null && lastWorkingDate.isBefore(periodEnd)) ? lastWorkingDate : periodEnd;
        int calendarDays = Math.max(0, (int) (effectiveTo.toEpochDay() - effectiveFrom.toEpochDay()) + 1);

        List<Attendance> periodRows = attendanceRepository
                .findByResourceIdAndAttendanceDateBetween(resource.getId(), effectiveFrom, effectiveTo);

        String milestoneId = null;
        String activityId = null;
        for (Attendance row : periodRows) {
            if (row.getMilestoneId() != null) milestoneId = row.getMilestoneId();
            if (row.getActivityId() != null) activityId = row.getActivityId();
        }

        Map<AttendanceStatus, Long> counts =
                periodRows.stream().collect(Collectors.groupingBy(Attendance::getStatus, Collectors.counting()));
        int presentDaysRaw = counts.getOrDefault(AttendanceStatus.P, 0L).intValue();
        int halfDays = counts.getOrDefault(AttendanceStatus.HD, 0L).intValue();
        int absentDays = counts.getOrDefault(AttendanceStatus.A, 0L).intValue();
        double presentDays = presentDaysRaw + halfDays * 0.5;

        Set<LocalDate> absentSet = periodRows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        Set<LocalDate> halfDaySet = periodRows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.HD)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());

        QuarterLeaveCalculation leaveCalc = quarterLeaveResolver.calculateForPeriod(
                resource.getResId(), resource.getId(), projectId, orgId,
                joiningDate, effectiveFrom, effectiveTo, absentSet, halfDaySet);

        Set<LocalDate> unpaidFull = new HashSet<>(leaveCalc.unpaidLeaveDates());
        Set<LocalDate> unpaidHalf = new HashSet<>(leaveCalc.unpaidHalfDayDates());
        double unpaidLeaveDays = unpaidFull.size() + unpaidHalf.size() * 0.5;

        double totalPlannedCost = 0d;
        double totalDeductedAmount = 0d;
        if (assignment != null) {
            LocalDate segStart = effectiveFrom;
            while (!segStart.isAfter(effectiveTo)) {
                LocalDate monthEnd = segStart.withDayOfMonth(segStart.lengthOfMonth());
                LocalDate segEnd = monthEnd.isBefore(effectiveTo) ? monthEnd : effectiveTo;
                int daysInSegment = (int) (segEnd.toEpochDay() - segStart.toEpochDay()) + 1;
                String segRateYear = resolveRateYear(projectId, orgId, segStart,
                        assignment.getRateYear());
                Double segRate = segRateYear != null ? assignment.getRateCardByYear().get(segRateYear) : null;
                if (segRate != null) {
                    totalPlannedCost += segRate * daysInSegment / segStart.lengthOfMonth();
                }
                segStart = segEnd.plusDays(1);
            }
            for (LocalDate d : unpaidFull) {
                if (!d.isBefore(effectiveFrom) && !d.isAfter(effectiveTo)) {
                    String dRateYear = resolveRateYear(projectId, orgId, d, assignment.getRateYear());
                    Double dRate = dRateYear != null ? assignment.getRateCardByYear().get(dRateYear) : null;
                    if (dRate != null) totalDeductedAmount += dRate / d.lengthOfMonth();
                }
            }
            for (LocalDate d : unpaidHalf) {
                if (!d.isBefore(effectiveFrom) && !d.isAfter(effectiveTo)) {
                    String dRateYear = resolveRateYear(projectId, orgId, d, assignment.getRateYear());
                    Double dRate = dRateYear != null ? assignment.getRateCardByYear().get(dRateYear) : null;
                    if (dRate != null) totalDeductedAmount += 0.5 * dRate / d.lengthOfMonth();
                }
            }
        }

        double perDayCost = calendarDays > 0 ? round2(totalPlannedCost / calendarDays) : 0d;
        double deductedAmount = round2(totalDeductedAmount);
        double periodCost = round2(totalPlannedCost - totalDeductedAmount);

        int year = periodStart.getYear();
        int quarter = (periodStart.getMonthValue() - 1) / 3 + 1;
        double relaxationDays = 0;
        double relaxationCostVal = 0;
        if (projectId != null) {
            LeaveRelaxation relaxation = leaveRelaxationRepository
                    .findByResource_ResIdAndProjectIdAndYearAndQuarter(
                            resource.getResId(), projectId, year, quarter)
                    .orElse(null);
            if (relaxation != null) {
                relaxationDays = relaxation.getRelaxationDays();
                relaxationCostVal = relaxation.getRelaxationCost();
            }
        }
        double totalCost = round2(periodCost + relaxationCostVal);

        Set<LocalDate> holidays = holidaysBetween(effectiveFrom, effectiveTo);
        int workingDays = 0;
        for (LocalDate d = effectiveFrom; !d.isAfter(effectiveTo); d = d.plusDays(1)) {
            if (!isWeekend(d) && !holidays.contains(d)) workingDays++;
        }

        Double monthlyRate = (assignment != null && rateYear != null)
                ? assignment.getRateCardByYear().get(rateYear)
                : null;
        MonthlyResourceCost periodEntry = new MonthlyResourceCost(
                resource.getResId(), resource.getName(), projectId,
                milestoneId, activityId,
                rateYear, periodLabel, effectiveFrom, effectiveTo,
                workingDays, presentDays, halfDays, absentDays,
                leaveCalc.paidLeaveDays(),
                calendarDays, unpaidLeaveDays, round2(calendarDays - unpaidLeaveDays),
                monthlyRate != null ? monthlyRate : 0d,
                perDayCost, deductedAmount, periodCost);

        return new ResourceCostSummary(
                resource.getResId(), resource.getName(), projectId, periodLabel,
                calendarDays, round2(totalPlannedCost), perDayCost,
                unpaidLeaveDays, round2(calendarDays - unpaidLeaveDays),
                deductedAmount, periodCost,
                relaxationDays, round2(relaxationCostVal), totalCost,
                List.of(periodEntry));
    }

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

        String orgId = (projectId == null || projectId.isBlank()) ? null
                : projectResourceRepository.findByProjectIdAndActiveTrue(projectId)
                        .stream().findFirst()
                        .map(ProjectResource::getOrganisationId)
                        .orElse(null);
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, orgId);
        LocalDate quarterStart = quarterLeaveResolver.quarterStart(year, quarter, cycleDay);
        LocalDate quarterEnd = quarterLeaveResolver.quarterEnd(year, quarter, cycleDay);

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
            String resourceOrgId = resourceRows.get(0).getOrganisationId();

            Set<LocalDate> absentDates = resourceRows.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.A)
                    .map(Attendance::getAttendanceDate)
                    .collect(Collectors.toSet());

            Set<LocalDate> halfDayDates = resourceRows.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.HD)
                    .map(Attendance::getAttendanceDate)
                    .collect(Collectors.toSet());

            Optional<ProjectResource> assignmentOpt = projectResourceRepository
                    .findByResource_ResIdAndProjectIdAndActiveTrue(resource.getResId(), resourceProjectId)
                    .or(() -> projectResourceRepository
                            .findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc(
                                    resource.getResId(), resourceProjectId)
                            .stream()
                            .findFirst());
            LocalDate joiningDate = assignmentOpt
                    .map(ProjectResource::getAssignmentStartDate)
                    .orElse(resource.getDateOfJoining());

            QuarterLeaveCalculation calculation = quarterLeaveResolver.calculate(
                    resource.getResId(), resource.getId(), resourceProjectId, resourceOrgId,
                    joiningDate, year, quarter,
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
