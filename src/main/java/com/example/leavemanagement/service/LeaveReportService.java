package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.LeaveReportEntry;
import com.example.leavemanagement.dto.LeaveReportSummary;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.QuarterlyRelaxationRequest;
import com.example.leavemanagement.dto.RelaxationEligibilityResponse;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.LeaveRelaxation;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.LeaveRelaxationRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.repository.ProjectYearMappingRepository;
import com.example.leavemanagement.security.CurrentUser;
import com.example.leavemanagement.security.CurrentUserContext;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LeaveReportService {

    private final AttendanceQueryService attendanceQueryService;
    private final MasterResourceRepository masterResourceRepository;
    private final ProjectResourceRepository projectResourceRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRelaxationRepository leaveRelaxationRepository;
    private final QuarterLeaveResolver quarterLeaveResolver;
    private final AttendancePeriodValidator periodValidator;
    private final ProjectYearMappingRepository yearMappingRepository;

    public LeaveReportService(
            AttendanceQueryService attendanceQueryService,
            MasterResourceRepository masterResourceRepository,
            ProjectResourceRepository projectResourceRepository,
            AttendanceRepository attendanceRepository,
            LeaveRelaxationRepository leaveRelaxationRepository,
            QuarterLeaveResolver quarterLeaveResolver,
            AttendancePeriodValidator periodValidator,
            ProjectYearMappingRepository yearMappingRepository) {
        this.attendanceQueryService = attendanceQueryService;
        this.masterResourceRepository = masterResourceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.attendanceRepository = attendanceRepository;
        this.leaveRelaxationRepository = leaveRelaxationRepository;
        this.quarterLeaveResolver = quarterLeaveResolver;
        this.periodValidator = periodValidator;
        this.yearMappingRepository = yearMappingRepository;
    }

    /** The resource's active project assignment, resolved by attendanceId (res_id). */
    private Optional<ProjectResource> activeAssignment(String attendanceId) {
        return masterResourceRepository
                .findByResId(attendanceId)
                .flatMap(resource -> projectResourceRepository.findByResourceIdAndActiveTrue(resource.getId()));
    }

    @Transactional(readOnly = true)
    public LeaveReportSummary quarterlySummary(int year, int quarter, String projectId) {
        QuarterLeaveReport report = attendanceQueryService.quarterlySettlement(year, quarter, projectId);

        List<LeaveReportEntry> entries = report.resources().stream()
                .map(settlement -> {
                    String resourceProjectId = activeAssignment(settlement.attendanceId())
                            .map(ProjectResource::getProjectId)
                            .orElse(null);
                    QuarterLeaveCalculation calc = settlement.calculation();
                    return new LeaveReportEntry(
                            settlement.attendanceId(),
                            settlement.employeeName(),
                            resourceProjectId,
                            calc.permissibleLeave(),
                            calc.leaveDaysTaken(),
                            calc.paidLeaveDays(),
                            calc.unpaidLeaveDays(),
                            calc.sandwichDays(),
                            calc.totalUnpaidDays(),
                            calc.lapsedLeaveDays());
                })
                .toList();

        return new LeaveReportSummary(
                report.year(),
                report.quarter(),
                report.quarterStart(),
                report.quarterEnd(),
                entries.size(),
                entries);
    }

    /** Raw quarterly settlement, plus any recorded relaxation applied on top. */
    @Transactional(readOnly = true)
    public EmployeeLeaveDetail employeeDetail(String attendanceId, int year, int quarter, String filterProjectId) {
        return applyRelaxation(rawEmployeeDetail(attendanceId, year, quarter, filterProjectId));
    }

    /**
     * Approves specific unpaid leave dates as relaxation leave for one resource's quarter.
     *
     * <p>Each call adds the selected dates to the cumulative approved set. Dates must be actual
     * unpaid leave dates for this resource's quarter and must not have been previously approved.
     * Per-day cost is calculated per month: {@code monthlyRate / calendarDaysInMonth}, so dates
     * from different months are priced independently.
     */
    @Transactional
    public EmployeeLeaveDetail applyQuarterlyRelaxation(
            QuarterlyRelaxationRequest request, MultipartFile attachment) {
        List<LocalDate> requestedDates = request.relaxationDates();
        if (requestedDates == null || requestedDates.isEmpty()) {
            throw new BadRequestException("At least one relaxation date must be selected");
        }

        EmployeeLeaveDetail raw =
                rawEmployeeDetail(request.resourceId(), request.year(), request.quarter(), request.projectId());

        // Validate: every requested date must be an eligible unpaid date (full, half-day, or sandwich).
        Set<LocalDate> eligibleSet = new HashSet<>(raw.unpaidLeaveDates());
        eligibleSet.addAll(raw.unpaidHalfDayDates());
        eligibleSet.addAll(raw.sandwichDates());
        for (LocalDate date : requestedDates) {
            if (!eligibleSet.contains(date)) {
                throw new BadRequestException(
                        "Date " + date + " is not an unpaid leave date for this resource in Q"
                                + request.quarter() + " " + request.year());
            }
        }

        MasterResource resource = masterResourceRepository
                .findByResId(request.resourceId())
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + request.resourceId()));
        LeaveRelaxation relaxation = leaveRelaxationRepository
                .findByResource_ResIdAndProjectIdAndYearAndQuarter(
                        request.resourceId(), request.projectId(), request.year(), request.quarter())
                .orElseGet(() -> new LeaveRelaxation(resource, request.projectId(), request.year(), request.quarter()));

        // Reject dates already approved in a previous call.
        Set<LocalDate> alreadyApproved = new HashSet<>(relaxation.getRelaxationDates());
        List<LocalDate> freshDates = requestedDates.stream()
                .filter(d -> !alreadyApproved.contains(d))
                .toList();
        if (freshDates.isEmpty()) {
            throw new BadRequestException("All selected dates have already been approved for relaxation");
        }

        Set<LocalDate> halfDayUnpaidSet = new HashSet<>(raw.unpaidHalfDayDates());

        // Per-day cost: monthlyRate / calendar-days-in-that-month, rate year resolved per date
        // from project-year mapping (falls back to the static rateYear on the assignment).
        // Half-day unpaid dates count as 0.5.
        ProjectResource assignment = projectResourceRepository
                .findByResource_ResIdAndProjectIdAndActiveTrue(request.resourceId(), request.projectId())
                .orElse(null);
        double newCost = 0;
        if (assignment != null) {
            String organisationId = assignment.getOrganisationId();
            for (LocalDate date : freshDates) {
                String rateYear = yearMappingRepository
                        .findEffectiveOn(request.projectId(), organisationId, date)
                        .map(m -> m.getRateYear())
                        .orElse(assignment.getRateYear());
                Double monthlyRate = rateYear != null ? assignment.getRateCardByYear().get(rateYear) : null;
                if (monthlyRate != null) {
                    double weight = halfDayUnpaidSet.contains(date) ? 0.5 : 1.0;
                    newCost += (monthlyRate / date.lengthOfMonth()) * weight;
                }
            }
        }

        // Merge new dates with previously approved ones.
        List<LocalDate> allDates = new ArrayList<>(alreadyApproved);
        allDates.addAll(freshDates);
        allDates.sort(Comparator.naturalOrder());

        double totalRelaxDays = allDates.stream()
                .mapToDouble(d -> halfDayUnpaidSet.contains(d) ? 0.5 : 1.0)
                .sum();
        double totalRelaxCost = round2(relaxation.getRelaxationCost() + newCost);

        relaxation.setOriginalPaidLeave(raw.paidLeave());
        relaxation.setOriginalUnpaidLeave(raw.unpaidLeave());
        relaxation.setRelaxationDates(allDates);
        relaxation.setRelaxationDays(totalRelaxDays);
        relaxation.setRelaxationCost(totalRelaxCost);
        relaxation.setFinalPaidLeave(raw.paidLeave());
        relaxation.setFinalUnpaidLeave(Math.max(0.0, raw.unpaidLeave() - totalRelaxDays));
        relaxation.setRemarks(request.remarks());
        if (attachment != null && !attachment.isEmpty()) {
            try {
                relaxation.setAttachmentName(attachment.getOriginalFilename());
                relaxation.setAttachmentContentType(attachment.getContentType());
                relaxation.setAttachmentData(attachment.getBytes());
            } catch (IOException e) {
                throw new BadRequestException("Failed to read attachment: " + e.getMessage());
            }
        }
        relaxation.setApprovedBy(currentUserIdentifier());
        relaxation.setApprovedAt(LocalDateTime.now());
        relaxation = leaveRelaxationRepository.save(relaxation);

        return applyRelaxation(raw, relaxation);
    }

    /**
     * Returns which unpaid leave dates are still eligible for relaxation approval (not yet
     * approved), along with the already-approved dates and cost for this resource's quarter.
     */
    @Transactional(readOnly = true)
    public RelaxationEligibilityResponse eligibleRelaxationDates(
            String resourceId, String projectId, int year, int quarter) {
        EmployeeLeaveDetail raw = rawEmployeeDetail(resourceId, year, quarter, projectId);

        List<LocalDate> allUnpaidDates = new ArrayList<>(raw.unpaidLeaveDates());
        allUnpaidDates.addAll(raw.unpaidHalfDayDates());
        allUnpaidDates.addAll(raw.sandwichDates());
        allUnpaidDates.sort(java.util.Comparator.naturalOrder());

        Optional<LeaveRelaxation> existing = leaveRelaxationRepository
                .findByResource_ResIdAndProjectIdAndYearAndQuarter(resourceId, projectId, year, quarter);
        List<LocalDate> approvedDates = existing.map(LeaveRelaxation::getRelaxationDates).orElse(List.of());
        double approvedCost = existing.map(LeaveRelaxation::getRelaxationCost).orElse(0.0);

        Set<LocalDate> approvedSet = new HashSet<>(approvedDates);
        List<LocalDate> eligible = allUnpaidDates.stream().filter(d -> !approvedSet.contains(d)).toList();

        return new RelaxationEligibilityResponse(
                resourceId, projectId, year, quarter,
                raw.unpaidLeaveDates(),
                raw.unpaidHalfDayDates(),
                raw.sandwichDates(),
                allUnpaidDates,
                approvedDates, eligible, approvedCost);
    }

    /** Returns the stored evidence attachment for a relaxation record, or throws if none exists. */
    @Transactional(readOnly = true)
    public LeaveRelaxation getRelaxationAttachment(
            String resourceId, String projectId, int year, int quarter) {
        LeaveRelaxation relaxation = leaveRelaxationRepository
                .findByResource_ResIdAndProjectIdAndYearAndQuarter(resourceId, projectId, year, quarter)
                .orElseThrow(() -> new NotFoundException(
                        "No relaxation record for " + resourceId + " Q" + quarter + " " + year));
        if (relaxation.getAttachmentData() == null) {
            throw new NotFoundException("No attachment on this relaxation record");
        }
        return relaxation;
    }

    /** Looks up a recorded relaxation for this resource/project/quarter and applies it, if any. */
    private EmployeeLeaveDetail applyRelaxation(EmployeeLeaveDetail raw) {
        if (raw.projectId() == null) {
            return raw; // no active assignment -> nothing to look a relaxation up against
        }
        return leaveRelaxationRepository
                .findByResource_ResIdAndProjectIdAndYearAndQuarter(
                        raw.attendanceId(), raw.projectId(), raw.year(), raw.quarter())
                .map(relaxation -> applyRelaxation(raw, relaxation))
                .orElse(raw);
    }

    private EmployeeLeaveDetail applyRelaxation(EmployeeLeaveDetail raw, LeaveRelaxation relaxation) {
        // Cap relaxationDays to the base unpaid leave in case attendance was re-uploaded after the
        // relaxation record was first saved (prevents negative unpaidLeave in the response).
        double relaxDays = Math.min(relaxation.getRelaxationDays(), raw.unpaidLeave());
        return new EmployeeLeaveDetail(
                raw.attendanceId(),
                raw.employeeName(),
                raw.projectId(),
                raw.projectName(),
                raw.milestoneId(),
                raw.activityId(),
                raw.designation(),
                raw.joiningDate(),
                raw.year(),
                raw.quarter(),
                raw.quarterStart(),
                raw.quarterEnd(),
                raw.permissibleLeave(),
                raw.carriedForwardLeave(),
                raw.leaveTaken(),
                raw.paidLeave(),
                raw.unpaidLeave() - relaxDays,
                relaxDays,
                raw.sandwichDays(),
                Math.max(0.0, raw.totalUnpaidDays() - relaxDays),
                raw.lapsedLeave(),
                raw.halfDayDates(),
                raw.paidLeaveDates(),
                raw.unpaidLeaveDates(),
                raw.unpaidHalfDayDates(),
                raw.sandwichDates());
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String currentUserIdentifier() {
        CurrentUser user = CurrentUserContext.get();
        if (user == null) {
            return null;
        }
        return user.email() != null && !user.email().isBlank() ? user.email() : user.username();
    }

    private EmployeeLeaveDetail rawEmployeeDetail(String attendanceId, int year, int quarter, String filterProjectId) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }

        Optional<MasterResource> resource = masterResourceRepository.findByResId(attendanceId);

        ProjectResource assignment = resource
                .flatMap(r -> {
                    Optional<ProjectResource> active =
                            projectResourceRepository.findByResourceIdAndActiveTrue(r.getId());
                    if (active.isPresent()) return active;
                    List<ProjectResource> history =
                            projectResourceRepository.findByResourceIdOrderByAssignmentStartDateAsc(r.getId());
                    return history.isEmpty()
                            ? Optional.empty()
                            : Optional.of(history.get(history.size() - 1));
                })
                .orElse(null);

        String projectId = assignment != null ? assignment.getProjectId() : null;
        if (filterProjectId != null && !filterProjectId.isBlank() && !filterProjectId.equals(projectId)) {
            throw new NotFoundException(
                    "No leave record for attendanceId " + attendanceId + " under project " + filterProjectId);
        }

        String organisationId = assignment != null ? assignment.getOrganisationId() : null;
        int cycleDay = quarterLeaveResolver.resolveCycleDay(projectId, organisationId);
        LocalDate quarterStart = quarterLeaveResolver.quarterStart(year, quarter, cycleDay);
        LocalDate quarterEnd = quarterLeaveResolver.quarterEnd(year, quarter, cycleDay);

        periodValidator.validate(quarterStart, quarterEnd, filterProjectId, attendanceId,
                "Q" + quarter + " " + year);

        List<Attendance> attendanceRows = resource
                .map(r -> attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                        r.getId(), quarterStart, quarterEnd))
                .orElse(List.of());

        Set<LocalDate> absentDates = attendanceRows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
                .map(Attendance::getAttendanceDate)
                .collect(Collectors.toSet());

        Set<LocalDate> halfDayDates = attendanceRows.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.HD)
                .map(Attendance::getAttendanceDate)
                .collect(Collectors.toSet());

        String milestoneId = null;
        String activityId  = null;
        for (Attendance row : attendanceRows) {
            if (row.getMilestoneId() != null) milestoneId = row.getMilestoneId();
            if (row.getActivityId()  != null) activityId  = row.getActivityId();
        }

        String projectName = null;

        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        LocalDate lastWorkingDate = assignment != null ? assignment.getAssignmentEndDate() : null;
        String employeeName = resource.map(MasterResource::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(attendanceId);

        Long resourceId = resource.map(MasterResource::getId).orElse(null);
        QuarterLeaveCalculation calc =
                quarterLeaveResolver.calculate(attendanceId, resourceId, projectId, organisationId,
                        joiningDate, lastWorkingDate, year, quarter,
                        absentDates, halfDayDates);

        String designation = assignment != null ? assignment.getRole() : null;
        List<LocalDate> sortedHalfDayDates = halfDayDates.stream().sorted().toList();
        return new EmployeeLeaveDetail(
                attendanceId,
                employeeName,
                projectId,
                projectName,
                milestoneId,
                activityId,
                designation,
                joiningDate,
                year,
                quarter,
                quarterStart,
                quarterEnd,
                calc.permissibleLeave(),
                calc.carriedForwardLeave(),
                calc.leaveDaysTaken(),
                calc.paidLeaveDays(),
                calc.unpaidLeaveDays(),
                0.0,
                calc.sandwichDays(),
                calc.totalUnpaidDays(),
                calc.lapsedLeaveDays(),
                sortedHalfDayDates,
                calc.paidLeaveDates(),
                calc.unpaidLeaveDates(),
                calc.unpaidHalfDayDates(),
                calc.sandwichDates());
    }
}
