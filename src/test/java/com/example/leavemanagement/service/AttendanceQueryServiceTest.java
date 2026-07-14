package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.AttendanceReportSummary;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.AttendanceValidationException;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class AttendanceQueryServiceTest {

    @Mock
    private AttendanceExcelParser parser;

    @Mock
    private AttendanceRepository attendanceRepository;

    @Mock
    private PublicHolidayRepository holidayRepository;

    @Mock
    private MasterResourceRepository masterResourceRepository;

    @Mock
    private ProjectResourceRepository projectResourceRepository;

    @Mock
    private ProjectConfigRepository projectConfigRepository;

    @Mock
    private LeavePolicyClient leavePolicyClient;

    // Real engine — the quarterly-settlement path is verified end-to-end.
    private final QuarterLeavePolicy policy = new QuarterLeavePolicy();

    private AttendanceQueryService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceQueryService(
                parser, attendanceRepository, holidayRepository, policy, masterResourceRepository,
                projectResourceRepository, projectConfigRepository, leavePolicyClient);
    }

    private MultipartFile anyFile() {
        return new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
    }

    private void setId(MasterResource resource, long id) {
        try {
            var field = MasterResource.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(resource, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void stubActiveResource(String attendanceId, String projectId, long resourceId) {
        MasterResource resource = new MasterResource(attendanceId);
        resource.setName("Asha");
        setId(resource, resourceId);
        when(masterResourceRepository.findByResId(attendanceId)).thenReturn(Optional.of(resource));
        ProjectResource assignment =
                new ProjectResource(resource, projectId, "Dev", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(resourceId))
                .thenReturn(Optional.of(assignment));
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    @Test
    void uploadWritesPresentHalfDayAndAbsentRowsUsingDefaultThresholds() {
        // Wed 01-Jul to Fri 03-Jul 2026 — no weekend in range.
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate(
                        "E1", "Asha", "Dev",
                        Set.of(LocalDate.of(2026, 7, 3)), // absent
                        Map.of(
                                LocalDate.of(2026, 7, 1), 480, // full day (default threshold)
                                LocalDate.of(2026, 7, 2), 200)))); // half day
        stubActiveResource("E1", "P1", 1L);
        when(leavePolicyClient.getLeavePolicy("P1"))
                .thenReturn(Optional.of(new LeavePolicyResponse(4, 8, false, false, true, true, 2, "MONTHLY", true)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        AttendanceUploadResult result = service.upload(
                "P1", "M1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile());

        assertThat(result.resourcesStored()).isEqualTo(1);
        assertThat(result.leavePoliciesByProject()).containsKey("P1");
        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, times(3)).save(captor.capture());
        Map<LocalDate, Attendance> byDate = captor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(Attendance::getAttendanceDate, a -> a));
        assertThat(byDate.get(LocalDate.of(2026, 7, 1)).getStatus()).isEqualTo(AttendanceStatus.P);
        assertThat(byDate.get(LocalDate.of(2026, 7, 1)).getWorkingHours()).isEqualTo(8.0);
        assertThat(byDate.get(LocalDate.of(2026, 7, 2)).getStatus()).isEqualTo(AttendanceStatus.HD);
        assertThat(byDate.get(LocalDate.of(2026, 7, 3)).getStatus()).isEqualTo(AttendanceStatus.A);
    }

    @Test
    void uploadSkipsWeekendsAndHolidaysEntirely() {
        // Fri 03-Jul, Sat 04-Jul, Sun 05-Jul, Mon 06-Jul (holiday) 2026.
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate(
                        "E1", "Asha", "Dev", Set.of(), Map.of(LocalDate.of(2026, 7, 3), 480))));
        stubActiveResource("E1", "P1", 1L);
        when(leavePolicyClient.getLeavePolicy("P1"))
                .thenReturn(Optional.of(new LeavePolicyResponse(4, 8, false, false, true, true, 2, "MONTHLY", true)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of(new com.example.leavemanagement.entity.PublicHoliday(
                        LocalDate.of(2026, 7, 6), "Test Holiday")));

        service.upload("P1", "M1", LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 6), null, anyFile());

        // Only Friday (the one working, non-holiday day) gets a row.
        verify(attendanceRepository, times(1)).save(any());
    }

    @Test
    void uploadRejectsWholeUploadWhenResourceDoesNotExist() {
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate("E1", "Asha", "Dev", Set.of(), Map.of())));
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(
                        "P1", "M1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile()))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Resource E1 does not exist."));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void uploadRejectsWholeUploadWhenResourceIsInactive() {
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate("E1", "Asha", "Dev", Set.of(), Map.of())));
        MasterResource resource = new MasterResource("E1");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(
                        "P1", "M1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile()))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Resource E1 is inactive."));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void uploadRejectsWholeUploadWhenResourceBelongsToDifferentProject() {
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate("E1", "Asha", "Dev", Set.of(), Map.of())));
        stubActiveResource("E1", "P1", 1L);

        assertThatThrownBy(() -> service.upload(
                        "P2", "M1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile()))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Resource E1 belongs to project P1, not P2."));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void uploadRejectsStartDateAfterEndDate() {
        assertThatThrownBy(() -> service.upload(
                        "P1", "M1", LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 4), null, anyFile()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Attendance Start Date cannot be greater than Attendance End Date.");
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void uploadAppliesRateYearParamToEveryUploadedResourcesActiveAssignment() {
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate(
                        "E1", "Asha", "Dev", Set.of(), Map.of(LocalDate.of(2026, 7, 1), 480))));
        MasterResource resource = new MasterResource("E1");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(resource, "P1", "Dev", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(assignment));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(leavePolicyClient.getLeavePolicy("P1"))
                .thenReturn(Optional.of(new LeavePolicyResponse(4, 8, false, false, true, true, 2, "MONTHLY", true)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        service.upload("P1", "M1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), "Year-2", anyFile());

        assertThat(assignment.getRateYear()).isEqualTo("Year-2");
        verify(projectResourceRepository).save(assignment);
    }

    // ------------------------------------------------------------------
    // Reports
    // ------------------------------------------------------------------

    @Test
    void monthlyReportComputesWorkingDaysAndStatusCounts() {
        // July 2026: 31 days, 8 weekend days (5 Sat/Sun pairs... actual count), 1 holiday.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        ProjectResource assignment = new ProjectResource(resource, "P1", "Dev", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByProjectIdAndActiveTrue("P1")).thenReturn(List.of(assignment));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of(new com.example.leavemanagement.entity.PublicHoliday(
                        LocalDate.of(2026, 7, 6), "Test Holiday"))); // a Monday

        List<Attendance> rows = new java.util.ArrayList<>();
        for (int day = 1; day <= 20; day++) {
            LocalDate date = LocalDate.of(2026, 7, day);
            if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY
                    || date.equals(LocalDate.of(2026, 7, 6))) {
                continue;
            }
            AttendanceStatus status = day == 8 ? AttendanceStatus.A : AttendanceStatus.P;
            rows.add(new Attendance(resource, "P1", "M1", date, status));
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                        eq(1L), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31))))
                .thenReturn(rows);

        List<AttendanceReportSummary> report = service.monthlyReport("P1", 2026, 7);

        assertThat(report).hasSize(1);
        AttendanceReportSummary summary = report.get(0);
        assertThat(summary.attendanceId()).isEqualTo("E1");
        assertThat(summary.employeeName()).isEqualTo("Sanju");
        assertThat(summary.projectId()).isEqualTo("P1");
        assertThat(summary.holidayDays()).isEqualTo(1);
        assertThat(summary.absentDays()).isEqualTo(1);
        assertThat(summary.presentDays()).isEqualTo(rows.size() - 1);
    }

    @Test
    void employeeReportReturnsSingleSummary() {
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(resource, "P1", "Dev", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(assignment));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        AttendanceReportSummary summary = service.employeeReport("E1", 2026, 7);

        assertThat(summary.attendanceId()).isEqualTo("E1");
        assertThat(summary.projectId()).isEqualTo("P1");
        assertThat(summary.period()).isEqualTo("July 2026");
    }

    @Test
    void employeeReportThrowsNotFoundForUnknownResource() {
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.employeeReport("E1", 2026, 7)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void quarterlyReportRequiresProjectIdOrResourceId() {
        assertThatThrownBy(() -> service.quarterlyReport(null, null, 2026, 3))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void quarterlyReportScopedToResourceIgnoresProjectId() {
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        List<AttendanceReportSummary> report = service.quarterlyReport(null, "E1", 2026, 3);

        assertThat(report).hasSize(1);
        assertThat(report.get(0).period()).isEqualTo("Q3 2026");
    }

    // ------------------------------------------------------------------
    // Payroll: quarterly leave-policy settlement
    // ------------------------------------------------------------------

    @Test
    void quarterlySettlementMapsCalendarQuarterAndReproducesIllustration1() {
        // Q2 2024 -> Apr/May/Jun. Apr 1,2 + May 1,2,3 + Jun 14 (paid) + Jun 17 (unpaid) -> 1 unpaid.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Resource A");
        setId(resource, 1L);
        List<Attendance> rows = List.of(
                new Attendance(resource, "P1", "M1", LocalDate.of(2024, 4, 1), AttendanceStatus.A),
                new Attendance(resource, "P1", "M1", LocalDate.of(2024, 4, 2), AttendanceStatus.A),
                new Attendance(resource, "P1", "M1", LocalDate.of(2024, 5, 1), AttendanceStatus.A),
                new Attendance(resource, "P1", "M1", LocalDate.of(2024, 5, 2), AttendanceStatus.A),
                new Attendance(resource, "P1", "M1", LocalDate.of(2024, 5, 3), AttendanceStatus.A),
                new Attendance(resource, "P1", "M1", LocalDate.of(2024, 6, 14), AttendanceStatus.A),
                new Attendance(resource, "P1", "M1", LocalDate.of(2024, 6, 17), AttendanceStatus.A));
        when(attendanceRepository.findByAttendanceDateBetween(LocalDate.of(2024, 4, 1), LocalDate.of(2024, 6, 30)))
                .thenReturn(rows);
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(projectConfigRepository.findById("P1")).thenReturn(Optional.empty());

        QuarterLeaveReport report = service.quarterlySettlement(2024, 2);

        assertThat(report.quarter()).isEqualTo(2);
        assertThat(report.quarterStart()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(report.quarterEnd()).isEqualTo(LocalDate.of(2024, 6, 30));
        assertThat(report.monthsWithData()).containsExactly(4, 5, 6);
        assertThat(report.resourceCount()).isEqualTo(1);

        var settlement = report.resources().get(0);
        assertThat(settlement.employeeName()).isEqualTo("Resource A");
        assertThat(settlement.calculation().permissibleLeave()).isEqualTo(6);
        assertThat(settlement.calculation().paidLeaveDays()).isEqualTo(6);
        assertThat(settlement.calculation().unpaidLeaveDays()).isEqualTo(1);
        assertThat(settlement.calculation().totalUnpaidDays()).isEqualTo(1);
    }

    @Test
    void quarterlySettlementRejectsInvalidQuarter() {
        assertThatThrownBy(() -> service.quarterlySettlement(2024, 5))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("quarter");
    }

    @Test
    void quarterlySettlementFiltersByProjectIdWhenGiven() {
        when(attendanceRepository.findByProjectIdAndAttendanceDateBetween(
                        "P1", LocalDate.of(2024, 4, 1), LocalDate.of(2024, 6, 30)))
                .thenReturn(List.of());
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        QuarterLeaveReport report = service.quarterlySettlement(2024, 2, "P1");

        assertThat(report.resourceCount()).isEqualTo(0);
        verify(attendanceRepository, never()).findByAttendanceDateBetween(any(), any());
    }
}
