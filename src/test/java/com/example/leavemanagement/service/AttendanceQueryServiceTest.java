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
import com.example.leavemanagement.dto.AttendanceReportResult;
import com.example.leavemanagement.dto.AttendanceReportSummary;
import com.example.leavemanagement.dto.AttendanceUploadResult;
import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.ActivityAttendanceReportResult;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.ActivityResourceConfig;
import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.exception.AttendanceValidationException;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.LeaveRelaxationRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
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
    private LeavePolicyClient leavePolicyClient;

    @Mock
    private LeaveRelaxationRepository leaveRelaxationRepository;

    @Mock
    private AttendancePeriodValidator periodValidator;

    @Mock
    private com.example.leavemanagement.repository.ProjectYearMappingRepository yearMappingRepository;

    @Mock
    private com.example.leavemanagement.client.ActivityDetailsClient activityDetailsClient;

    // Real leave engine — the activity-window leave path is verified end-to-end.
    private final QuarterLeavePolicy policy = new QuarterLeavePolicy();

    private AttendanceQueryService service;

    @BeforeEach
    void setUp() {
        QuarterLeaveResolver quarterLeaveResolver = new QuarterLeaveResolver(
                attendanceRepository, holidayRepository, leavePolicyClient, policy,
                projectResourceRepository);
        service = new AttendanceQueryService(
                parser, attendanceRepository, holidayRepository, masterResourceRepository,
                projectResourceRepository, leavePolicyClient, leaveRelaxationRepository,
                quarterLeaveResolver, periodValidator, yearMappingRepository,
                activityDetailsClient);
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
                .thenReturn(Optional.of(new LeavePolicyResponse(4, 8, "HALF_DAY", "FULL_DAY", true, true, 2, "MONTHLY", true, false, true, true)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        AttendanceUploadResult result = service.upload(
                "P1", null, "M1", null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile());

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
                .thenReturn(Optional.of(new LeavePolicyResponse(4, 8, "HALF_DAY", "FULL_DAY", true, true, 2, "MONTHLY", true, false, true, true)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of(new com.example.leavemanagement.entity.PublicHoliday(
                        LocalDate.of(2026, 7, 6), "Test Holiday")));

        service.upload("P1", null, "M1", null, LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 6), null, anyFile());

        // Only Friday (the one working, non-holiday day) gets a row.
        verify(attendanceRepository, times(1)).save(any());
    }

    @Test
    void uploadRejectsWholeUploadWhenResourceDoesNotExist() {
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate("E1", "Asha", "Dev", Set.of(), Map.of())));
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(
                        "P1", null, "M1", null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile()))
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
                        "P1", null, "M1", null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile()))
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
                        "P2", null, "M1", null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3), null, anyFile()))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Resource E1 belongs to project P1, not P2."));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void uploadRejectsStartDateAfterEndDate() {
        assertThatThrownBy(() -> service.upload(
                        "P1", null, "M1", null, LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 4), null, anyFile()))
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
                .thenReturn(Optional.of(new LeavePolicyResponse(4, 8, "HALF_DAY", "FULL_DAY", true, true, 2, "MONTHLY", true, false, true, true)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        service.upload("P1", null, "M1", null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), "Year-2", anyFile());

        assertThat(assignment.getRateYear()).isEqualTo("Year-2");
        verify(projectResourceRepository).save(assignment);
    }

    @Test
    void uploadRejectsWhenPeriodExceedsDesignationPlannedDuration() {
        // Activity 11-Jan-2026..10-Apr-2026; designation duration 2 months → planned end 10-Mar-2026.
        // Uploading a period ending 10-Apr-2026 for that designation must be rejected.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Hardik");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(
                resource, "P1", "Application Security Engineer", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(assignment));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(parser.parse(any(), any(), any())).thenReturn(List.of(new EmployeeAttendanceByDate(
                "E1", "Hardik", "Application Security Engineer", Set.of(), Map.of())));
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.of(
                new LeavePolicyResponse(4, 8, "HALF_DAY", "FULL_DAY", true, true, 2, "MONTHLY", true, false, true, true)));
        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9",
                        LocalDate.of(2026, 1, 11), LocalDate.of(2026, 4, 10),
                        List.of(new ActivityResourceConfig("Application Security Engineer", 1, 2.0, 136064.0)))));

        assertThatThrownBy(() -> service.upload(
                        "P1", "Org1", "M1", "ACT-001",
                        LocalDate.of(2026, 3, 11), LocalDate.of(2026, 4, 10), null, anyFile()))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .anySatisfy(msg -> assertThat(msg)
                                .contains("planned duration of 2.0 month(s)")
                                .contains("beyond the planned end 2026-03-10")));
        verify(attendanceRepository, never()).save(any());
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
        when(projectResourceRepository.findByProjectIdActiveDuring(eq("P1"), any(), any())).thenReturn(List.of(assignment));
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
            rows.add(new Attendance(resource, "P1", null, "M1", null,date, status));
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                        eq(1L), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31))))
                .thenReturn(rows);

        AttendanceReportResult report = service.monthlyReport("P1", 2026, 7);

        assertThat(report.resources()).hasSize(1);
        AttendanceReportSummary summary = report.resources().get(0);
        assertThat(summary.attendanceId()).isEqualTo("E1");
        assertThat(summary.employeeName()).isEqualTo("Sanju");
        assertThat(summary.projectId()).isEqualTo("P1");
        assertThat(summary.holidayDays()).isEqualTo(1);
        assertThat(summary.absentDays()).isEqualTo(1);
        assertThat(summary.presentDays()).isEqualTo((double) (rows.size() - 1));
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

        AttendanceReportResult result = service.employeeReport("E1", 2026, 7);
        AttendanceReportSummary summary = result.resources().get(0);

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
    void activityReportUsesActivityWindowFetchedLive() {
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        ProjectResource assignment =
                new ProjectResource(resource, "P1", "Architect Dev Ops Automation", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("Sample Activity",
                        LocalDate.of(2027, 5, 11), LocalDate.of(2027, 8, 10),
                        List.of(new ActivityResourceConfig("Architect Dev Ops Automation", 2, 3.0, 211982.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(List.of(resource));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(any(), any(), any())).thenReturn(List.of());

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "ACT-001");

        assertThat(report.activityId()).isEqualTo("ACT-001");
        assertThat(report.activityStartDate()).isEqualTo(LocalDate.of(2027, 5, 11));
        assertThat(report.activityEndDate()).isEqualTo(LocalDate.of(2027, 8, 10));
        assertThat(report.configuredResourceCount()).isEqualTo(2);
        assertThat(report.resources()).hasSize(1);
        assertThat(report.resources().get(0).attendanceId()).isEqualTo("E1");
    }

    @Test
    void activityReportProratesPermissibleLeaveForMidActivityJoiner() {
        // Activity 07-Jan..06-Apr-2026 (90 days), quota 6. Resource joins 05-Feb → prorated quota
        // round(6 × 61/90) = 4. With 6 full-day absences → paid 4, unpaid 2 (not paid 6).
        MasterResource resource = new MasterResource("E1");
        resource.setName("Neha");
        setId(resource, 1L);
        ProjectResource assignment = new ProjectResource(
                resource, "P1", "Architect Dev Ops Automation", LocalDate.of(2026, 2, 5));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Architect Dev Ops Automation", 2, 3.0, 211982.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(List.of(resource));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());
        List<Attendance> absences = List.of(
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 9), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 10), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 11), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 12), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 13), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 17), AttendanceStatus.A));
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(absences);

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "ACT-001");
        AttendanceReportSummary s = report.resources().get(0);

        assertThat(s.paidLeaveDays()).isEqualTo(4.0);
        assertThat(s.unpaidLeaveDays()).isEqualTo(2.0);
    }

    // ------------------------------------------------------------------
    // Resource cost calculator
    // ------------------------------------------------------------------

    @Test
    void employeeMonthlyCostMatchesWorkedExample() {
        // July 2026: 31 calendar days. 22 present, 1 absent. Leave policy: 0 paid/quarter → 1 unpaid.
        // cost = round2(88200 × 30/31) = 85354.84.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(resource, "P1", "Java Dev", LocalDate.of(2020, 1, 1));
        assignment.setRateYear("Year-3");
        assignment.setRateCardByYear(Map.of("Year-3", 88200.0));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(assignment));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(
                Optional.of(new LeavePolicyResponse(null, null, null, null, null, null, 0, "QUARTERLY", null, false, null, null)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        List<Attendance> rows = new java.util.ArrayList<>();
        boolean markedAbsent = false;
        for (LocalDate date = LocalDate.of(2026, 7, 1); !date.isAfter(LocalDate.of(2026, 7, 31));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }
            AttendanceStatus status = !markedAbsent ? AttendanceStatus.A : AttendanceStatus.P;
            markedAbsent = true;
            rows.add(new Attendance(resource, "P1", null, "M1", null,date, status));
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(rows);

        MonthlyResourceCost cost = service.employeeMonthlyCost("E1", 2026, 7);

        assertThat(cost.workingDays()).isEqualTo(23);
        assertThat(cost.presentDays()).isEqualTo(22.0);
        assertThat(cost.calendarDays()).isEqualTo(31);
        assertThat(cost.unpaidLeaveDays()).isEqualTo(1.0);
        assertThat(cost.rateYear()).isEqualTo("Year-3");
        assertThat(cost.monthlyRate()).isEqualTo(88200.0);
        assertThat(cost.cost()).isEqualTo(85354.84);
    }

    @Test
    void monthlyCostIsZeroWhenResourceHasNoRateYearSet() {
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        ProjectResource assignment = new ProjectResource(resource, "P1", "Dev", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByProjectIdActiveDuring(eq("P1"), any(), any())).thenReturn(List.of(assignment));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        List<MonthlyResourceCost> report = service.monthlyCostReport("P1", 2026, 7);

        assertThat(report).hasSize(1);
        assertThat(report.get(0).rateYear()).isNull();
        assertThat(report.get(0).monthlyRate()).isZero();
        assertThat(report.get(0).cost()).isZero();
    }

    @Test
    void monthlyCostReflectsAttendanceOnlyRelaxationIsSettledQuarterly() {
        // July 2026: 31 calendar days, 3 absent. Leave policy: 0 paid/quarter → 3 unpaid days.
        // cost = round2(88200 × 28/31) = 79664.52. Relaxation is quarterly, not monthly.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(resource, "P1", "Java Dev", LocalDate.of(2020, 1, 1));
        assignment.setRateYear("Year-3");
        assignment.setRateCardByYear(Map.of("Year-3", 88200.0));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(assignment));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(
                Optional.of(new LeavePolicyResponse(null, null, null, null, null, null, 0, "QUARTERLY", null, false, null, null)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        List<Attendance> rows = new java.util.ArrayList<>();
        int absentMarked = 0;
        for (LocalDate date = LocalDate.of(2026, 7, 1); !date.isAfter(LocalDate.of(2026, 7, 31));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }
            AttendanceStatus status = absentMarked < 3 ? AttendanceStatus.A : AttendanceStatus.P;
            absentMarked++;
            rows.add(new Attendance(resource, "P1", null, "M1", null,date, status));
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(rows);

        MonthlyResourceCost cost = service.employeeMonthlyCost("E1", 2026, 7);

        assertThat(cost.workingDays()).isEqualTo(23);
        assertThat(cost.presentDays()).isEqualTo(20.0);
        assertThat(cost.calendarDays()).isEqualTo(31);
        assertThat(cost.unpaidLeaveDays()).isEqualTo(3.0);
        assertThat(cost.cost()).isEqualTo(79664.52);
    }

    @Test
    void monthlyCostForAugustReflectsAttendanceOnlyNoRelaxation() {
        // August 2026: 31 calendar days, 2 absent. Leave policy: 0 paid/quarter → 2 unpaid days.
        // cost = round2(88200 × 29/31) = 82509.68. Relaxation is quarterly, not monthly.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        when(masterResourceRepository.findByResId("E1")).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(resource, "P1", "Java Dev", LocalDate.of(2020, 1, 1));
        assignment.setRateYear("Year-3");
        assignment.setRateCardByYear(Map.of("Year-3", 88200.0));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(1L)).thenReturn(Optional.of(assignment));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(
                Optional.of(new LeavePolicyResponse(null, null, null, null, null, null, 0, "QUARTERLY", null, false, null, null)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());

        List<Attendance> augustRows = new java.util.ArrayList<>();
        int augustAbsentMarked = 0;
        for (LocalDate date = LocalDate.of(2026, 8, 1); !date.isAfter(LocalDate.of(2026, 8, 31));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }
            AttendanceStatus status = augustAbsentMarked < 2 ? AttendanceStatus.A : AttendanceStatus.P;
            augustAbsentMarked++;
            augustRows.add(new Attendance(resource, "P1", null, "M1", null,date, status));
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(augustRows);

        MonthlyResourceCost cost = service.employeeMonthlyCost("E1", 2026, 8);

        assertThat(cost.workingDays()).isEqualTo(21);
        assertThat(cost.presentDays()).isEqualTo(19.0);
        assertThat(cost.calendarDays()).isEqualTo(31);
        assertThat(cost.unpaidLeaveDays()).isEqualTo(2.0);
        assertThat(cost.cost()).isEqualTo(82509.68);
    }

}
