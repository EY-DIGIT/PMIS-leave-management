package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.LeaveReportEntry;
import com.example.leavemanagement.dto.LeaveReportSummary;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.QuarterlyRelaxationRequest;
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
import com.example.leavemanagement.security.CurrentUser;
import com.example.leavemanagement.security.CurrentUserContext;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    public LeaveReportService(
            AttendanceQueryService attendanceQueryService,
            MasterResourceRepository masterResourceRepository,
            ProjectResourceRepository projectResourceRepository,
            AttendanceRepository attendanceRepository,
            LeaveRelaxationRepository leaveRelaxationRepository,
            QuarterLeaveResolver quarterLeaveResolver) {
        this.attendanceQueryService = attendanceQueryService;
        this.masterResourceRepository = masterResourceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.attendanceRepository = attendanceRepository;
        this.leaveRelaxationRepository = leaveRelaxationRepository;
        this.quarterLeaveResolver = quarterLeaveResolver;
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
     * Incrementally adds relaxation days for one resource's quarter.
     *
     * <p>Each call adds {@code request.relaxationDays()} to the running cumulative total, moving
     * that many days from unpaid leave into relaxation leave. The increment is silently clamped to
     * the remaining unpaid leave so it is never possible to approve more relaxation than exists.
     *
     * <p>Formula on every call:
     * <pre>
     *   applied            = MIN(requested, raw.unpaidLeave - prevCumulativeRelaxation)
     *   newRelaxationTotal = prevCumulativeRelaxation + applied
     *   finalUnpaidLeave   = raw.unpaidLeave - newRelaxationTotal
     * </pre>
     */
    @Transactional
    public EmployeeLeaveDetail applyQuarterlyRelaxation(
            QuarterlyRelaxationRequest request, MultipartFile attachment) {
        if (request.relaxationDays() < 0) {
            throw new BadRequestException("relaxationDays must be >= 0");
        }

        EmployeeLeaveDetail raw =
                rawEmployeeDetail(request.resourceId(), request.year(), request.quarter(), request.projectId());

        MasterResource resource = masterResourceRepository
                .findByResId(request.resourceId())
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + request.resourceId()));
        LeaveRelaxation relaxation = leaveRelaxationRepository
                .findByResource_ResIdAndProjectIdAndYearAndQuarter(
                        request.resourceId(), request.projectId(), request.year(), request.quarter())
                .orElseGet(() -> new LeaveRelaxation(resource, request.projectId(), request.year(), request.quarter()));

        // Cumulative relaxation already approved in prior calls (0 for a brand-new record).
        double prevRelaxationDays = relaxation.getRelaxationDays();

        // Remaining unpaid leave not yet converted to relaxation (may be fractional due to half-days).
        double remainingUnpaid = Math.max(0.0, raw.unpaidLeave() - prevRelaxationDays);

        // Clamp the increment — never approve more than what's still available.
        double approved = Math.min(request.relaxationDays(), remainingUnpaid);

        double newTotalRelaxation = prevRelaxationDays + approved;
        double newFinalUnpaid     = raw.unpaidLeave() - newTotalRelaxation;

        relaxation.setOriginalPaidLeave(raw.paidLeave());
        relaxation.setOriginalUnpaidLeave(raw.unpaidLeave());
        relaxation.setRelaxationDays(newTotalRelaxation);
        relaxation.setFinalPaidLeave(raw.paidLeave());
        relaxation.setFinalUnpaidLeave(newFinalUnpaid);
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
        // Always use the entity returned by save() — JPA merge can return a different managed
        // instance than the local variable, so discarding the return value risks using a stale
        // (pre-update) relaxationDays when building the response, which produces reversed values.
        relaxation = leaveRelaxationRepository.save(relaxation);

        return applyRelaxation(raw, relaxation);
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
                raw.sandwichDates());
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

        List<Integer> months = List.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
        LocalDate quarterStart = LocalDate.of(year, months.get(0), 1);
        LocalDate quarterEnd = LocalDate.of(year, months.get(2), 1)
                .withDayOfMonth(LocalDate.of(year, months.get(2), 1).lengthOfMonth());

        Optional<MasterResource> resource = masterResourceRepository.findByResId(attendanceId);
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

        ProjectResource assignment = resource
                .flatMap(r -> projectResourceRepository.findByResourceIdAndActiveTrue(r.getId()))
                .orElse(null);

        String projectId = assignment != null ? assignment.getProjectId() : null;
        if (filterProjectId != null && !filterProjectId.isBlank() && !filterProjectId.equals(projectId)) {
            throw new NotFoundException(
                    "No leave record for attendanceId " + attendanceId + " under project " + filterProjectId);
        }
        String projectName = null;

        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        String employeeName = resource.map(MasterResource::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(attendanceId);

        Long resourceId = resource.map(MasterResource::getId).orElse(null);
        QuarterLeaveCalculation calc =
                quarterLeaveResolver.calculate(resourceId, projectId, joiningDate, year, quarter,
                        absentDates, halfDayDates);

        List<LocalDate> sortedHalfDayDates = halfDayDates.stream().sorted().toList();
        return new EmployeeLeaveDetail(
                attendanceId,
                employeeName,
                projectId,
                projectName,
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
                0.0, // relaxationLeave: none applied yet — see applyRelaxation
                calc.sandwichDays(),
                calc.totalUnpaidDays(),
                calc.lapsedLeaveDays(),
                sortedHalfDayDates,
                calc.paidLeaveDates(),
                calc.unpaidLeaveDates(),
                calc.sandwichDates());
    }
}
