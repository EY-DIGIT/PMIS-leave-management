package com.example.leavemanagement.service;

import com.example.leavemanagement.client.ActivityDetailsClient;
import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.ActivityAttendanceReportResult;
import com.example.leavemanagement.dto.ActivityReplacementReport;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.AttendanceReportResult;
import com.example.leavemanagement.dto.AttendanceReportSummary;
import com.example.leavemanagement.dto.AttendanceReportTotals;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.ResourceCostResult;
import com.example.leavemanagement.dto.ResourceCostSummary;
import com.example.leavemanagement.dto.ResourceCostTotals;
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
    private final ActivityDetailsClient activityDetailsClient;

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
            ActivityDetailsClient activityDetailsClient) {
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
        this.activityDetailsClient = activityDetailsClient;
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
        ActivityDetailsResponse activity = null;
        if (activityId != null && !activityId.isBlank()) {
            activity = activityDetailsClient.getActivityDetails(activityId)
                    .orElseThrow(() -> new BadRequestException(
                            "Activity configuration could not be retrieved for activityId '" + activityId
                                    + "' from the projects Activity API (it may not exist or the service is "
                                    + "unavailable). Attendance upload aborted."));
            if (!activity.hasResources()) {
                throw new BadRequestException(
                        "The Activity API returned no designation configuration for activityId '" + activityId
                                + "'. Attendance cannot be validated against the activity, so the upload is aborted.");
            }
        }
        if (activity != null && activity.startDate() != null && activity.endDate() != null) {
            if (startDate.isBefore(activity.startDate()) || endDate.isAfter(activity.endDate())) {
                throw new BadRequestException(
                        "Upload period " + startDate + " to " + endDate
                                + " is outside the activity date range "
                                + activity.startDate() + " to " + activity.endDate() + ".");
            }
        }
        List<EmployeeAttendanceByDate> parsed = parser.parse(file, startDate, endDate);
        Map<String, LeavePolicyResponse> leavePolicies =
                validateResourcesAndFetchLeavePolicies(attendanceIds(parsed), projectId);

        if (activity != null) {
            validateActivityResources(activity, activityId, startDate, endDate, parsed, projectId);
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
     * halfDay} (hours), otherwise an 8h/4h default.
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
     * Validates the uploaded resources against the activity's planned configuration, fetched live
     * from the projects service (the single source of truth). The {@code resources} array defines
     * the required designation, quantity, and duration per designation.
     *
     * <p>Levels of validation:
     * <ol>
     *   <li>Per-resource: designation must be configured for the activity; the resource must have
     *       been active during the upload period.
     *   <li>Duration: the upload period must fall within the designation's planned span
     *       (activity start + duration months).
     *   <li>Count: per-designation uploaded count must not exceed the configured quantity; total
     *       uploaded must not exceed total configured.
     * </ol>
     * If the projects service returns no configuration, only the per-resource period-overlap checks
     * are performed.
     */
    private void validateActivityResources(
            ActivityDetailsResponse details, String activityId, LocalDate startDate, LocalDate endDate,
            List<EmployeeAttendanceByDate> parsed, String projectId) {
        String activityLabel = details.activityName() != null ? details.activityName() : activityId;
        boolean hasRequirements = details.hasResources();
        Map<String, Integer> required = hasRequirements ? details.requiredByDesignation() : Map.of();
        Map<String, Double> durations = hasRequirements ? details.durationByDesignation() : Map.of();

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
                        + "' which is not configured for activity '" + activityLabel + "'.");
                continue;
            }

            Double duration = designation != null ? durations.get(designation) : null;
            if (duration != null && details.startDate() != null) {
                LocalDate plannedEnd = plannedEndDate(details.startDate(), duration);
                if (endDate.isAfter(plannedEnd)) {
                    resourceErrors.add("Resource " + employee.attendanceId()
                            + " (designation '" + designation + "') has a planned duration of "
                            + duration + " month(s) from " + details.startDate()
                            + "; the attendance period end date " + endDate
                            + " is beyond the planned end " + plannedEnd + ".");
                    continue;
                }
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

        int totalRequired = details.totalRequired();
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
            msg.append("Attendance upload failed.\n\nActivity: ").append(activityLabel).append("\n\n");
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
            String projectId, String milestoneId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }

        LocalDate aStart = activity.startDate();
        LocalDate aEnd   = activity.endDate();
        int configuredCount = activity.totalRequired();
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;
        String periodLabel = aStart + " to " + aEnd;

        LocalDate reportStart = attendanceRepository
                .findMinDateByActivityIdAndDateBetween(activityId, aStart, aEnd).orElse(aStart);
        LocalDate reportEnd = attendanceRepository
                .findMaxDateByActivityIdAndDateBetween(activityId, aStart, aEnd).orElse(aEnd);

        List<MasterResource> uploadedResources = attendanceRepository
                .findDistinctResourcesByActivityIdAndDateBetween(activityId, reportStart, reportEnd);

        int months = Math.max(1, (int) Math.round(monthsBetween(aStart, aEnd)));
        int leaveLimit = resolveLeaveLimit(projectId, months);

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
                activityId, activityName, projectId, milestoneId,
                periodLabel, aStart, aEnd,
                reportStart, reportEnd, calendarDays,
                configuredCount, uploadedResources.size(),
                buildAttendanceTotals(rows, calendarDays), rows);
    }

    /**
     * Resource-replacement summary for one activity. A designation is "replaced" whenever more
     * distinct resources worked it (across the whole activity window) than its configured quantity:
     * {@code replacementCount = max(0, distinctResourceCount - configuredQuantity)}. Derived live from
     * the resources that actually have attendance under the activity — no separate history table.
     */
    @Transactional(readOnly = true)
    public ActivityReplacementReport activityReplacements(String projectId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;
        Map<String, Integer> required = activity.requiredByDesignation();

        List<MasterResource> resources = attendanceRepository
                .findDistinctResourcesByActivityIdAndDateBetween(activityId, activity.startDate(), activity.endDate());

        Map<String, List<ActivityReplacementReport.ReplacementResource>> byDesignation = new LinkedHashMap<>();
        for (MasterResource resource : resources) {
            ProjectResource assignment = resolveAssignmentForProject(resource.getResId(), projectId);
            String designation = assignment != null ? assignment.getRole() : "(unassigned)";
            byDesignation.computeIfAbsent(designation, d -> new ArrayList<>())
                    .add(new ActivityReplacementReport.ReplacementResource(
                            resource.getResId(),
                            resource.getName(),
                            assignment != null ? assignment.getAssignmentStartDate() : null,
                            assignment != null ? assignment.getAssignmentEndDate() : null,
                            assignment != null && assignment.isActive()));
        }

        int totalReplacements = 0;
        List<ActivityReplacementReport.DesignationReplacement> designations = new ArrayList<>();
        for (Map.Entry<String, List<ActivityReplacementReport.ReplacementResource>> entry : byDesignation.entrySet()) {
            List<ActivityReplacementReport.ReplacementResource> members = entry.getValue().stream()
                    .sorted(Comparator.comparing(
                            ActivityReplacementReport.ReplacementResource::joiningDate,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            int configuredQuantity = required.getOrDefault(entry.getKey(), 0);
            int distinctResourceCount = members.size();
            int replacementCount = Math.max(0, distinctResourceCount - configuredQuantity);
            totalReplacements += replacementCount;
            designations.add(new ActivityReplacementReport.DesignationReplacement(
                    entry.getKey(), configuredQuantity, distinctResourceCount, replacementCount, members));
        }

        return new ActivityReplacementReport(
                activityId, activityName, projectId,
                activity.startDate(), activity.endDate(),
                totalReplacements, designations);
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
        LocalDate fromDate = LocalDate.of(year, month, 1);
        LocalDate toDate = fromDate.withDayOfMonth(fromDate.lengthOfMonth());
        String periodLabel = fromDate.format(MONTH_YEAR);
        periodValidator.validate(fromDate, toDate, projectId, null, periodLabel);
        return latestAssignmentsActiveDuring(projectId, fromDate, toDate).stream()
                .map(ProjectResource::getResource)
                .map(resource -> {
                    QuarterLeaveCalculation calc = leaveForWindow(resource, projectId, fromDate, toDate);
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
        LocalDate fromDate = LocalDate.of(year, month, 1);
        LocalDate toDate = fromDate.withDayOfMonth(fromDate.lengthOfMonth());
        periodValidator.validate(fromDate, toDate, projectId, resourceId, fromDate.format(MONTH_YEAR));
        QuarterLeaveCalculation calc = leaveForWindow(resource, projectId, fromDate, toDate);
        return buildMonthlyCost(resource, projectId, fromDate, toDate, fromDate.format(MONTH_YEAR),
                new HashSet<>(calc.unpaidLeaveDates()), new HashSet<>(calc.unpaidHalfDayDates()));
    }

    /**
     * Resource cost for a single activity, scoped to the activity's execution window. Each resource's
     * {@code monthlyBreakdown} is split into monthly attendance cycles aligned to the activity start
     * day (e.g. start 07-Jan → 07-Jan..06-Feb, 07-Feb..06-Mar, … up to the activity end date).
     */
    @Transactional(readOnly = true)
    public ResourceCostResult activityCostReport(String projectId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        String periodLabel = aStart.format(PERIOD_DATE_FMT) + " to " + aEnd.format(PERIOD_DATE_FMT);
        List<LocalDate[]> cycles = monthlyCycles(aStart, aEnd);
        List<ResourceCostSummary> rows = latestAssignmentsActiveDuring(projectId, aStart, aEnd).stream()
                .map(ProjectResource::getResource)
                .map(resource -> buildActivityCostSummary(
                        resource, projectId, activityId, aStart, aEnd, cycles, periodLabel))
                .toList();
        return new ResourceCostResult(periodLabel, rows.size(), buildCostTotals(rows), rows);
    }

    /** Monthly attendance cycles from {@code start}, each start-day..(next start-day − 1), clamped to {@code end}. */
    private List<LocalDate[]> monthlyCycles(LocalDate start, LocalDate end) {
        List<LocalDate[]> cycles = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate cycleEnd = cursor.plusMonths(1).minusDays(1);
            if (cycleEnd.isAfter(end)) {
                cycleEnd = end;
            }
            cycles.add(new LocalDate[] {cursor, cycleEnd});
            cursor = cursor.plusMonths(1);
        }
        return cycles;
    }

    private ResourceCostSummary buildActivityCostSummary(
            MasterResource resource, String projectId, String activityId,
            LocalDate aStart, LocalDate aEnd, List<LocalDate[]> cycles, String periodLabel) {
        QuarterLeaveCalculation calc = leaveForWindow(resource, projectId, aStart, aEnd);
        Set<LocalDate> unpaidFull = new HashSet<>(calc.unpaidLeaveDates());
        Set<LocalDate> unpaidHalf = new HashSet<>(calc.unpaidHalfDayDates());

        List<MonthlyResourceCost> monthly = cycles.stream()
                .map(c -> buildMonthlyCost(resource, projectId, c[0], c[1],
                        c[0].format(PERIOD_DATE_FMT) + " to " + c[1].format(PERIOD_DATE_FMT),
                        unpaidFull, unpaidHalf))
                .toList();

        int totalCalendarDays = monthly.stream().mapToInt(MonthlyResourceCost::calendarDays).sum();
        double totalPlannedCost = monthly.stream().mapToDouble(m -> m.cost() + m.deductedAmount()).sum();
        double totalUnpaidLeaveDays = monthly.stream().mapToDouble(MonthlyResourceCost::unpaidLeaveDays).sum();
        double totalDeductedAmount = monthly.stream().mapToDouble(MonthlyResourceCost::deductedAmount).sum();

        double relaxationDays = 0;
        double relaxationCostVal = 0;
        if (projectId != null && activityId != null) {
            LeaveRelaxation relaxation = leaveRelaxationRepository
                    .findByResource_ResIdAndProjectIdAndActivityId(resource.getResId(), projectId, activityId)
                    .orElse(null);
            if (relaxation != null) {
                relaxationDays = relaxation.getRelaxationDays();
                relaxationCostVal = relaxation.getRelaxationCost();
            }
        }

        double perDayCost = totalCalendarDays > 0 ? round2(totalPlannedCost / totalCalendarDays) : 0d;
        double paidCalDays = totalCalendarDays - totalUnpaidLeaveDays;
        double deductedAmount = round2(totalDeductedAmount);
        double periodCost = round2(totalPlannedCost - totalDeductedAmount);
        double totalCost = round2(periodCost + relaxationCostVal);

        return new ResourceCostSummary(
                resource.getResId(), resource.getName(), projectId, periodLabel,
                totalCalendarDays, round2(totalPlannedCost), perDayCost,
                totalUnpaidLeaveDays, round2(paidCalDays), round2(paidCalDays),
                deductedAmount, periodCost,
                relaxationDays, round2(relaxationCostVal), totalCost,
                monthly);
    }

    /**
     * Yearly cost: pass {@code resourceId} for a single resource's summary, or {@code projectId}
     * for the project dashboard (one summary per active resource).
     */
    @Transactional(readOnly = true)
    public ResourceCostResult yearlyCostReport(String projectId, int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
        return periodCostReport(projectId, null, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    private ResourceCostTotals buildCostTotals(List<ResourceCostSummary> rows) {
        double totalCost         = round2(rows.stream().mapToDouble(ResourceCostSummary::totalCost).sum());
        double totalRelaxation   = round2(rows.stream().mapToDouble(ResourceCostSummary::relaxationCost).sum());
        double totalDeducted     = round2(rows.stream().mapToDouble(ResourceCostSummary::deductedAmount).sum());
        return new ResourceCostTotals(rows.size(), totalCost, totalRelaxation, totalDeducted);
    }

    /**
     * Paid/unpaid leave breakdown for a resource over an arbitrary {@code [start, end]} window. The
     * permissible quota is the project's quarterly allowance prorated to the window length; the quota
     * is shared with predecessor resources of the same designation (replacement inheritance).
     */
    private QuarterLeaveCalculation leaveForWindow(
            MasterResource resource, String projectId, LocalDate start, LocalDate end) {
        ProjectResource assignment = projectId == null ? null
                : resolveAssignmentForProject(resource.getResId(), projectId);
        String orgId = assignment != null ? assignment.getOrganisationId() : null;
        List<Attendance> rows = attendanceRepository
                .findByResourceIdAndAttendanceDateBetween(resource.getId(), start, end);
        Set<LocalDate> absentDates = rows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        Set<LocalDate> halfDayDates = rows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.HD)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        double quota = quarterLeaveResolver.activityLeaveQuota(projectId, monthsBetween(start, end));
        return quarterLeaveResolver.calculateForWindow(
                resource.getResId(), resource.getId(), projectId, orgId,
                start, end, quota, absentDates, halfDayDates);
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
        Double activityRate = activityMonthlyRate(
                attendance.activityId(), assignment != null ? assignment.getRole() : null);
        if (activityRate != null) {
            monthlyRate = activityRate;
        }
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
                round2(paidCalendarDays),
                rate,
                perDayRate,
                deductedAmount,
                cost);
    }

    /**
     * Monthly paid-leave allowance per resource: uses {@code leavesPerFrequencyCount} directly
     * when {@code leavesFrequency=MONTHLY}; divides quarterly/yearly counts proportionally.
     * Falls back to 0 (no policy = no credit).
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

    /**
     * The last date covered by a planned duration in months from {@code start}, honoring fractional
     * months (e.g. 1.5 = one whole month plus half of the following month's days). The result is
     * inclusive: a duration of 2.0 from the 11th ends on the 10th two months later.
     */
    private LocalDate plannedEndDate(LocalDate start, double durationMonths) {
        int wholeMonths = (int) Math.floor(durationMonths);
        double fraction = durationMonths - wholeMonths;
        LocalDate afterWhole = start.plusMonths(wholeMonths);
        int extraDays = (int) Math.round(fraction * afterWhole.lengthOfMonth());
        return afterWhole.plusDays(extraDays).minusDays(1);
    }

    /**
     * Inclusive duration of {@code [start, end]} expressed in months as a fraction — the inverse of
     * {@link #plannedEndDate}. e.g. 11-May → 10-Aug = 3.0; 11-May → 25-Jun ≈ 1.5.
     */
    private double monthsBetween(LocalDate start, LocalDate end) {
        java.time.Period p = java.time.Period.between(start, end.plusDays(1));
        return p.getYears() * 12 + p.getMonths() + p.getDays() / 30.0;
    }

    /**
     * The activity's planned monthly rate for a designation, fetched live from the projects service.
     * Returns null when the cost is not activity-scoped (no activityId) or the API has no rate for
     * that designation — callers then fall back to the resource's rate card.
     */
    private Double activityMonthlyRate(String activityId, String designation) {
        if (activityId == null || activityId.isBlank() || designation == null) {
            return null;
        }
        return activityDetailsClient.getActivityDetails(activityId)
                .map(ActivityDetailsResponse::monthlyRateByDesignation)
                .map(rates -> rates.get(designation))
                .orElse(null);
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

        double quota = quarterLeaveResolver.activityLeaveQuota(projectId, monthsBetween(effectiveStart, effectiveEnd));
        QuarterLeaveCalculation leaveCalc = quarterLeaveResolver.calculateForWindow(
                resource.getResId(), resource.getId(), projectId, orgId,
                effectiveStart, effectiveEnd, quota, absentSet, halfDaySet);

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
     * Leave is period-scoped via {@link QuarterLeaveResolver#calculateForWindow}.
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

        double quota = quarterLeaveResolver.activityLeaveQuota(projectId, monthsBetween(effectiveFrom, effectiveTo));
        QuarterLeaveCalculation leaveCalc = quarterLeaveResolver.calculateForWindow(
                resource.getResId(), resource.getId(), projectId, orgId,
                effectiveFrom, effectiveTo, quota, absentSet, halfDaySet);

        Set<LocalDate> unpaidFull = new HashSet<>(leaveCalc.unpaidLeaveDates());
        Set<LocalDate> unpaidHalf = new HashSet<>(leaveCalc.unpaidHalfDayDates());
        double unpaidLeaveDays = unpaidFull.size() + unpaidHalf.size() * 0.5;

        double totalPlannedCost = 0d;
        double totalDeductedAmount = 0d;
        if (assignment != null) {
            Double activityRate = activityMonthlyRate(activityId, assignment.getRole());
            LocalDate segStart = effectiveFrom;
            while (!segStart.isAfter(effectiveTo)) {
                LocalDate monthEnd = segStart.withDayOfMonth(segStart.lengthOfMonth());
                LocalDate segEnd = monthEnd.isBefore(effectiveTo) ? monthEnd : effectiveTo;
                int daysInSegment = (int) (segEnd.toEpochDay() - segStart.toEpochDay()) + 1;
                String segRateYear = resolveRateYear(projectId, orgId, segStart,
                        assignment.getRateYear());
                Double segRate = activityRate != null ? activityRate
                        : (segRateYear != null ? assignment.getRateCardByYear().get(segRateYear) : null);
                if (segRate != null) {
                    totalPlannedCost += segRate * daysInSegment / segStart.lengthOfMonth();
                }
                segStart = segEnd.plusDays(1);
            }
            for (LocalDate d : unpaidFull) {
                if (!d.isBefore(effectiveFrom) && !d.isAfter(effectiveTo)) {
                    String dRateYear = resolveRateYear(projectId, orgId, d, assignment.getRateYear());
                    Double dRate = activityRate != null ? activityRate
                            : (dRateYear != null ? assignment.getRateCardByYear().get(dRateYear) : null);
                    if (dRate != null) totalDeductedAmount += dRate / d.lengthOfMonth();
                }
            }
            for (LocalDate d : unpaidHalf) {
                if (!d.isBefore(effectiveFrom) && !d.isAfter(effectiveTo)) {
                    String dRateYear = resolveRateYear(projectId, orgId, d, assignment.getRateYear());
                    Double dRate = activityRate != null ? activityRate
                            : (dRateYear != null ? assignment.getRateCardByYear().get(dRateYear) : null);
                    if (dRate != null) totalDeductedAmount += 0.5 * dRate / d.lengthOfMonth();
                }
            }
        }

        double perDayCost = calendarDays > 0 ? round2(totalPlannedCost / calendarDays) : 0d;
        double deductedAmount = round2(totalDeductedAmount);
        double periodCost = round2(totalPlannedCost - totalDeductedAmount);

        double relaxationDays = 0;
        double relaxationCostVal = 0;
        if (projectId != null && activityId != null) {
            LeaveRelaxation relaxation = leaveRelaxationRepository
                    .findByResource_ResIdAndProjectIdAndActivityId(
                            resource.getResId(), projectId, activityId)
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

        Double activityDisplayRate = activityMonthlyRate(
                activityId, assignment != null ? assignment.getRole() : null);
        Double monthlyRate = activityDisplayRate != null ? activityDisplayRate
                : ((assignment != null && rateYear != null) ? assignment.getRateCardByYear().get(rateYear) : null);
        MonthlyResourceCost periodEntry = new MonthlyResourceCost(
                resource.getResId(), resource.getName(), projectId,
                milestoneId, activityId,
                rateYear, periodLabel, effectiveFrom, effectiveTo,
                workingDays, presentDays, halfDays, absentDays,
                leaveCalc.paidLeaveDays(),
                calendarDays, unpaidLeaveDays, round2(calendarDays - unpaidLeaveDays),
                round2(calendarDays - unpaidLeaveDays),
                monthlyRate != null ? monthlyRate : 0d,
                perDayCost, deductedAmount, periodCost);

        return new ResourceCostSummary(
                resource.getResId(), resource.getName(), projectId, periodLabel,
                calendarDays, round2(totalPlannedCost), perDayCost,
                unpaidLeaveDays, round2(calendarDays - unpaidLeaveDays),
                round2(calendarDays - unpaidLeaveDays),
                deductedAmount, periodCost,
                relaxationDays, round2(relaxationCostVal), totalCost,
                List.of(periodEntry));
    }

}
