package com.example.leavemanagement.service;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.LeaveReportEntry;
import com.example.leavemanagement.dto.LeaveReportSummary;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.ResourceQuarterSettlement;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectConfig;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.LocalDate;
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
    private final PublicHolidayRepository publicHolidayRepository;
    private final QuarterLeavePolicy policy;
    private final LeavePolicyClient leavePolicyClient;

    public LeaveReportService(
            AttendanceQueryService attendanceQueryService,
            MasterResourceRepository masterResourceRepository,
            ProjectResourceRepository projectResourceRepository,
            ProjectConfigRepository projectConfigRepository,
            AttendanceRepository attendanceRepository,
            PublicHolidayRepository publicHolidayRepository,
            QuarterLeavePolicy policy,
            LeavePolicyClient leavePolicyClient) {
        this.attendanceQueryService = attendanceQueryService;
        this.masterResourceRepository = masterResourceRepository;
        this.projectResourceRepository = projectResourceRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.attendanceRepository = attendanceRepository;
        this.publicHolidayRepository = publicHolidayRepository;
        this.policy = policy;
        this.leavePolicyClient = leavePolicyClient;
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

    @Transactional(readOnly = true)
    public EmployeeLeaveDetail employeeDetail(String attendanceId, int year, int quarter, String filterProjectId) {
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

        Set<LocalDate> holidays =
                publicHolidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(quarterStart, quarterEnd)
                        .stream()
                        .map(PublicHoliday::getHolidayDate)
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

        Integer maxLeavesFromPolicy = Optional.ofNullable(projectId)
                .flatMap(leavePolicyClient::getLeavePolicy)
                .map(LeavePolicyResponse::leavesPerFrequencyCount)
                .orElse(null);
        int maxLeaves = maxLeavesFromPolicy != null
                ? maxLeavesFromPolicy
                : config != null ? config.getMaxLeavesPerPeriod() : QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE;

        QuarterLeaveCalculation calc =
                policy.compute(quarterStart, quarterEnd, joiningDate, absentDates, holidays, maxLeaves);

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
                calc.leaveDaysTaken(),
                calc.paidLeaveDays(),
                calc.unpaidLeaveDays(),
                calc.sandwichDays(),
                calc.totalUnpaidDays(),
                calc.lapsedLeaveDays(),
                calc.paidLeaveDates(),
                calc.unpaidLeaveDates(),
                calc.sandwichDates());
    }
}
