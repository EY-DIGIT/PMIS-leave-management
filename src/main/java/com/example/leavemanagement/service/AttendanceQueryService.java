package com.example.leavemanagement.service;

import com.example.leavemanagement.client.ActivityDetailsClient;
import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.ActivityAttendanceReportResult;
import com.example.leavemanagement.dto.ActivityAvailabilityReport;
import com.example.leavemanagement.dto.ActivityHolidayReport;
import com.example.leavemanagement.dto.ActivityReplacementOnboardingReport;
import com.example.leavemanagement.dto.ActivityReplacementOverlapReport;
import com.example.leavemanagement.dto.ActivityReplacementReport;
import com.example.leavemanagement.dto.ActivityResourceDetailsReport;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.AttendanceReportResult;
import com.example.leavemanagement.dto.AttendanceReportSummary;
import com.example.leavemanagement.dto.AttendanceReportTotals;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.ResourceAvailabilityReport;
import com.example.leavemanagement.dto.ResourceCostResult;
import com.example.leavemanagement.dto.ResourceCostSummary;
import com.example.leavemanagement.dto.ResourceCostTotals;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
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
import java.util.HashMap;
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
    private final ProjectYearMappingRepository yearMappingRepository;
    private final ActivityDetailsClient activityDetailsClient;
    private final ResourceBasedPeriodService resourceBasedPeriodService;

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
            ActivityDetailsClient activityDetailsClient,
            ResourceBasedPeriodService resourceBasedPeriodService) {
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
        this.resourceBasedPeriodService = resourceBasedPeriodService;
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

        // Incremental upsert: for each uploaded (resource, date) update the existing row or insert a new
        // one. Rows for other resources, other dates, or other months are never touched — no bulk delete.
        int stored = 0;
        for (EmployeeAttendanceByDate employee : parsed) {
            MasterResource resource = masterResourceRepository.findByResId(employee.attendanceId()).orElseThrow();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (isWeekend(date) || holidays.contains(date)) {
                    continue;
                }
                Integer workedMinutes = employee.workedMinutesByDate().get(date);
                AttendanceStatus status;
                Double workingHours;
                if (workedMinutes != null && workedMinutes > 0) {
                    status = workedMinutes >= thresholds[0] ? AttendanceStatus.P : AttendanceStatus.HD;
                    workingHours = Math.round(workedMinutes / 60.0 * 100) / 100.0;
                } else {
                    status = AttendanceStatus.A;
                    workingHours = null;
                }
                final AttendanceStatus rowStatus = status;
                final LocalDate rowDate = date;
                // Upsert within the full context (project + milestone + activity) so the same resource/date
                // under a different milestone/activity is a separate row, not an overwrite.
                Attendance row = attendanceRepository
                        .findForUpsert(resource.getId(), projectId, milestoneId, activityId, rowDate)
                        .orElseGet(() -> new Attendance(
                                resource, projectId, organisationId, milestoneId, activityId, rowDate, rowStatus));
                row.setProjectId(projectId);
                row.setOrganisationId(organisationId);
                row.setMilestoneId(milestoneId);
                row.setActivityId(activityId);
                row.setStatus(status);
                row.setWorkingHours(workingHours);
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
        Map<String, LocalDate> plannedDeployment =
                hasRequirements ? details.plannedDeploymentByDesignation() : Map.of();

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

            // Validate the activity's per-designation quota against the DESIGNATION AS UPLOADED in the
            // attendance sheet (column C), not the resource's stored assignment role — the sheet is the
            // authoritative statement of which designation this attendance is being uploaded for.
            String designation = employee.designation() != null ? employee.designation().trim() : null;
            if (hasRequirements && (designation == null || !required.containsKey(designation))) {
                resourceErrors.add("Resource " + employee.attendanceId()
                        + " has designation '" + designation
                        + "' which is not configured for activity '" + activityLabel + "'.");
                continue;
            }

            Double duration = designation != null ? durations.get(designation) : null;
            // Anchor the planned window to the designation's PLANNED DEPLOYMENT DATE (staggered resources
            // deploy after the activity start); fall back to the activity start when none is configured.
            LocalDate deploymentStart = plannedDeployment.getOrDefault(designation, details.startDate());
            if (duration != null && deploymentStart != null) {
                LocalDate plannedEnd = plannedEndDate(deploymentStart, duration);
                // Check the resource's OWN last attendance in this upload, not the whole batch's end date,
                // so a resource whose real attendance stays within its window passes in a longer batch.
                LocalDate lastAttendance = lastAttendanceDate(employee);
                LocalDate checkDate = lastAttendance != null ? lastAttendance : endDate;
                if (checkDate.isAfter(plannedEnd)) {
                    resourceErrors.add("Resource " + employee.attendanceId()
                            + " (designation '" + designation + "') has a planned duration of "
                            + duration + " month(s) from " + deploymentStart
                            + "; attendance on " + checkDate
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

        // One row per designation assignment period (promotion → two rows: e.g. PM then PD), each scoped to
        // its own period and computed with its own designation/leave/rate. The displayed attendance window
        // is bounded by the latest uploaded attendance date (reportEnd) so the report grows month-by-month
        // with each upload; the leave quota still uses the full activity window (aEnd) so proration is
        // correct even when only one month is uploaded.
        List<AttendanceReportSummary> rows = uploadedResources.stream()
                .flatMap(resource ->
                        activityAssignmentRows(resource, projectId, activityId, aStart, aEnd, reportEnd, periodLabel).stream())
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
     * The report rows for one resource within an activity — one per designation assignment period that has
     * attendance for this activity, scoped to {@code [max(assignStart, aStart), min(assignEnd, aEnd)]}. This
     * yields separate rows for a promotion (Program Manager then Program Director) and excludes assignment
     * periods with no attendance under this activity (e.g. after a transfer to another activity).
     */
    private List<AttendanceReportSummary> activityAssignmentRows(
            MasterResource resource, String projectId, String activityId,
            LocalDate aStart, LocalDate aEnd, LocalDate reportEnd, String periodLabel) {
        List<AttendanceReportSummary> rows = new ArrayList<>();
        for (ProjectResource a : projectResourceRepository
                .findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc(resource.getResId(), projectId)) {
            LocalDate start = a.getAssignmentStartDate();
            if (start == null) {
                continue;
            }
            LocalDate effStart = start.isAfter(aStart) ? start : aStart;
            // Full activity-scoped assignment window — drives the leave quota/proration.
            LocalDate effEnd = (a.getAssignmentEndDate() != null && a.getAssignmentEndDate().isBefore(aEnd))
                    ? a.getAssignmentEndDate() : aEnd;
            if (effStart.isAfter(effEnd)) {
                continue;
            }
            if (!attendanceRepository.existsByResIdAndActivityIdAndDateBetween(
                    resource.getResId(), activityId, effStart, effEnd)) {
                continue;
            }
            // Displayed window is bounded by the latest uploaded attendance date so calendar/working/
            // present counts reflect only what's been uploaded; skip if nothing uploaded yet in this window.
            LocalDate displayEnd = effEnd.isBefore(reportEnd) ? effEnd : reportEnd;
            if (effStart.isAfter(displayEnd)) {
                continue;
            }
            rows.add(buildSummary(resource, projectId, effStart, displayEnd, periodLabel,
                    a.getRole(), start, a.isActive(), a.getAssignmentEndDate(), effStart, effEnd, activityId));
        }
        if (rows.isEmpty()) {
            // No assignment period matched (e.g. assignment history not available) — one row over the activity.
            ProjectResource a = resolveAssignmentForProject(resource.getResId(), projectId);
            LocalDate displayEnd = aEnd.isBefore(reportEnd) ? aEnd : reportEnd;
            rows.add(buildSummary(resource, projectId, aStart, displayEnd, periodLabel,
                    a != null ? a.getRole() : null,
                    a != null ? a.getAssignmentStartDate() : null,
                    a != null && a.isActive(),
                    a != null ? a.getAssignmentEndDate() : null,
                    aStart, aEnd, activityId));
        }
        return rows;
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

    /**
     * UIDAI SLA 006 (Resource Replacement Overlap) for one activity. For each replacement — an outgoing
     * assignment that names an incoming resource in {@code replacedByResId} — it computes the overlap
     * window {@code [incoming joining date, outgoing last working date]} and counts the <b>working
     * days</b> in it against the UIDAI working-day/holiday calendar (weekends and public holidays
     * excluded), not raw calendar days. If the incoming resource joins after the outgoing resource's
     * last day there is no overlap, so working days = 0. Returns only the SLA result string — never a
     * severity level.
     */
    @Transactional(readOnly = true)
    public ActivityReplacementOverlapReport activityReplacementOverlaps(String projectId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;

        List<ActivityReplacementOverlapReport.ReplacementOverlap> overlaps = new ArrayList<>();
        for (ProjectResource outgoing : projectResourceRepository.findByProjectId(projectId)) {
            String incomingResId = outgoing.getReplacedByResId();
            if (incomingResId == null || incomingResId.isBlank()) {
                continue;
            }
            String outgoingResId = outgoing.getResource().getResId();
            // Scope to this activity: the outgoing resource must have worked on it.
            if (!attendanceRepository.existsByResIdAndActivityIdAndDateBetween(
                    outgoingResId, activityId, aStart, aEnd)) {
                continue;
            }
            ProjectResource incoming = incomingAssignment(incomingResId, projectId, outgoing.getRole());
            LocalDate overlapStart = incoming != null ? incoming.getAssignmentStartDate() : null; // joining
            LocalDate overlapEnd = outgoing.getAssignmentEndDate();                                // last day

            int workingDays = (overlapStart != null && overlapEnd != null && !overlapStart.isAfter(overlapEnd))
                    ? workingDaysBetween(overlapStart, overlapEnd)
                    : 0;
            String result = workingDays >= 20
                    ? "Overlap >= 20 Working Days"
                    : "Overlap < 20 Working Days";

            overlaps.add(new ActivityReplacementOverlapReport.ReplacementOverlap(
                    "SLA006",
                    outgoingResId, outgoing.getResource().getName(), outgoing.getRole(), overlapEnd,
                    incomingResId,
                    incoming != null ? incoming.getResource().getName() : null,
                    incoming != null ? incoming.getRole() : outgoing.getRole(),
                    overlapStart, overlapStart, overlapEnd, workingDays, result));
        }
        return new ActivityReplacementOverlapReport(
                projectId, activityId, activityName, overlaps.size(), overlaps);
    }

    /**
     * UIDAI SLA 009 (Delay in Onboarding of Replacement Resource) for one activity. For each
     * replacement it computes the onboarding delay {@code mobilization − notification} in <b>calendar
     * days</b> (the SLA says "21 Days", not working days) and returns the result: "Within 21 Days"
     * ({@code <= 21}), "More than 21 Days" ({@code > 21}), or "Manual" when the notification date was
     * not captured. Never a severity level.
     */
    @Transactional(readOnly = true)
    public ActivityReplacementOnboardingReport activityReplacementOnboarding(String projectId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;

        List<ActivityReplacementOnboardingReport.ReplacementOnboarding> rows = new ArrayList<>();
        for (ProjectResource outgoing : projectResourceRepository.findByProjectId(projectId)) {
            String incomingResId = outgoing.getReplacedByResId();
            if (incomingResId == null || incomingResId.isBlank()) {
                continue;
            }
            String outgoingResId = outgoing.getResource().getResId();
            if (!attendanceRepository.existsByResIdAndActivityIdAndDateBetween(
                    outgoingResId, activityId, aStart, aEnd)) {
                continue;
            }
            ProjectResource incoming = incomingAssignment(incomingResId, projectId, outgoing.getRole());
            LocalDate notificationDate = outgoing.getReplacementNotifiedDate();
            LocalDate mobilizationDate = incoming != null ? incoming.getAssignmentStartDate() : null;

            Integer onboardingDays;
            String result;
            if (notificationDate == null) {
                onboardingDays = null;
                result = "Manual";
            } else if (mobilizationDate == null) {
                onboardingDays = null;
                result = "Manual";
            } else {
                onboardingDays = (int) java.time.temporal.ChronoUnit.DAYS.between(notificationDate, mobilizationDate);
                result = onboardingDays <= 21 ? "Within 21 Days" : "More than 21 Days";
            }

            rows.add(new ActivityReplacementOnboardingReport.ReplacementOnboarding(
                    "SLA009",
                    outgoingResId, outgoing.getResource().getName(), outgoing.getRole(),
                    incomingResId,
                    incoming != null ? incoming.getResource().getName() : null,
                    incoming != null ? incoming.getRole() : outgoing.getRole(),
                    notificationDate, mobilizationDate, onboardingDays, result));
        }
        return new ActivityReplacementOnboardingReport(
                projectId, activityId, activityName, rows.size(), rows);
    }

    /** The incoming resource's assignment for the replaced designation (earliest matching, else latest). */
    private ProjectResource incomingAssignment(String incomingResId, String projectId, String role) {
        List<ProjectResource> assignments = projectResourceRepository
                .findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc(incomingResId, projectId);
        return assignments.stream()
                .filter(a -> role == null || role.equals(a.getRole()))
                .filter(a -> a.getAssignmentStartDate() != null)
                .min(Comparator.comparing(ProjectResource::getAssignmentStartDate))
                .orElseGet(() -> assignments.stream().findFirst().orElse(null));
    }

    /** Working days (weekdays that are not public holidays) in {@code [start, end]}, UIDAI calendar. */
    private int workingDaysBetween(LocalDate start, LocalDate end) {
        Set<LocalDate> holidays = holidaysBetween(start, end);
        int count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (!isWeekend(d) && !holidays.contains(d)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Public holidays within an activity's window. Plain weekends are excluded; a holiday on a weekend
     * is tagged {@code WEEKEND_HOLIDAY}, a holiday on a weekday {@code HOLIDAY}.
     */
    @Transactional(readOnly = true)
    public ActivityHolidayReport activityHolidays(String projectId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;

        List<ActivityHolidayReport.Holiday> holidays = new ArrayList<>();
        int weekdayCount = 0;
        int weekendCount = 0;
        for (PublicHoliday h : holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(aStart, aEnd)) {
            LocalDate d = h.getHolidayDate();
            boolean weekend = isWeekend(d);
            if (weekend) {
                weekendCount++;
            } else {
                weekdayCount++;
            }
            String dayName = d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
            String type = weekend ? "WEEKEND_HOLIDAY" : "HOLIDAY";
            holidays.add(new ActivityHolidayReport.Holiday(d, dayName, type, h.getName()));
        }

        return new ActivityHolidayReport(
                activityId, activityName, aStart, aEnd,
                holidays.size(), weekdayCount, weekendCount, holidays);
    }

    /**
     * Resource history for a project + designation (+ optional organisation): every resource holding
     * that designation, each with the list of activities they worked and the period worked on each
     * (assignment window ∩ activity window) plus current status. Lets the UI see who has worked a
     * designation before deciding whether an uploaded resource is a continuation, a new deployment, or
     * a replacement.
     */
    @Transactional(readOnly = true)
    public ActivityResourceDetailsReport resourceDetailsByDesignation(
            String projectId, String designation, String organisationId) {
        boolean hasOrg = organisationId != null && !organisationId.isBlank();
        Map<Long, ProjectResource> latestByResource = new LinkedHashMap<>();
        projectResourceRepository.findByProjectIdAndRole(projectId, designation).stream()
                .filter(a -> !hasOrg || organisationId.equals(a.getOrganisationId()))
                .sorted(Comparator.comparing(ProjectResource::getAssignmentStartDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(a -> latestByResource.put(a.getResource().getId(), a));

        Map<String, Optional<ActivityDetailsResponse>> activityCache = new HashMap<>();
        List<ActivityResourceDetailsReport.ResourceHistory> resources = new ArrayList<>();
        for (ProjectResource assignment : latestByResource.values()) {
            MasterResource resource = assignment.getResource();
            LocalDate assignStart = assignment.getAssignmentStartDate();
            LocalDate assignEnd = assignment.getAssignmentEndDate();
            String status = assignment.isActive() ? "Active" : "Completed";

            List<ActivityResourceDetailsReport.ActivityWork> works = new ArrayList<>();
            for (String activityId : attendanceRepository.findDistinctActivityIdsByResourceId(resource.getId())) {
                ActivityDetailsResponse activity = activityCache
                        .computeIfAbsent(activityId, id -> activityDetailsClient.getActivityDetails(id))
                        .orElse(null);
                if (activity == null || activity.startDate() == null || activity.endDate() == null) {
                    continue;
                }
                LocalDate aStart = activity.startDate();
                LocalDate aEnd = activity.endDate();
                LocalDate workedFrom = (assignStart != null && assignStart.isAfter(aStart)) ? assignStart : aStart;
                LocalDate workedTo = (assignEnd != null && assignEnd.isBefore(aEnd)) ? assignEnd : aEnd;
                works.add(new ActivityResourceDetailsReport.ActivityWork(
                        activityId,
                        activity.activityName() != null ? activity.activityName() : activityId,
                        aStart, aEnd, workedFrom, workedTo, status));
            }
            works.sort(Comparator.comparing(
                    ActivityResourceDetailsReport.ActivityWork::activityStartDate,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            resources.add(new ActivityResourceDetailsReport.ResourceHistory(
                    resource.getResId(), resource.getName(), works));
        }

        return new ActivityResourceDetailsReport(projectId, organisationId, designation, resources);
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
        List<AttendanceReportSummary> rows = latestAssignmentsActiveDuring(projectId, start, end).stream()
                .filter(pr -> organisationId == null || organisationId.isBlank()
                        || organisationId.equals(pr.getOrganisationId()))
                .map(pr -> buildSummary(pr.getResource(), projectId, start, end, periodLabel,
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
        return buildSummary(resource, projectId, start, end, periodLabel, designation, joiningDate, active, lastWorkingDate);
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
            return new AttendanceReportTotals(0, calendarDays, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
        int sandwichSum        = rows.stream().mapToInt(AttendanceReportSummary::sandwichDays).sum();
        double avgAtt          = Math.round(
                rows.stream().mapToDouble(AttendanceReportSummary::attendancePercentage).average().orElse(0) * 100)
                / 100.0;
        return new AttendanceReportTotals(
                rows.size(), calendarDays, workingDays, presentSum, halfSum, leaveSum, absentSum, wfhSum,
                leaveTakenSum, paidSum, unpaidSum, sandwichSum, avgAtt);
    }

    private AttendanceReportSummary buildSummary(
            MasterResource resource, String projectId, LocalDate start, LocalDate end, String periodLabel,
            String designation, LocalDate joiningDate, boolean active, LocalDate lastWorkingDate) {
        // Default: leave is computed over the same window shown (monthly/yearly/period reports); no activity filter.
        return buildSummary(resource, projectId, start, end, periodLabel,
                designation, joiningDate, active, lastWorkingDate, start, end, (String) null);
    }

    /**
     * {@code leaveStart}/{@code leaveEnd} is the window the leave quota + paid/unpaid split is computed
     * over — which may be wider than the displayed {@code start}/{@code end}. The activity report shows a
     * cumulative snapshot (only the uploaded months) but leave belongs to the whole activity, so it passes
     * the full activity window here; otherwise the quota would be prorated down to the uploaded months.
     *
     * <p>{@code activityId} (nullable): when set, only attendance for that activity is counted — so a
     * resource who transferred to another activity doesn't have the other activity's days leak in.
     */
    private AttendanceReportSummary buildSummary(
            MasterResource resource, String projectId, LocalDate start, LocalDate end, String periodLabel,
            String designation, LocalDate joiningDate, boolean active, LocalDate lastWorkingDate,
            LocalDate leaveStart, LocalDate leaveEnd, String filterActivityId) {
        LocalDate effectiveStart = (joiningDate != null && joiningDate.isAfter(start)) ? joiningDate : start;
        LocalDate effectiveEnd = (lastWorkingDate != null && lastWorkingDate.isBefore(end)) ? lastWorkingDate : end;
        // Resource not active in this window (joined after it, or left before it) → no active days.
        int totalDays = Math.max(0, (int) (effectiveEnd.toEpochDay() - effectiveStart.toEpochDay()) + 1);
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

        List<Attendance> rows = attendanceRows(resource.getId(), filterActivityId, effectiveStart, effectiveEnd);

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

        QuarterLeaveCalculation calc =
                leaveForWindow(resource, projectId, designation, joiningDate, leaveStart, leaveEnd, filterActivityId);
        double paidLeaveDays   = calc.paidLeaveDays();
        double unpaidLeaveDays = calc.unpaidLeaveDays();
        int sandwichDays       = calc.sandwichDays();
        // Total leave taken = paid + unpaid + sandwich (sandwich-charged holiday/weekend days included).
        double leaveTaken = paidLeaveDays + unpaidLeaveDays + sandwichDays;

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
                leaveTaken,
                paidLeaveDays,
                unpaidLeaveDays,
                sandwichDays);
    }

    private void validateMonthAndYear(int year, int month) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("month must be between 1 and 12");
        }
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
    }

    /**
     * Monthly resource-availability report for UIDAI SLA 007 (Minimum Resource Availability): the
     * business days attended and total working hours logged per resource in the month, with the
     * derived SLA severity level. Pass {@code resourceId} to scope to one resource, or leave it blank
     * for every resource active on the project during the month.
     */
    @Transactional(readOnly = true)
    public ResourceAvailabilityReport availabilityReport(
            String projectId, int year, int month, String resourceId) {
        validateMonthAndYear(year, month);
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        String periodLabel = from.format(MONTH_YEAR);
        periodValidator.validate(from, to, projectId, resourceId, periodLabel);

        boolean hasResourceFilter = resourceId != null && !resourceId.isBlank();
        List<ResourceAvailabilityReport.ResourceAvailability> rows =
                latestAssignmentsActiveDuring(projectId, from, to).stream()
                        .filter(pr -> !hasResourceFilter || resourceId.equals(pr.getResource().getResId()))
                        .map(pr -> buildAvailability(pr, from, to))
                        .toList();
        return new ResourceAvailabilityReport(projectId, year, month, periodLabel, rows.size(), rows);
    }

    private ResourceAvailabilityReport.ResourceAvailability buildAvailability(
            ProjectResource assignment, LocalDate from, LocalDate to) {
        MasterResource resource = assignment.getResource();
        LocalDate joiningDate = assignment.getAssignmentStartDate();
        LocalDate lastWorkingDate = assignment.getAssignmentEndDate();
        LocalDate effStart = (joiningDate != null && joiningDate.isAfter(from)) ? joiningDate : from;
        LocalDate effEnd = (lastWorkingDate != null && lastWorkingDate.isBefore(to)) ? lastWorkingDate : to;

        List<Attendance> rows = effStart.isAfter(effEnd) ? List.of()
                : attendanceRepository.findByResourceIdAndAttendanceDateBetween(resource.getId(), effStart, effEnd);
        AvailabilityCounts c = availabilityOf(rows);

        return new ResourceAvailabilityReport.ResourceAvailability(
                resource.getResId(), resource.getName(), assignment.getRole(),
                c.businessDays(), c.presentDays(), c.totalHours(),
                slaSeverity(c.businessDays(), c.totalHours()));
    }

    /**
     * Activity-scoped monthly availability report (UIDAI SLA 007 input). Filtered by project +
     * activity, it returns a calendar-month breakdown where each month <em>aggregates across all
     * resources</em>: total business days attended and total working hours logged that month, plus the
     * number of resources that contributed. Only months with uploaded attendance appear, so the
     * breakup grows month-by-month with each upload.
     */
    @Transactional(readOnly = true)
    public ActivityAvailabilityReport activityAvailabilityReport(String projectId, String activityId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        String activityName = activity.activityName() != null ? activity.activityName() : activityId;
        String periodLabel = aStart.format(PERIOD_DATE_FMT) + " to " + aEnd.format(PERIOD_DATE_FMT);
        // Monthly cycles are aligned to the activity start day (e.g. 07-Jan → 06-Feb, 07-Feb → 06-Mar,
        // …), not calendar months. Only cycles that have begun uploading appear, so the breakup grows
        // with each upload.
        LocalDate maxUploaded = attendanceRepository
                .findMaxDateByActivityIdAndDateBetween(activityId, aStart, aEnd).orElse(null);

        List<ActivityAvailabilityReport.MonthlyAvailability> months = new ArrayList<>();
        for (LocalDate[] cycle : cyclesUpTo(aStart, aEnd, maxUploaded)) {
            LocalDate cStart = cycle[0];
            LocalDate cEnd = cycle[1];
            List<Attendance> rows = attendanceRepository
                    .findByActivityIdAndAttendanceDateBetween(activityId, cStart, cEnd);
            if (rows.isEmpty()) {
                continue;
            }
            AvailabilityCounts c = availabilityOf(rows);
            int resourceCount = (int) rows.stream()
                    .map(a -> a.getResource().getId()).distinct().count();
            months.add(new ActivityAvailabilityReport.MonthlyAvailability(
                    cStart.getYear(), cStart.getMonthValue(),
                    cStart.format(PERIOD_DATE_FMT) + " to " + cEnd.format(PERIOD_DATE_FMT),
                    cStart, cEnd, resourceCount,
                    c.businessDays(), c.presentDays(), c.totalHours()));
        }
        return new ActivityAvailabilityReport(
                projectId, activityId, activityName, periodLabel, aStart, aEnd, months.size(), months);
    }

    /** Present/half/WFH day counts and total logged hours for a set of attendance rows. */
    private record AvailabilityCounts(int businessDays, double presentDays, double totalHours) {}

    private AvailabilityCounts availabilityOf(List<Attendance> rows) {
        int presentRaw = 0;
        int halfDays = 0;
        int wfhDays = 0;
        double totalHours = 0.0;
        for (Attendance a : rows) {
            switch (a.getStatus()) {
                case P -> presentRaw++;
                case HD -> halfDays++;
                case WFH -> wfhDays++;
                default -> { /* A / L / etc. — no attended day */ }
            }
            if (a.getWorkingHours() != null) {
                totalHours += a.getWorkingHours();
            }
        }
        // Business days = days the resource actually logged attendance (present, half-day, or WFH).
        return new AvailabilityCounts(presentRaw + halfDays + wfhDays, presentRaw + halfDays * 0.5, round2(totalHours));
    }

    /**
     * UIDAI SLA 007 applied severity: 0 when {@code businessDays >= 16 and hours >= 144}; 2 when
     * {@code businessDays >= 12 and hours >= 108}; otherwise 4. Monotonic reading of the SLA tiers.
     */
    private static int slaSeverity(int businessDays, double hours) {
        if (businessDays >= 16 && hours >= 144) {
            return 0;
        }
        if (businessDays >= 12 && hours >= 108) {
            return 2;
        }
        return 4;
    }

    /** Dashboard: one month's cost per resource active on the project during that month. */
    @Transactional(readOnly = true)
    public List<MonthlyResourceCost> monthlyCostReport(String projectId, int year, int month) {
        validateMonthAndYear(year, month);
        LocalDate fromDate = LocalDate.of(year, month, 1);
        LocalDate toDate = fromDate.withDayOfMonth(fromDate.lengthOfMonth());
        String periodLabel = fromDate.format(MONTH_YEAR);
        periodValidator.validate(fromDate, toDate, projectId, null, periodLabel);
        ResourceBasedPeriodService.ResourceBasedPeriod window = resourceBasedPeriodService.resolve(projectId).orElse(null);
        return latestAssignmentsActiveDuring(projectId, fromDate, toDate).stream()
                .map(ProjectResource::getResource)
                .map(resource -> {
                    QuarterLeaveCalculation calc = leaveForWindow(resource, projectId, fromDate, toDate);
                    return buildMonthlyCost(resource, projectId, fromDate, toDate, periodLabel,
                            new HashSet<>(calc.unpaidLeaveDates()),
                            new HashSet<>(calc.unpaidHalfDayDates()),
                            new HashSet<>(calc.sandwichDates()),
                            Set.of(), window);
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
        ResourceBasedPeriodService.ResourceBasedPeriod window = resourceBasedPeriodService.resolve(projectId).orElse(null);
        return buildMonthlyCost(resource, projectId, fromDate, toDate, fromDate.format(MONTH_YEAR),
                new HashSet<>(calc.unpaidLeaveDates()), new HashSet<>(calc.unpaidHalfDayDates()),
                new HashSet<>(calc.sandwichDates()), Set.of(), window);
    }

    /**
     * Resource cost for a single activity, scoped to the activity's execution window. Each resource's
     * {@code monthlyBreakdown} is split into monthly attendance cycles aligned to the activity start
     * day (e.g. start 07-Jan → 07-Jan..06-Feb, 07-Feb..06-Mar, … up to the activity end date).
     */
    @Transactional(readOnly = true)
    public ResourceCostResult activityCostReport(String projectId, String activityId, String resourceId) {
        ActivityDetailsResponse activity = activityDetailsClient.getActivityDetails(activityId)
                .orElseThrow(() -> new NotFoundException("No activity found with id '" + activityId + "'"));
        if (activity.startDate() == null || activity.endDate() == null) {
            throw new BadRequestException("Activity '" + activityId + "' has no start/end date.");
        }
        LocalDate aStart = activity.startDate();
        LocalDate aEnd = activity.endDate();
        // Only include monthly cycles that have actually been uploaded: bound by the latest
        // attendance date for this activity, so the breakup grows month-by-month with each upload.
        LocalDate maxUploaded = attendanceRepository
                .findMaxDateByActivityIdAndDateBetween(activityId, aStart, aEnd).orElse(null);
        String periodLabel = aStart.format(PERIOD_DATE_FMT) + " to " + aEnd.format(PERIOD_DATE_FMT);
        boolean hasResourceFilter = resourceId != null && !resourceId.isBlank();
        ResourceBasedPeriodService.ResourceBasedPeriod window = resourceBasedPeriodService.resolve(projectId).orElse(null);
        List<ResourceCostSummary> rows = latestAssignmentsActiveDuring(projectId, aStart, aEnd).stream()
                .map(ProjectResource::getResource)
                .filter(resource -> !hasResourceFilter || resourceId.equals(resource.getResId()))
                .flatMap(resource ->
                        activityCostRows(resource, projectId, activityId, aStart, aEnd, maxUploaded, window).stream())
                .toList();
        return new ResourceCostResult(periodLabel, rows.size(), buildCostTotals(rows), rows);
    }

    /** One cost summary per designation assignment period (promotion → PM cost + PD cost, each own rate). */
    private List<ResourceCostSummary> activityCostRows(
            MasterResource resource, String projectId, String activityId,
            LocalDate aStart, LocalDate aEnd, LocalDate maxUploaded,
            ResourceBasedPeriodService.ResourceBasedPeriod window) {
        List<ResourceCostSummary> out = new ArrayList<>();
        for (ProjectResource a : projectResourceRepository
                .findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc(resource.getResId(), projectId)) {
            LocalDate start = a.getAssignmentStartDate();
            if (start == null) {
                continue;
            }
            LocalDate effStart = start.isAfter(aStart) ? start : aStart;
            LocalDate effEnd = (a.getAssignmentEndDate() != null && a.getAssignmentEndDate().isBefore(aEnd))
                    ? a.getAssignmentEndDate() : aEnd;
            if (effStart.isAfter(effEnd)
                    || !attendanceRepository.existsByResIdAndActivityIdAndDateBetween(
                            resource.getResId(), activityId, effStart, effEnd)) {
                continue;
            }
            out.add(buildActivityCostSummary(resource, a, projectId, activityId, effStart, effEnd,
                    cyclesUpTo(effStart, effEnd, maxUploaded),
                    effStart.format(PERIOD_DATE_FMT) + " to " + effEnd.format(PERIOD_DATE_FMT), window));
        }
        if (out.isEmpty()) {
            ProjectResource a = resolveAssignmentForProject(resource.getResId(), projectId);
            out.add(buildActivityCostSummary(resource, a, projectId, activityId, aStart, aEnd,
                    cyclesUpTo(aStart, aEnd, maxUploaded),
                    aStart.format(PERIOD_DATE_FMT) + " to " + aEnd.format(PERIOD_DATE_FMT), window));
        }
        return out;
    }

    private List<LocalDate[]> cyclesUpTo(LocalDate start, LocalDate end, LocalDate maxUploaded) {
        return maxUploaded == null ? List.of()
                : monthlyCycles(start, end).stream().filter(c -> !c[0].isAfter(maxUploaded)).toList();
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
            MasterResource resource, ProjectResource assignment, String projectId, String activityId,
            LocalDate effStart, LocalDate effEnd, List<LocalDate[]> cycles, String periodLabel,
            ResourceBasedPeriodService.ResourceBasedPeriod window) {
        String designation = assignment != null ? assignment.getRole() : null;
        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        QuarterLeaveCalculation calc =
                leaveForWindow(resource, projectId, designation, joiningDate, effStart, effEnd, activityId);
        Set<LocalDate> unpaidFull = new HashSet<>(calc.unpaidLeaveDates());
        Set<LocalDate> unpaidHalf = new HashSet<>(calc.unpaidHalfDayDates());
        Set<LocalDate> sandwich = new HashSet<>(calc.sandwichDates());
        Set<LocalDate> relaxationDatesSet = new HashSet<>();
        if (projectId != null && activityId != null) {
            leaveRelaxationRepository
                    .findByResource_ResIdAndProjectIdAndActivityId(resource.getResId(), projectId, activityId)
                    .ifPresent(r -> relaxationDatesSet.addAll(r.getRelaxationDates()));
        }

        List<MonthlyResourceCost> monthly = cycles.stream()
                .map(c -> buildMonthlyCost(resource, assignment, projectId, activityId, c[0], c[1],
                        c[0].format(PERIOD_DATE_FMT) + " to " + c[1].format(PERIOD_DATE_FMT),
                        unpaidFull, unpaidHalf, sandwich, relaxationDatesSet, window))
                // Only cycles the resource actually has attendance in (skip pre-joining / post-leaving months).
                .filter(m -> m.presentDays() > 0 || m.absentDays() > 0 || m.halfDays() > 0)
                .toList();

        int totalCalendarDays = monthly.stream().mapToInt(MonthlyResourceCost::calendarDays).sum();
        int totalActiveCalendarDays = monthly.stream().mapToInt(MonthlyResourceCost::activeCalendarDays).sum();
        double totalPlannedCost = monthly.stream().mapToDouble(m -> m.cost() + m.deductedAmount()).sum();
        double totalUnpaidDays = monthly.stream().mapToDouble(MonthlyResourceCost::totalUnpaidDays).sum();
        double totalDeductedAmount = monthly.stream().mapToDouble(MonthlyResourceCost::deductedAmount).sum();
        double totalRelaxationDays = monthly.stream().mapToDouble(MonthlyResourceCost::relaxationDays).sum();
        double totalRelaxationCost = monthly.stream().mapToDouble(MonthlyResourceCost::relaxationCost).sum();
        double totalBillableDays = monthly.stream().mapToDouble(MonthlyResourceCost::billableDays).sum();

        double perDayCost = totalActiveCalendarDays > 0 ? round2(totalPlannedCost / totalActiveCalendarDays) : 0d;
        double deductedAmount = round2(totalDeductedAmount);
        // Relaxation is already inside the reduced deduction (Rule #5) — no add-back.
        double periodCost = round2(totalPlannedCost - totalDeductedAmount);
        double totalCost = periodCost;

        return new ResourceCostSummary(
                resource.getResId(), resource.getName(), designation,
                assignment != null ? assignment.getAssignmentStartDate() : null,
                assignment != null ? assignment.getAssignmentEndDate() : null,
                projectId, periodLabel,
                totalCalendarDays, totalActiveCalendarDays, round2(totalPlannedCost), perDayCost,
                totalUnpaidDays, round2(totalBillableDays), round2(totalBillableDays),
                deductedAmount, periodCost,
                totalRelaxationDays, round2(totalRelaxationCost), totalCost,
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
    /** Attendance rows for a window — filtered to one activity when {@code activityId} is set, else all. */
    private List<Attendance> attendanceRows(Long resourceId, String activityId, LocalDate start, LocalDate end) {
        return (activityId == null || activityId.isBlank())
                ? attendanceRepository.findByResourceIdAndAttendanceDateBetween(resourceId, start, end)
                : attendanceRepository.findByResourceIdAndActivityIdAndAttendanceDateBetween(
                        resourceId, activityId, start, end);
    }

    /** Leave over a window for the resource's active/latest assignment (single-assignment callers). */
    private QuarterLeaveCalculation leaveForWindow(
            MasterResource resource, String projectId, LocalDate start, LocalDate end) {
        ProjectResource assignment = projectId == null ? null
                : resolveAssignmentForProject(resource.getResId(), projectId);
        return leaveForWindow(resource, projectId,
                assignment != null ? assignment.getRole() : null,
                assignment != null ? assignment.getAssignmentStartDate() : null,
                start, end, null);
    }

    /**
     * Leave over a window for a <b>specific</b> designation assignment — the designation and join date are
     * passed explicitly (not re-resolved), so a promoted resource's PM period and PD period are computed
     * independently with their own role-based chain and proration. When {@code filterActivityId} is set,
     * only that activity's attendance is counted (so a transfer to another activity doesn't leak in).
     */
    private QuarterLeaveCalculation leaveForWindow(
            MasterResource resource, String projectId, String designation, LocalDate joiningDate,
            LocalDate start, LocalDate end, String filterActivityId) {
        String orgId = projectId == null ? null
                : java.util.Optional.ofNullable(resolveAssignmentForProject(resource.getResId(), projectId))
                        .map(ProjectResource::getOrganisationId).orElse(null);
        List<Attendance> rows = attendanceRows(resource.getId(), filterActivityId, start, end);
        Set<LocalDate> absentDates = rows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        Set<LocalDate> halfDayDates = rows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.HD)
                .map(Attendance::getAttendanceDate).collect(Collectors.toSet());
        double quota = quarterLeaveResolver.activityLeaveQuota(projectId, monthsBetween(start, end));
        return quarterLeaveResolver.calculateForWindow(
                resource.getResId(), resource.getId(), projectId, orgId,
                designation, joiningDate, start, end, quota, absentDates, halfDayDates);
    }

    private MonthlyResourceCost buildMonthlyCost(
            MasterResource resource, String projectId, LocalDate fromDate, LocalDate toDate,
            String periodLabel, Set<LocalDate> unpaidFull, Set<LocalDate> unpaidHalf,
            Set<LocalDate> sandwichDates, Set<LocalDate> relaxationDates,
            ResourceBasedPeriodService.ResourceBasedPeriod window) {
        ProjectResource assignment = projectId == null
                ? null
                : resolveAssignmentForProject(resource.getResId(), projectId);
        return buildMonthlyCost(resource, assignment, projectId, null, fromDate, toDate, periodLabel,
                unpaidFull, unpaidHalf, sandwichDates, relaxationDates, window);
    }

    private MonthlyResourceCost buildMonthlyCost(
            MasterResource resource, ProjectResource assignment, String projectId, String activityId,
            LocalDate fromDate, LocalDate toDate, String periodLabel, Set<LocalDate> unpaidFull,
            Set<LocalDate> unpaidHalf, Set<LocalDate> sandwichDates, Set<LocalDate> relaxationDates,
            ResourceBasedPeriodService.ResourceBasedPeriod window) {
        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        LocalDate lastWorkingDate = assignment != null ? assignment.getAssignmentEndDate() : null;
        boolean active = assignment != null && assignment.isActive();
        AttendanceReportSummary attendance = buildSummary(
                resource, projectId, fromDate, toDate, periodLabel,
                assignment != null ? assignment.getRole() : null, joiningDate, active, lastWorkingDate,
                fromDate, toDate, activityId);
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
        // Gate billing to the project's resource-based period: days outside it are not chargeable.
        // A null window means phases are unavailable (mock off / call failed) → no gating.
        if (window != null) {
            if (window.from().isAfter(effectiveFrom)) effectiveFrom = window.from();
            if (window.to().isBefore(effectiveTo)) effectiveTo = window.to();
        }
        int activeCalendarDays = Math.max(0, (int) (effectiveTo.toEpochDay() - effectiveFrom.toEpochDay()) + 1);

        double unpaidLeaveDays =
                unpaidFull.stream().filter(d -> inRange(d, fromDate, toDate)).count()
                + unpaidHalf.stream().filter(d -> inRange(d, fromDate, toDate)).count() * 0.5;
        int sandwichLeave = (int) sandwichDates.stream().filter(d -> inRange(d, fromDate, toDate)).count();
        double relaxationDays = relaxationDates.stream()
                .filter(d -> inRange(d, fromDate, toDate))
                .mapToDouble(d -> unpaidHalf.contains(d) ? 0.5 : 1.0)
                .sum();
        // Rule #5: Total Unpaid Days = Unpaid + Sandwich − Approved Relaxation.
        double totalUnpaidDays = Math.max(0.0, unpaidLeaveDays + sandwichLeave - relaxationDays);
        double paidLeaveDaysMonthly = Math.max(0.0, (attendance.absentDays() + attendance.halfDays() * 0.5) - unpaidLeaveDays);

        double perDayRate = monthlyRate != null ? round2(monthlyRate / fullCalendarDays) : 0d;
        // When the resource-based period fully excludes this cycle there are no billable days, so
        // nothing is charged and nothing is deducted (cost = 0).
        boolean billable = monthlyRate != null && activeCalendarDays > 0;
        double deductedAmount = billable ? round2(totalUnpaidDays * monthlyRate / fullCalendarDays) : 0d;
        double relaxationCost = billable ? round2(relaxationDays * monthlyRate / fullCalendarDays) : 0d;
        // Cost bills only the days the resource was active; billable days net out unpaid within that.
        double cost = billable ? round2((double) activeCalendarDays * monthlyRate / fullCalendarDays - deductedAmount) : 0d;
        double billableDays = activeCalendarDays > 0 ? round2(Math.max(0.0, activeCalendarDays - totalUnpaidDays)) : 0d;

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
                fullCalendarDays,
                activeCalendarDays,
                unpaidLeaveDays,
                sandwichLeave,
                relaxationDays,
                totalUnpaidDays,
                billableDays,
                billableDays,
                rate,
                perDayRate,
                deductedAmount,
                relaxationCost,
                cost);
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        return !d.isBefore(from) && !d.isAfter(to);
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

    /** The latest date an uploaded employee has any attendance record (present/worked or absent). */
    private LocalDate lastAttendanceDate(EmployeeAttendanceByDate employee) {
        LocalDate last = null;
        for (LocalDate d : employee.absentDates()) {
            if (last == null || d.isAfter(last)) last = d;
        }
        for (LocalDate d : employee.workedMinutesByDate().keySet()) {
            if (last == null || d.isAfter(last)) last = d;
        }
        return last;
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
                designation, joiningDate, effectiveStart, effectiveEnd, quota, absentSet, halfDaySet);

        double paidLeaveDays = leaveCalc.paidLeaveDays();
        double unpaidLeaveDays = leaveCalc.unpaidLeaveDays();
        int sandwichDays = leaveCalc.sandwichDays();
        // Total leave taken = paid + unpaid + sandwich.
        double leaveTaken = paidLeaveDays + unpaidLeaveDays + sandwichDays;
        double attendancePercentage = workingDays > 0
                ? Math.round((presentDays + paidLeaveDays) * 10000.0 / workingDays) / 100.0
                : 0d;

        int calendarDays = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        return new AttendanceReportSummary(
                resource.getResId(), resource.getName(), designation, projectId,
                milestoneId, activityId, joiningDate, lastWorkingDate, active,
                periodLabel, start, end, calendarDays, workingDays, presentDays, halfDays,
                leaveDays, absentDays, weekOffDays, holidayDays, wfhDays, attendancePercentage,
                leaveTaken, paidLeaveDays, unpaidLeaveDays, sandwichDays);
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
        ResourceBasedPeriodService.ResourceBasedPeriod window = resourceBasedPeriodService.resolve(projectId).orElse(null);
        List<ResourceCostSummary> rows = latestAssignmentsActiveDuring(projectId, startDate, endDate).stream()
                .filter(pr -> organisationId == null || organisationId.isBlank()
                        || organisationId.equals(pr.getOrganisationId()))
                .map(pr -> buildPeriodCostSummary(pr.getResource(), projectId, startDate, endDate, periodLabel, window))
                .toList();
        return new ResourceCostResult(periodLabel, rows.size(), buildCostTotals(rows), rows);
    }

    private ResourceCostSummary buildPeriodCostSummary(
            MasterResource resource, String projectId, LocalDate periodStart, LocalDate periodEnd,
            String periodLabel, ResourceBasedPeriodService.ResourceBasedPeriod window) {
        ProjectResource assignment = resolveAssignmentForProject(resource.getResId(), projectId);
        String orgId = assignment != null ? assignment.getOrganisationId() : null;
        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        LocalDate lastWorkingDate = assignment != null ? assignment.getAssignmentEndDate() : null;
        boolean active = assignment != null && assignment.isActive();
        String rateYear = resolveRateYear(projectId, orgId, periodStart,
                assignment != null ? assignment.getRateYear() : null);

        int fullCalendarDays = (int) (periodEnd.toEpochDay() - periodStart.toEpochDay()) + 1;
        LocalDate effectiveFrom = (joiningDate != null && joiningDate.isAfter(periodStart)) ? joiningDate : periodStart;
        LocalDate effectiveTo = (lastWorkingDate != null && lastWorkingDate.isBefore(periodEnd)) ? lastWorkingDate : periodEnd;

        // Billing window: the active window clamped to the project's resource-based period. Attendance
        // counts stay on [effectiveFrom, effectiveTo]; only the chargeable days/cost use the billing
        // window. A null window means no phases available → no gating (billing == active window).
        LocalDate billFrom = effectiveFrom;
        LocalDate billTo = effectiveTo;
        if (window != null) {
            if (window.from().isAfter(billFrom)) billFrom = window.from();
            if (window.to().isBefore(billTo)) billTo = window.to();
        }
        int calendarDays = Math.max(0, (int) (billTo.toEpochDay() - billFrom.toEpochDay()) + 1);

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
                assignment != null ? assignment.getRole() : null,
                joiningDate, effectiveFrom, effectiveTo, quota, absentSet, halfDaySet);

        Set<LocalDate> unpaidFull = new HashSet<>(leaveCalc.unpaidLeaveDates());
        Set<LocalDate> unpaidHalf = new HashSet<>(leaveCalc.unpaidHalfDayDates());
        Set<LocalDate> sandwich = new HashSet<>(leaveCalc.sandwichDates());
        double unpaidLeaveDays = unpaidFull.size() + unpaidHalf.size() * 0.5;

        Set<LocalDate> relaxationDatesSet = new HashSet<>();
        if (projectId != null && activityId != null) {
            leaveRelaxationRepository
                    .findByResource_ResIdAndProjectIdAndActivityId(resource.getResId(), projectId, activityId)
                    .ifPresent(r -> relaxationDatesSet.addAll(r.getRelaxationDates()));
        }

        double totalPlannedCost = 0d;
        double grossDeduction = 0d;
        double relaxationCostVal = 0d;
        int sandwichLeave = 0;
        double relaxationDays = 0d;
        if (assignment != null) {
            Double activityRate = activityMonthlyRate(activityId, assignment.getRole());
            LocalDate segStart = billFrom;
            while (!segStart.isAfter(billTo)) {
                LocalDate monthEnd = segStart.withDayOfMonth(segStart.lengthOfMonth());
                LocalDate segEnd = monthEnd.isBefore(billTo) ? monthEnd : billTo;
                int daysInSegment = (int) (segEnd.toEpochDay() - segStart.toEpochDay()) + 1;
                String segRateYear = resolveRateYear(projectId, orgId, segStart, assignment.getRateYear());
                Double segRate = activityRate != null ? activityRate
                        : (segRateYear != null ? assignment.getRateCardByYear().get(segRateYear) : null);
                if (segRate != null) {
                    totalPlannedCost += segRate * daysInSegment / segStart.lengthOfMonth();
                }
                segStart = segEnd.plusDays(1);
            }
            for (LocalDate d : unpaidFull) {
                if (inRange(d, billFrom, billTo)) {
                    grossDeduction += dailyRate(projectId, orgId, assignment, activityRate, d);
                }
            }
            for (LocalDate d : unpaidHalf) {
                if (inRange(d, billFrom, billTo)) {
                    grossDeduction += 0.5 * dailyRate(projectId, orgId, assignment, activityRate, d);
                }
            }
            for (LocalDate d : sandwich) {
                if (inRange(d, billFrom, billTo)) {
                    grossDeduction += dailyRate(projectId, orgId, assignment, activityRate, d);
                    sandwichLeave++;
                }
            }
            for (LocalDate d : relaxationDatesSet) {
                if (inRange(d, billFrom, billTo)) {
                    double weight = unpaidHalf.contains(d) ? 0.5 : 1.0;
                    relaxationCostVal += weight * dailyRate(projectId, orgId, assignment, activityRate, d);
                    relaxationDays += weight;
                }
            }
        }

        // Rule #5: Total Unpaid Days = Unpaid + Sandwich − Approved Relaxation.
        double totalUnpaidDays = Math.max(0.0, unpaidLeaveDays + sandwichLeave - relaxationDays);
        double totalDeductedAmount = Math.max(0.0, grossDeduction - relaxationCostVal);
        double perDayCost = calendarDays > 0 ? round2(totalPlannedCost / calendarDays) : 0d;
        double deductedAmount = round2(totalDeductedAmount);
        double periodCost = round2(totalPlannedCost - totalDeductedAmount);
        double billableDays = round2(calendarDays - totalUnpaidDays);
        // Relaxation is already inside the reduced deduction (Rule #5) — no add-back.
        double totalCost = periodCost;

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
                fullCalendarDays, calendarDays, unpaidLeaveDays, sandwichLeave, relaxationDays, totalUnpaidDays,
                billableDays, billableDays,
                monthlyRate != null ? monthlyRate : 0d,
                perDayCost, deductedAmount, round2(relaxationCostVal), periodCost);

        return new ResourceCostSummary(
                resource.getResId(), resource.getName(),
                assignment != null ? assignment.getRole() : null,
                assignment != null ? assignment.getAssignmentStartDate() : null,
                assignment != null ? assignment.getAssignmentEndDate() : null,
                projectId, periodLabel,
                fullCalendarDays, calendarDays, round2(totalPlannedCost), perDayCost,
                totalUnpaidDays, billableDays, billableDays,
                deductedAmount, periodCost,
                relaxationDays, round2(relaxationCostVal), totalCost,
                List.of(periodEntry));
    }

    /** Per-day deduction rate for a single date, honoring the activity rate or the date's rate-year. */
    private double dailyRate(String projectId, String orgId, ProjectResource assignment,
            Double activityRate, LocalDate d) {
        Double rate = activityRate;
        if (rate == null) {
            String ry = resolveRateYear(projectId, orgId, d, assignment.getRateYear());
            rate = ry != null ? assignment.getRateCardByYear().get(ry) : null;
        }
        return rate != null ? rate / d.lengthOfMonth() : 0d;
    }

}
