package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.client.EmployeeDirectoryClient;
import com.example.leavemanagement.client.EmployeeInfo;
import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.AttendanceSummaryReport;
import com.example.leavemanagement.dto.EmployeeAttendance;
import com.example.leavemanagement.dto.LeavePolicyResponse;
import com.example.leavemanagement.dto.MonthlyAttendanceStored;
import com.example.leavemanagement.dto.MonthlyAttendanceSummary;
import com.example.leavemanagement.dto.QuarterLeaveReport;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ResourceMonthlyAttendance;
import com.example.leavemanagement.exception.AttendanceValidationException;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import com.example.leavemanagement.repository.ResourceMonthlyAttendanceRepository;
import com.example.leavemanagement.repository.ResourceProjectMappingRepository;
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
    private ResourceMonthlyAttendanceRepository attendanceRepository;

    @Mock
    private PublicHolidayRepository holidayRepository;

    @Mock
    private EmployeeDirectoryClient directory;

    @Mock
    private AttendanceLeaveService attendanceLeaveService;

    @Mock
    private ResourceProjectMappingRepository resourceProjectMappingRepository;

    @Mock
    private ProjectConfigRepository projectConfigRepository;

    @Mock
    private MasterResourceRepository masterResourceRepository;

    @Mock
    private LeavePolicyClient leavePolicyClient;

    // Real engine — the quarter path is verified end-to-end.
    private final QuarterLeavePolicy policy = new QuarterLeavePolicy();

    private AttendanceQueryService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceQueryService(
                parser, attendanceRepository, holidayRepository, directory, policy,
                attendanceLeaveService, resourceProjectMappingRepository, projectConfigRepository,
                masterResourceRepository, leavePolicyClient);
    }

    @Test
    void quarterlySettlementMapsCalendarQuarterAndReproducesIllustration1() {
        // Q2 2024 -> Apr/May/Jun. Apr 1,2 + May 1,2,3 + Jun 14 (paid) + Jun 17 (unpaid) -> 1 unpaid.
        ResourceMonthlyAttendance apr = row("E1", "Resource A", 2024, 4, Set.of(1, 2));
        ResourceMonthlyAttendance may = row("E1", "Resource A", 2024, 5, Set.of(1, 2, 3));
        ResourceMonthlyAttendance jun = row("E1", "Resource A", 2024, 6, Set.of(14, 17));
        when(attendanceRepository.findByYearAndMonthIn(2024, List.of(4, 5, 6)))
                .thenReturn(List.of(apr, may, jun));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of());
        when(directory.findByAttendanceId("E1"))
                .thenReturn(Optional.of(new EmployeeInfo("E1", "Resource A", "Dev", null)));

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
    void singleMonthSummaryIsFlatAndDelegatesToBuildSummary() {
        ResourceMonthlyAttendance row = row("E1", "Asha", 2024, 6, Set.of(3));
        row.setWorkedMinutesByDay(Map.of(1, 480, 2, 420));
        when(attendanceRepository.findByYearAndMonth(2024, 6)).thenReturn(List.of(row));
        MonthlyAttendanceSummary sentinel =
                new MonthlyAttendanceSummary(2024, 6, 30, 4, 4, 8, List.of(), 0, List.of(), 1, List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmployeeAttendance>> captor = ArgumentCaptor.forClass(List.class);
        when(attendanceLeaveService.buildSummary(eq(2024), eq(6), captor.capture())).thenReturn(sentinel);

        // dispatcher returns the flat summary for a numeric month
        Object result = service.summary(2024, "6", null);

        assertThat(result).isSameAs(sentinel);
        List<EmployeeAttendance> passed = captor.getValue();
        assertThat(passed).hasSize(1);
        assertThat(passed.get(0).attendanceId()).isEqualTo("E1");
        assertThat(passed.get(0).absentDays()).containsExactly(3);
        assertThat(passed.get(0).workedMinutesByDay()).containsEntry(1, 480).containsEntry(2, 420);
    }

    @Test
    void allMonthsSummaryReturnsOneSummaryPerStoredMonthSorted() {
        when(attendanceRepository.findByYear(2024))
                .thenReturn(List.of(row("E1", "Asha", 2024, 6, Set.of(3)), row("E1", "Asha", 2024, 4, Set.of(2))));
        MonthlyAttendanceSummary aprSummary =
                new MonthlyAttendanceSummary(2024, 4, 30, 4, 4, 8, List.of(), 0, List.of(), 1, List.of());
        MonthlyAttendanceSummary junSummary =
                new MonthlyAttendanceSummary(2024, 6, 30, 4, 4, 8, List.of(), 0, List.of(), 1, List.of());
        when(attendanceLeaveService.buildSummary(eq(2024), eq(4), any())).thenReturn(aprSummary);
        when(attendanceLeaveService.buildSummary(eq(2024), eq(6), any())).thenReturn(junSummary);

        // dispatcher returns the envelope for "all"
        Object result = service.summary(2024, "all", null);

        assertThat(result).isInstanceOf(AttendanceSummaryReport.class);
        AttendanceSummaryReport report = (AttendanceSummaryReport) result;
        assertThat(report.month()).isNull();
        assertThat(report.months()).containsExactly(aprSummary, junSummary); // sorted by month
    }

    @Test
    void summaryRejectsInvalidMonthParam() {
        assertThatThrownBy(() -> service.summary(2024, "13", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("month");
    }

    @Test
    void storeAndSummarizePersistsAndReturnsSummary() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(6, 7), Map.of(6, 500))));
        stubActiveResource("E1", "P1");
        when(leavePolicyClient.getLeavePolicy("P1"))
                .thenReturn(Optional.of(new LeavePolicyResponse("MONTHLY", 4d, 8d, true)));
        when(attendanceRepository.findByYearAndMonth(2026, 5)).thenReturn(List.of()); // no existing rows
        MonthlyAttendanceSummary sentinel =
                new MonthlyAttendanceSummary(2026, 5, 31, 5, 4, 9, List.of(), 0, List.of(), 1, List.of());
        when(attendanceLeaveService.buildSummary(eq(2026), eq(5), any())).thenReturn(sentinel);

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        MonthlyAttendanceSummary result = service.storeAndSummarize(2026, 5, "M1", "P1", null, null, file);

        assertThat(result).isSameAs(sentinel); // summary returned
        org.mockito.Mockito.verify(attendanceRepository).save(any(ResourceMonthlyAttendance.class)); // and persisted
    }

    @Test
    void storeMonthlyKeepsOnlyWeekdayAbsencesAndPersistsWorkedMinutes() {
        // June 2024: 1 Sat, 2 Sun (dropped), 6 Thu, 7 Fri (kept).
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(1, 2, 6, 7), Map.of(6, 500))));
        stubActiveResource("E1", "P1");
        LeavePolicyResponse leavePolicy = new LeavePolicyResponse("MONTHLY", 4d, 8d, true);
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.of(leavePolicy));
        when(attendanceRepository.findByYearAndMonth(2024, 6)).thenReturn(List.of()); // no existing rows

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        MonthlyAttendanceStored stored = service.storeMonthly(2024, 6, "M1", "P1", null, null, file);

        assertThat(stored.resourcesStored()).isEqualTo(1);
        assertThat(stored.leavePoliciesByProject()).containsEntry("P1", leavePolicy);
        ArgumentCaptor<ResourceMonthlyAttendance> captor = ArgumentCaptor.forClass(ResourceMonthlyAttendance.class);
        org.mockito.Mockito.verify(attendanceRepository).save(captor.capture());
        ResourceMonthlyAttendance saved = captor.getValue();
        assertThat(saved.getAbsentDays()).containsExactlyInAnyOrder(6, 7);
        assertThat(saved.getWorkedMinutesByDay()).containsEntry(6, 500);
    }

    @Test
    void storeMonthlyRejectsWholeUploadWhenResourceDoesNotExist() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(6, 7), Map.of())));
        when(masterResourceRepository.findByResIdAndActiveTrue("E1")).thenReturn(Optional.empty());
        when(masterResourceRepository.existsByResId("E1")).thenReturn(false);

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        assertThatThrownBy(() -> service.storeMonthly(2024, 6, "M1", "P1", null, null, file))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Resource E1 does not exist."));
        org.mockito.Mockito.verify(attendanceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void storeMonthlyRejectsWholeUploadWhenResourceIsInactive() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(6, 7), Map.of())));
        when(masterResourceRepository.findByResIdAndActiveTrue("E1")).thenReturn(Optional.empty());
        when(masterResourceRepository.existsByResId("E1")).thenReturn(true);

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        assertThatThrownBy(() -> service.storeMonthly(2024, 6, "M1", "P1", null, null, file))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Resource E1 is inactive."));
        org.mockito.Mockito.verify(attendanceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void storeMonthlyRejectsWholeUploadWhenLeavePolicyFetchFails() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(6, 7), Map.of())));
        stubActiveResource("E1", "P1");
        when(leavePolicyClient.getLeavePolicy("P1")).thenReturn(Optional.empty());

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        assertThatThrownBy(() -> service.storeMonthly(2024, 6, "M1", "P1", null, null, file))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Could not fetch leave policy for project P1."));
        org.mockito.Mockito.verify(attendanceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void storeMonthlyRejectsWholeUploadWhenResourceBelongsToDifferentProject() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(6, 7), Map.of())));
        stubActiveResource("E1", "P1");

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        assertThatThrownBy(() -> service.storeMonthly(2024, 6, "M1", "P2", null, null, file))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Resource E1 belongs to project P1, not P2."));
        org.mockito.Mockito.verify(attendanceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void monthlySummaryFiltersByProjectIdWhenGiven() {
        ResourceMonthlyAttendance row = row("E1", "Asha", 2024, 6, Set.of(3));
        when(attendanceRepository.findByYearAndMonthAndProjectId(2024, 6, "P1")).thenReturn(List.of(row));
        MonthlyAttendanceSummary sentinel =
                new MonthlyAttendanceSummary(2024, 6, 30, 4, 4, 8, List.of(), 0, List.of(), 1, List.of());
        when(attendanceLeaveService.buildSummary(eq(2024), eq(6), any())).thenReturn(sentinel);

        Object result = service.summary(2024, "6", "P1");

        assertThat(result).isSameAs(sentinel);
        org.mockito.Mockito.verify(attendanceRepository, org.mockito.Mockito.never()).findByYearAndMonth(2024, 6);
    }

    @Test
    void quarterlySettlementFiltersByProjectIdWhenGiven() {
        when(attendanceRepository.findByYearAndMonthInAndProjectId(2024, List.of(4, 5, 6), "P1"))
                .thenReturn(List.of());
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of());

        QuarterLeaveReport report = service.quarterlySettlement(2024, 2, "P1");

        assertThat(report.resourceCount()).isEqualTo(0);
        org.mockito.Mockito.verify(attendanceRepository, org.mockito.Mockito.never())
                .findByYearAndMonthIn(2024, List.of(4, 5, 6));
    }

    @Test
    void storeMonthlyAcceptsFullCalendarMonthRange() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(6, 7), Map.of())));
        stubActiveResource("E1", "P1");
        when(leavePolicyClient.getLeavePolicy("P1"))
                .thenReturn(Optional.of(new LeavePolicyResponse("MONTHLY", 4d, 8d, true)));
        when(attendanceRepository.findByYearAndMonth(2026, 6)).thenReturn(List.of());

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        MonthlyAttendanceStored stored = service.storeMonthly(
                2026, 6, "M1", "P1", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), file);

        assertThat(stored.resourcesStored()).isEqualTo(1);
    }

    @Test
    void storeMonthlyAcceptsRollingMonthRange() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Asha", "Dev", Set.of(6, 7), Map.of())));
        stubActiveResource("E1", "P1");
        when(leavePolicyClient.getLeavePolicy("P1"))
                .thenReturn(Optional.of(new LeavePolicyResponse("MONTHLY", 4d, 8d, true)));
        when(attendanceRepository.findByYearAndMonth(2026, 6)).thenReturn(List.of());

        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        // 4 June -> 4 July: same date one month later.
        MonthlyAttendanceStored stored = service.storeMonthly(
                2026, 6, "M1", "P1", LocalDate.of(2026, 6, 4), LocalDate.of(2026, 7, 4), file);

        assertThat(stored.resourcesStored()).isEqualTo(1);
    }

    @Test
    void storeMonthlyRejectsIncompleteDateRange() {
        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        // 4 June -> 20 June: neither a full calendar month nor a rolling month.
        assertThatThrownBy(() -> service.storeMonthly(
                        2026, 6, "M1", "P1", LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 20), file))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .hasSize(1)
                        .allSatisfy(msg -> assertThat(msg).contains("does not span a complete month")));
        org.mockito.Mockito.verify(attendanceRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(parser);
    }

    @Test
    void storeMonthlyRejectsWhenOnlyOneOfStartOrEndDateGiven() {
        MultipartFile file = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});
        assertThatThrownBy(() -> service.storeMonthly(2026, 6, "M1", "P1", LocalDate.of(2026, 6, 1), null, file))
                .isInstanceOf(AttendanceValidationException.class)
                .satisfies(ex -> assertThat(((AttendanceValidationException) ex).getErrors())
                        .containsExactly("Both startDate and endDate must be provided together."));
    }

    private void stubActiveResource(String attendanceId, String projectId) {
        MasterResource resource = new MasterResource(attendanceId);
        resource.setActive(true);
        resource.setProjectId(projectId);
        when(masterResourceRepository.findByResIdAndActiveTrue(attendanceId)).thenReturn(Optional.of(resource));
    }

    private ResourceMonthlyAttendance row(String id, String name, int year, int month, Set<Integer> absentDays) {
        ResourceMonthlyAttendance row = new ResourceMonthlyAttendance(id, name, "Dev", year, month);
        row.setAbsentDays(new java.util.HashSet<>(absentDays));
        return row;
    }
}
