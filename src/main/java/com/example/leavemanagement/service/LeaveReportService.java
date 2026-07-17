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
import com.example.leavemanagement.entity.ProjectConfig;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.LeaveRelaxationRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.security.CurrentUser;
import com.example.leavemanagement.security.CurrentUserContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveReportService {

    private final AttendanceQueryService attendanceQueryService;
    private final MasterResourceRepository masterResourceRepository;
    private final ProjectResourceRepository projectResourceRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRelaxationRepository leaveRelaxationRepository;
    private final QuarterLeaveResolver quarterLeaveResolver;

    public LeaveReportService(
            AttendanceQueryService attendanceQueryService,
            MasterResourceRepository masterResourceRepository,
            ProjectResourceRepository projectResourceRepository,
            ProjectConfigRepository projectConfigRepository,
            AttendanceRepository attendanceRepository,
            LeaveRelaxationRepository leaveRelaxationRepository,
            QuarterLeaveResolver quarterLeaveResolver) {
        this.attendanceQueryService = attendanceQueryService;
        this.masterResourceRepository = masterResourceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.projectConfigRepository = projectConfigRepository;
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
     * Records UIDAI's final relaxation decision for one resource's quarter (see {@link
     * LeaveRelaxation}) and returns the recalculated settlement. Re-recording a decision for the
     * same quarter overwrites the previous one, rather than stacking.
     */
    @Transactional
    public EmployeeLeaveDetail applyQuarterlyRelaxation(QuarterlyRelaxationRequest request) {
        EmployeeLeaveDetail raw =
                rawEmployeeDetail(request.resourceId(), request.year(), request.quarter(), request.projectId());
        if (request.relaxationDays() < 0 || request.relaxationDays() > raw.unpaidLeave()) {
            throw new BadRequestException("relaxationDays must be between 0 and " + raw.unpaidLeave()
                    + " (this quarter's unpaid leave days).");
        }

        MasterResource resource = masterResourceRepository
                .findByResId(request.resourceId())
                .orElseThrow(() -> new NotFoundException("No resource with res_id " + request.resourceId()));
        LeaveRelaxation relaxation = leaveRelaxationRepository
                .findByResource_ResIdAndProjectIdAndYearAndQuarter(
                        request.resourceId(), request.projectId(), request.year(), request.quarter())
                .orElseGet(() -> new LeaveRelaxation(resource, request.projectId(), request.year(), request.quarter()));
        relaxation.setOriginalPaidLeave(raw.paidLeave());
        relaxation.setOriginalUnpaidLeave(raw.unpaidLeave());
        relaxation.setRelaxationDays(request.relaxationDays());
        relaxation.setFinalPaidLeave(raw.paidLeave());
        relaxation.setFinalUnpaidLeave(raw.unpaidLeave() - request.relaxationDays());
        relaxation.setRemarks(request.remarks());
        relaxation.setApprovedBy(currentUserIdentifier());
        relaxation.setApprovedAt(LocalDateTime.now());
        leaveRelaxationRepository.save(relaxation);

        return applyRelaxation(raw, relaxation);
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
                Math.max(0, raw.unpaidLeave() - relaxation.getRelaxationDays()),
                relaxation.getRelaxationDays(),
                raw.sandwichDays(),
                Math.max(0, raw.totalUnpaidDays() - relaxation.getRelaxationDays()),
                raw.lapsedLeave(),
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
        Set<LocalDate> absentDates = resource
                .map(r -> attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                        r.getId(), quarterStart, quarterEnd))
                .orElse(List.of())
                .stream()
                .filter(a -> a.getStatus() == AttendanceStatus.A)
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
        ProjectConfig config = Optional.ofNullable(projectId)
                .flatMap(projectConfigRepository::findById)
                .orElse(null);
        String projectName = config != null ? config.getProjectName() : null;

        LocalDate joiningDate = assignment != null ? assignment.getAssignmentStartDate() : null;
        String employeeName = resource.map(MasterResource::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(attendanceId);

        Long resourceId = resource.map(MasterResource::getId).orElse(null);
        QuarterLeaveCalculation calc =
                quarterLeaveResolver.calculate(resourceId, projectId, joiningDate, year, quarter, absentDates);

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
                0, // relaxationLeave: none applied yet — see applyRelaxation
                calc.sandwichDays(),
                calc.totalUnpaidDays(),
                calc.lapsedLeaveDays(),
                calc.paidLeaveDates(),
                calc.unpaidLeaveDates(),
                calc.sandwichDates());
    }
}
