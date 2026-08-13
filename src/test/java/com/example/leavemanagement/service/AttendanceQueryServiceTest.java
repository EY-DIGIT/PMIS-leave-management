package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
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
import com.example.leavemanagement.dto.ActivityResourceDetailsReport;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
import com.example.leavemanagement.dto.ActivityResourceConfig;
import com.example.leavemanagement.dto.ActivityAvailabilityReport;
import com.example.leavemanagement.dto.MonthlyResourceCost;
import com.example.leavemanagement.dto.ResourceAvailabilityReport;
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

    @Mock
    private ResourceBasedPeriodService resourceBasedPeriodService;

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
                activityDetailsClient, resourceBasedPeriodService);
        // No resource-based period by default → cost is not gated (existing numbers unchanged).
        lenient().when(resourceBasedPeriodService.resolve(any())).thenReturn(Optional.empty());
        // By default the activity-filtered attendance query mirrors the plain date-range one (ignores
        // activityId), so existing tests' attendance stubs apply. Transfer tests override this explicitly.
        lenient().when(attendanceRepository.findByResourceIdAndActivityIdAndAttendanceDateBetween(
                        any(), any(), any(), any()))
                .thenAnswer(inv -> attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                        inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));
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
    void uploadUpsertsExistingRowIncrementallyWithoutBulkDelete() {
        // A re-upload updates the existing (resource, date) row in place — no delete, other rows untouched.
        LocalDate day = LocalDate.of(2026, 7, 1);
        when(parser.parse(any(), any(), any()))
                .thenReturn(List.of(new EmployeeAttendanceByDate("E1", "Asha", "Dev", Set.of(), Map.of(day, 480))));
        stubActiveResource("E1", "P1", 1L);
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.of(
                new LeavePolicyResponse(4, 8, "HALF_DAY", "FULL_DAY", true, true, 2, "MONTHLY", true, false, true, true)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        MasterResource resource = masterResourceRepository.findByResId("E1").orElseThrow();
        Attendance existing = new Attendance(resource, "P1", null, "M1", null, day, AttendanceStatus.A);
        when(attendanceRepository.findByResourceIdAndAttendanceDate(1L, day)).thenReturn(Optional.of(existing));

        service.upload("P1", null, "M1", null, day, day, null, anyFile());

        // Existing row updated in place (A → P), and no bulk delete was issued.
        assertThat(existing.getStatus()).isEqualTo(AttendanceStatus.P);
        verify(attendanceRepository).save(existing);
        verify(attendanceRepository, never()).bulkDeleteByProjectIdAndDateBetween(any(), any(), any());
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

    @Test
    void activityReportUsesFullActivityQuotaEvenWhenOnlyOneMonthUploaded() {
        // Activity 07-Jan..06-Apr-2026 (quota 6). Only month 1 uploaded → reportEnd narrows to 06-Feb.
        // Leave must still use the full activity quota of 6, so 6 absences → paid 6, unpaid 0 (not paid 2).
        MasterResource resource = new MasterResource("E1");
        resource.setName("Hardik");
        setId(resource, 1L);
        ProjectResource assignment =
                new ProjectResource(resource, "P1", "Application Security Engineer", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Application Security Engineer", 1, 2.0, 136064.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 1, 7)));
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 2, 6)));
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(List.of(resource));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any())).thenReturn(List.of(
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 7), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 8), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 9), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 12), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 13), AttendanceStatus.A),
                new Attendance(resource, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 14), AttendanceStatus.A)));

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "ACT-001");
        AttendanceReportSummary s = report.resources().get(0);

        assertThat(s.leaveTaken()).isEqualTo(6.0);
        assertThat(s.paidLeaveDays()).isEqualTo(6.0);
        assertThat(s.unpaidLeaveDays()).isEqualTo(0.0);
    }

    @Test
    void replacementInheritsRemainingSharedPaidLeaveNotAFreshEntitlement() {
        // Activity 07-Jan..06-Apr-2026, quota 6, Program Manager qty 1.
        // Kuldeep joins 05-Feb → pool prorated to 4; takes 3 paid absences (05,06,09-Feb) → remaining 1.
        // Rahul replaces Kuldeep (11-Feb..); takes 2 absences → inherits 1 → paid 1, unpaid 1.
        MasterResource kuldeep = new MasterResource("E1");
        kuldeep.setName("Kuldeep");
        setId(kuldeep, 1L);
        MasterResource rahul = new MasterResource("E2");
        rahul.setName("Rahul");
        setId(rahul, 2L);

        ProjectResource kuldeepAssign =
                new ProjectResource(kuldeep, "P1", "Program Manager", LocalDate.of(2026, 2, 5));
        kuldeepAssign.setActive(false);
        kuldeepAssign.setAssignmentEndDate(LocalDate.of(2026, 2, 10));
        ProjectResource rahulAssign =
                new ProjectResource(rahul, "P1", "Program Manager", LocalDate.of(2026, 2, 11));

        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.empty());
        when(projectResourceRepository.findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc("E1", "P1"))
                .thenReturn(List.of(kuldeepAssign));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E2", "P1"))
                .thenReturn(Optional.of(rahulAssign));
        when(projectResourceRepository.findByProjectIdAndRole("P1", "Program Manager"))
                .thenReturn(List.of(kuldeepAssign, rahulAssign));

        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(List.of(kuldeep, rahul));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());

        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any())).thenReturn(List.of(
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 5), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 6), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 9), AttendanceStatus.A)));
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(2L), any(), any())).thenReturn(List.of(
                new Attendance(rahul, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 12), AttendanceStatus.A),
                new Attendance(rahul, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 13), AttendanceStatus.A)));

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "ACT-001");
        AttendanceReportSummary kuldeepRow = report.resources().stream()
                .filter(r -> r.attendanceId().equals("E1")).findFirst().orElseThrow();
        AttendanceReportSummary rahulRow = report.resources().stream()
                .filter(r -> r.attendanceId().equals("E2")).findFirst().orElseThrow();

        assertThat(kuldeepRow.paidLeaveDays()).isEqualTo(3.0);
        assertThat(kuldeepRow.unpaidLeaveDays()).isEqualTo(0.0);
        assertThat(rahulRow.paidLeaveDays()).isEqualTo(1.0);
        assertThat(rahulRow.unpaidLeaveDays()).isEqualTo(1.0);
    }

    @Test
    void replacementLeaveIsCountedOnlyFromJoiningDateNotActivityStart() {
        // Kuldeep 07-Jan..09-Feb (0 leaves), Rahul replaces from 10-Feb. Rahul has a stray pre-join
        // absence (08-Jan) plus two post-join (12,13-Feb). Only the post-join leaves must be counted.
        MasterResource kuldeep = new MasterResource("E1");
        kuldeep.setName("Kuldeep");
        setId(kuldeep, 1L);
        MasterResource rahul = new MasterResource("E2");
        rahul.setName("Rahul");
        setId(rahul, 2L);

        ProjectResource kuldeepAssign =
                new ProjectResource(kuldeep, "P1", "Program Manager", LocalDate.of(2026, 1, 7));
        kuldeepAssign.setActive(false);
        kuldeepAssign.setAssignmentEndDate(LocalDate.of(2026, 2, 9));
        ProjectResource rahulAssign =
                new ProjectResource(rahul, "P1", "Program Manager", LocalDate.of(2026, 2, 10));

        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.empty());
        when(projectResourceRepository.findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc("E1", "P1"))
                .thenReturn(List.of(kuldeepAssign));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E2", "P1"))
                .thenReturn(Optional.of(rahulAssign));
        when(projectResourceRepository.findByProjectIdAndRole("P1", "Program Manager"))
                .thenReturn(List.of(kuldeepAssign, rahulAssign));
        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(List.of(kuldeep, rahul));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any())).thenReturn(List.of());
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(2L), any(), any())).thenReturn(List.of(
                new Attendance(rahul, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 8), AttendanceStatus.A),
                new Attendance(rahul, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 12), AttendanceStatus.A),
                new Attendance(rahul, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 13), AttendanceStatus.A)));

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "ACT-001");
        AttendanceReportSummary rahulRow = report.resources().stream()
                .filter(r -> r.attendanceId().equals("E2")).findFirst().orElseThrow();

        // Only the two post-joining absences count; the 08-Jan pre-join date is excluded.
        assertThat(rahulRow.leaveTaken()).isEqualTo(2.0);
        assertThat(rahulRow.paidLeaveDays()).isEqualTo(2.0);
        assertThat(rahulRow.unpaidLeaveDays()).isEqualTo(0.0);
    }

    @Test
    void transferToAnotherActivityDoesNotLeakThatActivitysAttendanceIntoThisOne() {
        // Kuldeep worked A1 (Jan–Feb) then transferred to A2 (Mar). Same open project assignment. A1's report
        // window is the whole activity, but only A1 attendance must count — the Mar A2 absences must NOT leak in.
        MasterResource kuldeep = new MasterResource("E1");
        kuldeep.setName("Kuldeep");
        setId(kuldeep, 1L);
        ProjectResource assignment =
                new ProjectResource(kuldeep, "P1", "Program Manager", LocalDate.of(2026, 1, 7));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(assignment));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc("E1", "P1"))
                .thenReturn(List.of(assignment));
        when(projectResourceRepository.findByProjectIdAndRole("P1", "Program Manager"))
                .thenReturn(List.of(assignment));
        when(activityDetailsClient.getActivityDetails("A1")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(List.of(kuldeep));
        when(attendanceRepository.existsByResIdAndActivityIdAndDateBetween(eq("E1"), eq("A1"), any(), any()))
                .thenReturn(true);
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());
        // A1-filtered attendance: 2 absences in Jan (A1). The plain date-range query would ALSO include a
        // March A2 absence — but the activity-filtered path must ignore it.
        doReturn(List.of(
                new Attendance(kuldeep, "P1", null, "M1", "A1", LocalDate.of(2026, 1, 7), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "A1", LocalDate.of(2026, 1, 8), AttendanceStatus.A)))
                .when(attendanceRepository).findByResourceIdAndActivityIdAndAttendanceDateBetween(
                        eq(1L), eq("A1"), any(), any());
        // Date-range (unfiltered) attendance includes the March A2 absence — the code must NOT use this path.
        lenient().when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(List.of(
                new Attendance(kuldeep, "P1", null, "M1", "A1", LocalDate.of(2026, 1, 7), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "A1", LocalDate.of(2026, 1, 8), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "A2", LocalDate.of(2026, 3, 9), AttendanceStatus.A)));

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "A1");
        AttendanceReportSummary row = report.resources().get(0);

        // Only the 2 A1 absences count — the March A2 absence is excluded.
        assertThat(row.absentDays()).isEqualTo(2);
        assertThat(row.leaveTaken()).isEqualTo(2.0);
    }

    @Test
    void promotionYieldsTwoIndependentRowsPerDesignation() {
        // Kuldeep promoted mid-activity: Program Manager 07-Jan..06-Mar, Program Director 07-Mar..06-Apr.
        // Activity report returns TWO rows, each with its own designation and independent leave entitlement.
        MasterResource kuldeep = new MasterResource("E1");
        kuldeep.setName("Kuldeep");
        setId(kuldeep, 1L);
        ProjectResource pmAssign =
                new ProjectResource(kuldeep, "P1", "Program Manager", LocalDate.of(2026, 1, 7));
        pmAssign.setActive(false);
        pmAssign.setAssignmentEndDate(LocalDate.of(2026, 3, 6));
        ProjectResource pdAssign =
                new ProjectResource(kuldeep, "P1", "Program Director", LocalDate.of(2026, 3, 7));

        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0),
                                new ActivityResourceConfig("Program Director", 1, 3.0, 250000.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(List.of(kuldeep));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc("E1", "P1"))
                .thenReturn(List.of(pdAssign, pmAssign));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.of(pdAssign));
        when(attendanceRepository.existsByResIdAndActivityIdAndDateBetween(eq("E1"), eq("ACT-001"), any(), any()))
                .thenReturn(true);
        when(projectResourceRepository.findByProjectIdAndRole("P1", "Program Manager"))
                .thenReturn(List.of(pmAssign));
        when(projectResourceRepository.findByProjectIdAndRole("P1", "Program Director"))
                .thenReturn(List.of(pdAssign));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());
        // PM period [07-Jan..06-Mar]: 3 absences (quota 4 → paid 3). PD period [07-Mar..06-Apr]: 1 absence (quota 2 → paid 1).
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                eq(1L), eq(LocalDate.of(2026, 1, 7)), eq(LocalDate.of(2026, 3, 6)))).thenReturn(List.of(
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 7), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 8), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 9), AttendanceStatus.A)));
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                eq(1L), eq(LocalDate.of(2026, 3, 7)), eq(LocalDate.of(2026, 4, 6)))).thenReturn(List.of(
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 3, 9), AttendanceStatus.A)));

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "ACT-001");

        assertThat(report.resources()).hasSize(2);
        AttendanceReportSummary pm = report.resources().stream()
                .filter(r -> "Program Manager".equals(r.designation())).findFirst().orElseThrow();
        AttendanceReportSummary pd = report.resources().stream()
                .filter(r -> "Program Director".equals(r.designation())).findFirst().orElseThrow();

        assertThat(pm.paidLeaveDays()).isEqualTo(3.0);   // PM own entitlement (prorated 4)
        assertThat(pm.unpaidLeaveDays()).isEqualTo(0.0);
        assertThat(pd.paidLeaveDays()).isEqualTo(1.0);   // PD independent entitlement (prorated 2)
        assertThat(pd.unpaidLeaveDays()).isEqualTo(0.0);
    }

    @Test
    void replacementDetectedByLifecycleDatesEvenWithMultipleConfiguredSlots() {
        // Program Manager qty 2. Kuldeep 07-Jan..09-Feb (5 paid), Ravindra 07-Jan..active (independent),
        // Rahul joins 10-Feb..active. Rahul chains to Kuldeep by lifecycle dates (no replacedByResId),
        // inheriting the remaining 1; Ravindra keeps his own full quota.
        MasterResource kuldeep = new MasterResource("E1");
        kuldeep.setName("Kuldeep");
        setId(kuldeep, 1L);
        MasterResource rahul = new MasterResource("E2");
        rahul.setName("Rahul");
        setId(rahul, 2L);
        MasterResource ravindra = new MasterResource("E3");
        ravindra.setName("Ravindra");
        setId(ravindra, 3L);

        ProjectResource kuldeepAssign =
                new ProjectResource(kuldeep, "P1", "Program Manager", LocalDate.of(2026, 1, 7));
        kuldeepAssign.setActive(false);
        kuldeepAssign.setAssignmentEndDate(LocalDate.of(2026, 2, 9));
        ProjectResource ravindraAssign =
                new ProjectResource(ravindra, "P1", "Program Manager", LocalDate.of(2026, 1, 7));
        ProjectResource rahulAssign =
                new ProjectResource(rahul, "P1", "Program Manager", LocalDate.of(2026, 2, 10));

        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E1", "P1"))
                .thenReturn(Optional.empty());
        when(projectResourceRepository.findByResource_ResIdAndProjectIdOrderByAssignmentStartDateDesc("E1", "P1"))
                .thenReturn(List.of(kuldeepAssign));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E2", "P1"))
                .thenReturn(Optional.of(rahulAssign));
        when(projectResourceRepository.findByResource_ResIdAndProjectIdAndActiveTrue("E3", "P1"))
                .thenReturn(Optional.of(ravindraAssign));
        when(projectResourceRepository.findByProjectIdAndRole("P1", "Program Manager"))
                .thenReturn(List.of(kuldeepAssign, ravindraAssign, rahulAssign));

        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Program Manager", 2, 3.0, 198764.0)))));
        when(attendanceRepository.findMinDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findDistinctResourcesByActivityIdAndDateBetween(eq("ACT-001"), any(), any()))
                .thenReturn(List.of(kuldeep, ravindra, rahul));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any())).thenReturn(List.of());
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());

        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any())).thenReturn(List.of(
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 7), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 8), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 9), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 12), AttendanceStatus.A),
                new Attendance(kuldeep, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 13), AttendanceStatus.A)));
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(3L), any(), any())).thenReturn(List.of(
                new Attendance(ravindra, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 14), AttendanceStatus.A),
                new Attendance(ravindra, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 15), AttendanceStatus.A),
                new Attendance(ravindra, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 1, 16), AttendanceStatus.A)));
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(2L), any(), any())).thenReturn(List.of(
                new Attendance(rahul, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 12), AttendanceStatus.A),
                new Attendance(rahul, "P1", null, "M1", "ACT-001", LocalDate.of(2026, 2, 13), AttendanceStatus.A)));

        ActivityAttendanceReportResult report = service.activityReport("P1", "M1", "ACT-001");
        AttendanceReportSummary kuldeepRow = report.resources().stream()
                .filter(r -> r.attendanceId().equals("E1")).findFirst().orElseThrow();
        AttendanceReportSummary rahulRow = report.resources().stream()
                .filter(r -> r.attendanceId().equals("E2")).findFirst().orElseThrow();
        AttendanceReportSummary ravindraRow = report.resources().stream()
                .filter(r -> r.attendanceId().equals("E3")).findFirst().orElseThrow();

        assertThat(kuldeepRow.paidLeaveDays()).isEqualTo(5.0);
        assertThat(kuldeepRow.unpaidLeaveDays()).isEqualTo(0.0);
        assertThat(rahulRow.paidLeaveDays()).isEqualTo(1.0);   // inherited remaining from Kuldeep
        assertThat(rahulRow.unpaidLeaveDays()).isEqualTo(1.0);
        assertThat(ravindraRow.paidLeaveDays()).isEqualTo(3.0); // independent full quota
        assertThat(ravindraRow.unpaidLeaveDays()).isEqualTo(0.0);
    }

    @Test
    void resourceDetailsByDesignationListsEachResourcesActivityHistory() {
        // Project P1, designation Program Manager. Kuldeep (07-Jan..10-Feb, completed) then Rahul
        // (11-Feb.., active) both worked activity A1 — listed with their worked periods and status.
        MasterResource kuldeep = new MasterResource("R101");
        kuldeep.setName("Kuldeep");
        setId(kuldeep, 1L);
        MasterResource rahul = new MasterResource("R205");
        rahul.setName("Rahul");
        setId(rahul, 2L);

        ProjectResource kuldeepAssign =
                new ProjectResource(kuldeep, "P1", "Program Manager", LocalDate.of(2026, 1, 7));
        kuldeepAssign.setActive(false);
        kuldeepAssign.setAssignmentEndDate(LocalDate.of(2026, 2, 10));
        ProjectResource rahulAssign =
                new ProjectResource(rahul, "P1", "Program Manager", LocalDate.of(2026, 2, 11));

        when(projectResourceRepository.findByProjectIdAndRole("P1", "Program Manager"))
                .thenReturn(List.of(kuldeepAssign, rahulAssign));
        when(attendanceRepository.findDistinctActivityIdsByResourceId(1L)).thenReturn(List.of("ACT-001"));
        when(attendanceRepository.findDistinctActivityIdsByResourceId(2L)).thenReturn(List.of("ACT-001"));
        when(activityDetailsClient.getActivityDetails("ACT-001")).thenReturn(Optional.of(
                new ActivityDetailsResponse("Resource Deployment", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6),
                        List.of(new ActivityResourceConfig("Program Manager", 1, 3.0, 198764.0)))));

        ActivityResourceDetailsReport report = service.resourceDetailsByDesignation("P1", "Program Manager", null);

        assertThat(report.designation()).isEqualTo("Program Manager");
        assertThat(report.projectId()).isEqualTo("P1");
        assertThat(report.resources()).hasSize(2);

        ActivityResourceDetailsReport.ResourceHistory first = report.resources().stream()
                .filter(r -> r.resourceId().equals("R101")).findFirst().orElseThrow();
        assertThat(first.activities().get(0).activityName()).isEqualTo("Resource Deployment");
        assertThat(first.activities().get(0).workedFrom()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(first.activities().get(0).workedTo()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(first.activities().get(0).status()).isEqualTo("Completed");

        ActivityResourceDetailsReport.ResourceHistory second = report.resources().stream()
                .filter(r -> r.resourceId().equals("R205")).findFirst().orElseThrow();
        assertThat(second.activities().get(0).workedFrom()).isEqualTo(LocalDate.of(2026, 2, 11));
        assertThat(second.activities().get(0).workedTo()).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(second.activities().get(0).status()).isEqualTo("Active");
    }

    // ------------------------------------------------------------------
    // Resource cost calculator
    // ------------------------------------------------------------------

    @Test
    void availabilityReportSumsBusinessDaysAndHoursAndDerivesSlaSeverity() {
        // Feb 2026: 16 present weekdays @ 9h = 144h → SLA 007 severity 0.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        ProjectResource assignment = new ProjectResource(resource, "P1", "Program Manager", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByProjectIdActiveDuring(eq("P1"), any(), any()))
                .thenReturn(List.of(assignment));

        List<Attendance> rows = new java.util.ArrayList<>();
        LocalDate date = LocalDate.of(2026, 2, 1);
        int added = 0;
        while (added < 16) {
            if (date.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                Attendance a = new Attendance(resource, "P1", null, "M1", null, date, AttendanceStatus.P);
                a.setWorkingHours(9.0);
                rows.add(a);
                added++;
            }
            date = date.plusDays(1);
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(rows);

        ResourceAvailabilityReport report = service.availabilityReport("P1", 2026, 2, null);

        assertThat(report.resources()).hasSize(1);
        ResourceAvailabilityReport.ResourceAvailability r = report.resources().get(0);
        assertThat(r.attendanceId()).isEqualTo("E1");
        assertThat(r.designation()).isEqualTo("Program Manager");
        assertThat(r.businessDays()).isEqualTo(16);
        assertThat(r.totalWorkingHours()).isEqualTo(144.0);
        assertThat(r.presentDays()).isEqualTo(16.0);
        assertThat(r.slaSeverity()).isZero();
    }

    @Test
    void availabilityReportDerivesSeverity4WhenBelowThresholds() {
        // Only 10 present days @ 8h = 80h → < 12 days & < 108 hrs → severity 4.
        MasterResource resource = new MasterResource("E1");
        resource.setName("Sanju");
        setId(resource, 1L);
        ProjectResource assignment = new ProjectResource(resource, "P1", "Program Manager", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByProjectIdActiveDuring(eq("P1"), any(), any()))
                .thenReturn(List.of(assignment));

        List<Attendance> rows = new java.util.ArrayList<>();
        LocalDate date = LocalDate.of(2026, 2, 2);
        for (int i = 0; i < 10; i++) {
            Attendance a = new Attendance(resource, "P1", null, "M1", null, date.plusDays(i), AttendanceStatus.P);
            a.setWorkingHours(8.0);
            rows.add(a);
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(rows);

        ResourceAvailabilityReport.ResourceAvailability r =
                service.availabilityReport("P1", 2026, 2, null).resources().get(0);
        assertThat(r.businessDays()).isEqualTo(10);
        assertThat(r.totalWorkingHours()).isEqualTo(80.0);
        assertThat(r.slaSeverity()).isEqualTo(4);
    }

    @Test
    void activityAvailabilityReportAggregatesPerActivityAlignedCycle() {
        // Activity A1 (07-Jan..06-Apr) → cycles 07-Jan..06-Feb, 07-Feb..06-Mar, 07-Mar..06-Apr.
        // Uploaded up to 16-Feb, so only the first two cycles appear.
        // Cycle 1 (07-Jan..06-Feb): Kuldeep 16 @ 9h + Sanju 12 @ 8h → 28 days, 240h, 2 resources.
        // Cycle 2 (07-Feb..06-Mar): Kuldeep 10 @ 8h → 10 days, 80h, 1 resource.
        MasterResource kuldeep = new MasterResource("E1");
        kuldeep.setName("Kuldeep");
        setId(kuldeep, 1L);
        MasterResource sanju = new MasterResource("E2");
        sanju.setName("Sanju");
        setId(sanju, 2L);

        when(activityDetailsClient.getActivityDetails("A1")).thenReturn(Optional.of(
                new ActivityDetailsResponse("D9", LocalDate.of(2026, 1, 7), LocalDate.of(2026, 4, 6), List.of())));
        when(attendanceRepository.findMaxDateByActivityIdAndDateBetween(eq("A1"), any(), any()))
                .thenReturn(Optional.of(LocalDate.of(2026, 2, 16)));

        List<Attendance> all = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Attendance a = new Attendance(kuldeep, "P1", null, "M1", "A1", LocalDate.of(2026, 1, 7).plusDays(i), AttendanceStatus.P);
            a.setWorkingHours(9.0);
            all.add(a);
        }
        for (int i = 0; i < 12; i++) {
            Attendance a = new Attendance(sanju, "P1", null, "M1", "A1", LocalDate.of(2026, 1, 7).plusDays(i), AttendanceStatus.P);
            a.setWorkingHours(8.0);
            all.add(a);
        }
        for (int i = 0; i < 10; i++) { // 07-Feb..16-Feb → cycle 2
            Attendance a = new Attendance(kuldeep, "P1", null, "M1", "A1", LocalDate.of(2026, 2, 7).plusDays(i), AttendanceStatus.P);
            a.setWorkingHours(8.0);
            all.add(a);
        }
        when(attendanceRepository.findByActivityIdAndAttendanceDateBetween(eq("A1"), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate s = inv.getArgument(1);
                    LocalDate e = inv.getArgument(2);
                    return all.stream()
                            .filter(a -> !a.getAttendanceDate().isBefore(s) && !a.getAttendanceDate().isAfter(e))
                            .toList();
                });

        ActivityAvailabilityReport report = service.activityAvailabilityReport("P1", "A1");

        assertThat(report.months()).hasSize(2);

        ActivityAvailabilityReport.MonthlyAvailability c1 = report.months().get(0);
        assertThat(c1.period()).isEqualTo("07-Jan-2026 to 06-Feb-2026");
        assertThat(c1.fromDate()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(c1.toDate()).isEqualTo(LocalDate.of(2026, 2, 6));
        assertThat(c1.resourceCount()).isEqualTo(2);
        assertThat(c1.totalBusinessDays()).isEqualTo(28);
        assertThat(c1.totalWorkingHours()).isEqualTo(240.0); // 16*9 + 12*8

        ActivityAvailabilityReport.MonthlyAvailability c2 = report.months().get(1);
        assertThat(c2.period()).isEqualTo("07-Feb-2026 to 06-Mar-2026");
        assertThat(c2.fromDate()).isEqualTo(LocalDate.of(2026, 2, 7));
        assertThat(c2.toDate()).isEqualTo(LocalDate.of(2026, 3, 6));
        assertThat(c2.resourceCount()).isEqualTo(1);
        assertThat(c2.totalBusinessDays()).isEqualTo(10);
        assertThat(c2.totalWorkingHours()).isEqualTo(80.0);
    }

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
    void monthlyCostBillsOnlyDaysInsideResourceBasedPeriod() {
        // July 2026, all present, rate 88200. Resource-based period starts 16-Jul-2026 → only
        // 16..31 Jul (16 days) are billable: cost = round2(88200 × 16/31) = 45522.58.
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
        when(resourceBasedPeriodService.resolve("P1")).thenReturn(Optional.of(
                new ResourceBasedPeriodService.ResourceBasedPeriod(
                        LocalDate.of(2026, 7, 16), LocalDate.of(2032, 1, 1))));

        List<Attendance> rows = new java.util.ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 7, 1); !date.isAfter(LocalDate.of(2026, 7, 31));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }
            rows.add(new Attendance(resource, "P1", null, "M1", null, date, AttendanceStatus.P));
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(rows);

        MonthlyResourceCost cost = service.employeeMonthlyCost("E1", 2026, 7);

        // Attendance counts are unchanged — only billing is gated.
        assertThat(cost.presentDays()).isEqualTo(23.0);
        assertThat(cost.activeCalendarDays()).isEqualTo(16);
        assertThat(cost.cost()).isEqualTo(45522.58);
    }

    @Test
    void monthlyCostIsZeroWhenCycleFullyOutsideResourceBasedPeriod() {
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
        // Resource-based period is entirely after July 2026 → nothing billable.
        when(resourceBasedPeriodService.resolve("P1")).thenReturn(Optional.of(
                new ResourceBasedPeriodService.ResourceBasedPeriod(
                        LocalDate.of(2030, 1, 1), LocalDate.of(2032, 1, 1))));

        List<Attendance> rows = new java.util.ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 7, 1); !date.isAfter(LocalDate.of(2026, 7, 31));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                continue;
            }
            rows.add(new Attendance(resource, "P1", null, "M1", null, date, AttendanceStatus.P));
        }
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(eq(1L), any(), any()))
                .thenReturn(rows);

        MonthlyResourceCost cost = service.employeeMonthlyCost("E1", 2026, 7);

        assertThat(cost.activeCalendarDays()).isZero();
        assertThat(cost.cost()).isZero();
        assertThat(cost.billableDays()).isZero();
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
