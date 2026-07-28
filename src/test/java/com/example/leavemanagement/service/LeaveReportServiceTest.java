package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.AdditionalAnswers.returnsFirstArg;

import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.EmployeeLeaveDetail;
import com.example.leavemanagement.dto.QuarterlyRelaxationRequest;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.AttendanceStatus;
import com.example.leavemanagement.entity.LeaveRelaxation;
import com.example.leavemanagement.entity.MasterResource;
import com.example.leavemanagement.entity.ProjectResource;
import com.example.leavemanagement.repository.AttendanceRepository;
import com.example.leavemanagement.repository.LeaveRelaxationRepository;
import com.example.leavemanagement.repository.MasterResourceRepository;
import com.example.leavemanagement.repository.ProjectResourceRepository;
import com.example.leavemanagement.repository.ProjectYearMappingRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeaveReportServiceTest {

    @Mock
    private AttendanceQueryService attendanceQueryService;

    @Mock
    private MasterResourceRepository masterResourceRepository;

    @Mock
    private ProjectResourceRepository projectResourceRepository;

    @Mock
    private AttendanceRepository attendanceRepository;

    @Mock
    private PublicHolidayRepository publicHolidayRepository;

    @Mock
    private LeaveRelaxationRepository leaveRelaxationRepository;

    @Mock
    private LeavePolicyClient leavePolicyClient;

    @Mock
    private AttendancePeriodValidator periodValidator;

    @Mock
    private ProjectYearMappingRepository yearMappingRepository;

    // Real engine, same as AttendanceQueryServiceTest.
    private final QuarterLeavePolicy policy = new QuarterLeavePolicy();

    private LeaveReportService service;

    @BeforeEach
    void setUp() {
        QuarterLeaveResolver quarterLeaveResolver = new QuarterLeaveResolver(
                attendanceRepository, publicHolidayRepository, leavePolicyClient, policy);
        service = new LeaveReportService(
                attendanceQueryService, masterResourceRepository, projectResourceRepository,
                attendanceRepository, leaveRelaxationRepository, quarterLeaveResolver, periodValidator,
                yearMappingRepository);
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

    /** 8 weekdays in Q2 2026 (Apr-Jun) -> 6 paid (permissible), 2 unpaid. */
    private List<LocalDate> eightWeekdaysInQ2() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 4, 1);
        while (dates.size() < 8) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                dates.add(day);
            }
            day = day.plusDays(1);
        }
        return dates;
    }

    private MasterResource stubResourceWithAbsences(String resId, long id, String projectId, List<LocalDate> absentDates) {
        MasterResource resource = new MasterResource(resId);
        resource.setName("Asha");
        setId(resource, id);
        when(masterResourceRepository.findByResId(resId)).thenReturn(Optional.of(resource));
        ProjectResource assignment = new ProjectResource(resource, projectId, "Dev", LocalDate.of(2020, 1, 1));
        when(projectResourceRepository.findByResourceIdAndActiveTrue(id)).thenReturn(Optional.of(assignment));
        when(publicHolidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of());
        List<Attendance> rows = absentDates.stream()
                .map(date -> new Attendance(resource, projectId, null, "M1", null,date, AttendanceStatus.A))
                .toList();
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(
                        id, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(rows);
        return resource;
    }

    // Unpaid leave dates in Q2 2026: Apr 9 and Apr 10 (6 permissible leave covers Apr 1-3,6-8).
    private static final LocalDate APR9  = LocalDate.of(2026, 4, 9);
    private static final LocalDate APR10 = LocalDate.of(2026, 4, 10);

    @Test
    void applyQuarterlyRelaxationReducesUnpaidLeaveAndKeepsPaidLeaveUnchanged() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInQ2());
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndYearAndQuarter("E1", "P1", 2026, 2))
                .thenReturn(Optional.empty());
        when(leaveRelaxationRepository.save(any())).thenAnswer(org.mockito.AdditionalAnswers.returnsFirstArg());

        // Select Apr 9 (first unpaid date) for relaxation.
        QuarterlyRelaxationRequest request = new QuarterlyRelaxationRequest(
                "E1", "P1", 2026, 2, List.of(APR9), "Approved by UIDAI on medical grounds.");
        EmployeeLeaveDetail result = service.applyQuarterlyRelaxation(request, null);

        assertThat(result.paidLeave()).isEqualTo(6);
        assertThat(result.relaxationLeave()).isEqualTo(1);
        assertThat(result.unpaidLeave()).isEqualTo(1); // 2 original - 1 relaxation

        ArgumentCaptor<LeaveRelaxation> captor = ArgumentCaptor.forClass(LeaveRelaxation.class);
        org.mockito.Mockito.verify(leaveRelaxationRepository).save(captor.capture());
        LeaveRelaxation saved = captor.getValue();
        assertThat(saved.getOriginalPaidLeave()).isEqualTo(6);
        assertThat(saved.getOriginalUnpaidLeave()).isEqualTo(2);
        assertThat(saved.getRelaxationDays()).isEqualTo(1);
        assertThat(saved.getRelaxationDates()).containsExactly(APR9);
        assertThat(saved.getFinalPaidLeave()).isEqualTo(6);
        assertThat(saved.getFinalUnpaidLeave()).isEqualTo(1);
        assertThat(saved.getRemarks()).isEqualTo("Approved by UIDAI on medical grounds.");
        assertThat(saved.getApprovedAt()).isNotNull();
    }

    @Test
    void applyQuarterlyRelaxationApprovesAllUnpaidDates() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInQ2());
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndYearAndQuarter("E1", "P1", 2026, 2))
                .thenReturn(Optional.empty());
        when(leaveRelaxationRepository.save(any())).thenAnswer(returnsFirstArg());

        // Select both unpaid dates (Apr 9 and Apr 10).
        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest("E1", "P1", 2026, 2, List.of(APR9, APR10), null);
        EmployeeLeaveDetail result = service.applyQuarterlyRelaxation(request, null);

        assertThat(result.relaxationLeave()).isEqualTo(2);
        assertThat(result.unpaidLeave()).isEqualTo(0);

        ArgumentCaptor<LeaveRelaxation> captor = ArgumentCaptor.forClass(LeaveRelaxation.class);
        verify(leaveRelaxationRepository).save(captor.capture());
        assertThat(captor.getValue().getRelaxationDays()).isEqualTo(2);
        assertThat(captor.getValue().getRelaxationDates()).containsExactly(APR9, APR10);
    }

    @Test
    void applyQuarterlyRelaxationRejectsDateNotInUnpaidList() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInQ2());

        // Apr 8 is a paid leave date, not an unpaid one — should be rejected before touching the DB.
        QuarterlyRelaxationRequest request = new QuarterlyRelaxationRequest(
                "E1", "P1", 2026, 2, List.of(LocalDate.of(2026, 4, 8)), null);

        assertThatThrownBy(() -> service.applyQuarterlyRelaxation(request, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void applyQuarterlyRelaxationAccumulatesAcrossTwoCalls() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInQ2());

        // First call already saved: Apr 9 approved.
        LeaveRelaxation firstRecord = new LeaveRelaxation(
                masterResourceRepository.findByResId("E1").orElseThrow(), "P1", 2026, 2);
        firstRecord.setRelaxationDays(1);
        firstRecord.setRelaxationDates(List.of(APR9));
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndYearAndQuarter("E1", "P1", 2026, 2))
                .thenReturn(Optional.of(firstRecord));
        when(leaveRelaxationRepository.save(any())).thenAnswer(returnsFirstArg());

        // Second call: approve Apr 10 — should accumulate to 2 total.
        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest("E1", "P1", 2026, 2, List.of(APR10), null);
        EmployeeLeaveDetail result = service.applyQuarterlyRelaxation(request, null);

        assertThat(result.relaxationLeave()).isEqualTo(2); // 1 prev + 1 new
        assertThat(result.unpaidLeave()).isEqualTo(0);

        ArgumentCaptor<LeaveRelaxation> captor = ArgumentCaptor.forClass(LeaveRelaxation.class);
        verify(leaveRelaxationRepository).save(captor.capture());
        assertThat(captor.getValue().getRelaxationDays()).isEqualTo(2);
        assertThat(captor.getValue().getRelaxationDates()).containsExactlyInAnyOrder(APR9, APR10);
    }

    @Test
    void applyQuarterlyRelaxationRejectsEmptyDateList() {
        // No resource stubs needed — the guard throws before any repository is consulted.
        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest("E1", "P1", 2026, 2, List.of(), null);

        assertThatThrownBy(() -> service.applyQuarterlyRelaxation(request, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void employeeDetailAppliesAnAlreadyRecordedRelaxation() {
        MasterResource resource = stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInQ2());
        LeaveRelaxation existing = new LeaveRelaxation(resource, "P1", 2026, 2);
        existing.setRelaxationDays(2);
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndYearAndQuarter("E1", "P1", 2026, 2))
                .thenReturn(Optional.of(existing));

        EmployeeLeaveDetail result = service.employeeDetail("E1", 2026, 2, null);

        assertThat(result.paidLeave()).isEqualTo(6);
        assertThat(result.relaxationLeave()).isEqualTo(2);
        assertThat(result.unpaidLeave()).isEqualTo(0); // 2 original - 2 relaxation
    }

    @Test
    void employeeDetailWithoutRecordedRelaxationLeavesUnpaidLeaveUntouched() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInQ2());
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndYearAndQuarter("E1", "P1", 2026, 2))
                .thenReturn(Optional.empty());

        EmployeeLeaveDetail result = service.employeeDetail("E1", 2026, 2, null);

        assertThat(result.relaxationLeave()).isEqualTo(0);
        assertThat(result.unpaidLeave()).isEqualTo(2);
    }
}
