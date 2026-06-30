package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.LeaveReportEntry;
import com.example.leavemanagement.dto.LeaveReportSummary;
import com.example.leavemanagement.dto.QuarterLeaveCalculation;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.dto.ResourceQuarterSettlement;
import com.example.leavemanagement.entity.ProjectConfig;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.entity.ResourceMonthlyAttendance;
import com.example.leavemanagement.entity.ResourceProjectMapping;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import com.example.leavemanagement.repository.ResourceMonthlyAttendanceRepository;
import com.example.leavemanagement.repository.ResourceProjectMappingRepository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveReportService {

    private final AttendanceQueryService attendanceQueryService;
    private final ResourceProjectMappingRepository resourceProjectMappingRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final ResourceMonthlyAttendanceRepository resourceMonthlyAttendanceRepository;
    private final PublicHolidayRepository publicHolidayRepository;
    private final QuarterLeavePolicy policy;

    public LeaveReportService(
            AttendanceQueryService attendanceQueryService,
            ResourceProjectMappingRepository resourceProjectMappingRepository,
            ProjectConfigRepository projectConfigRepository,
            ResourceMonthlyAttendanceRepository resourceMonthlyAttendanceRepository,
            PublicHolidayRepository publicHolidayRepository,
            QuarterLeavePolicy policy) {
        this.attendanceQueryService = attendanceQueryService;
        this.resourceProjectMappingRepository = resourceProjectMappingRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.resourceMonthlyAttendanceRepository = resourceMonthlyAttendanceRepository;
        this.publicHolidayRepository = publicHolidayRepository;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public LeaveReportSummary quarterlySummary(int year, int quarter) {
        QuarterLeaveReport report = attendanceQueryService.quarterlySettlement(year, quarter);

        List<LeaveReportEntry> entries = report.resources().stream()
                .map(settlement -> {
                    String projectId = resourceProjectMappingRepository
                            .findById(settlement.attendanceId())
                            .map(ResourceProjectMapping::getProjectId)
                            .orElse(null);
                    QuarterLeaveCalculation calc = settlement.calculation();
                    return new LeaveReportEntry(
                            settlement.attendanceId(),
                            settlement.employeeName(),
                            projectId,
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
    public EmployeeLeaveDetail employeeDetail(String attendanceId, int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("quarter must be between 1 and 4");
        }

        List<Integer> months = List.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
        LocalDate quarterStart = LocalDate.of(year, months.get(0), 1);
        LocalDate quarterEnd = LocalDate.of(year, months.get(2), 1)
                .withDayOfMonth(LocalDate.of(year, months.get(2), 1).lengthOfMonth());

        List<ResourceMonthlyAttendance> rows =
                resourceMonthlyAttendanceRepository.findByYearAndMonthIn(year, months).stream()
                        .filter(r -> attendanceId.equals(r.getAttendanceId()))
                        .toList();

        Set<LocalDate> absentDates = new HashSet<>();
        String sheetName = attendanceId;
        for (ResourceMonthlyAttendance row : rows) {
            if (row.getEmployeeName() != null && !row.getEmployeeName().isBlank()) {
                sheetName = row.getEmployeeName();
            }
            int lengthOfMonth = LocalDate.of(year, row.getMonth(), 1).lengthOfMonth();
            for (Integer day : row.getAbsentDays()) {
                if (day >= 1 && day <= lengthOfMonth) {
                    absentDates.add(LocalDate.of(year, row.getMonth(), day));
                }
            }
        }

        Set<LocalDate> holidays =
                publicHolidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(quarterStart, quarterEnd)
                        .stream()
                        .map(PublicHoliday::getHolidayDate)
                        .collect(Collectors.toSet());

        ResourceProjectMapping mapping =
                resourceProjectMappingRepository.findById(attendanceId).orElse(null);

        String projectId = mapping != null ? mapping.getProjectId() : null;
        ProjectConfig config = Optional.ofNullable(projectId)
                .flatMap(projectConfigRepository::findById)
                .orElse(null);
        String projectName = config != null ? config.getProjectName() : null;

        LocalDate joiningDate = mapping != null ? mapping.getJoiningDate() : null;
        String employeeName = (mapping != null && mapping.getEmployeeName() != null
                && !mapping.getEmployeeName().isBlank())
                ? mapping.getEmployeeName()
                : sheetName;

        int maxLeaves = config != null
                ? config.getMaxLeavesPerPeriod()
                : QuarterLeavePolicy.MAX_PERMISSIBLE_LEAVE;

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
