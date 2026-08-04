package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.AdditionalAnswers.returnsFirstArg;

import com.example.leavemanagement.client.ActivityDetailsClient;
import com.example.leavemanagement.client.LeavePolicyClient;
import com.example.leavemanagement.dto.ActivityDetailsResponse;
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

    @Mock
    private ActivityDetailsClient activityDetailsClient;

    // Real engine, same as AttendanceQueryServiceTest.
    private final QuarterLeavePolicy policy = new QuarterLeavePolicy();

    private LeaveReportService service;

    // Activity window Apr 1 – Jun 30 2026 (3 months → leave quota 6, matching the old Q2 math).
    private static final String ACTIVITY = "ACT-1";
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 6, 30);

    @BeforeEach
    void setUp() {
        QuarterLeaveResolver quarterLeaveResolver = new QuarterLeaveResolver(
                attendanceRepository, publicHolidayRepository, leavePolicyClient, policy,
                projectResourceRepository);
        service = new LeaveReportService(
                masterResourceRepository, projectResourceRepository,
                attendanceRepository, leaveRelaxationRepository, quarterLeaveResolver, periodValidator,
                yearMappingRepository, activityDetailsClient);
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

    /** 8 weekdays in the activity window (Apr-Jun 2026) -> 6 paid (permissible), 2 unpaid. */
    private List<LocalDate> eightWeekdaysInWindow() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate day = WINDOW_START;
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
        when(activityDetailsClient.getActivityDetails(ACTIVITY)).thenReturn(Optional.of(
                new ActivityDetailsResponse("Activity", WINDOW_START, WINDOW_END, List.of())));
        List<Attendance> rows = absentDates.stream()
                .map(date -> new Attendance(resource, projectId, null, "M1", ACTIVITY, date, AttendanceStatus.A))
                .toList();
        when(attendanceRepository.findByResourceIdAndAttendanceDateBetween(id, WINDOW_START, WINDOW_END))
                .thenReturn(rows);
        return resource;
    }

    // Unpaid leave dates in the window: Apr 9 and Apr 10 (6 permissible leave covers Apr 1-3,6-8).
    private static final LocalDate APR9  = LocalDate.of(2026, 4, 9);
    private static final LocalDate APR10 = LocalDate.of(2026, 4, 10);

    @Test
    void applyQuarterlyRelaxationReducesUnpaidLeaveAndKeepsPaidLeaveUnchanged() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInWindow());
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndActivityId("E1", "P1", ACTIVITY))
                .thenReturn(Optional.empty());
        when(leaveRelaxationRepository.save(any())).thenAnswer(returnsFirstArg());

        QuarterlyRelaxationRequest request = new QuarterlyRelaxationRequest(
                "E1", "P1", ACTIVITY, List.of(APR9), "Approved by UIDAI on medical grounds.");
        EmployeeLeaveDetail result = service.applyQuarterlyRelaxation(request, null);

        assertThat(result.paidLeave()).isEqualTo(6);
        assertThat(result.relaxationLeave()).isEqualTo(1);
        assertThat(result.unpaidLeave()).isEqualTo(1); // 2 original - 1 relaxation

        ArgumentCaptor<LeaveRelaxation> captor = ArgumentCaptor.forClass(LeaveRelaxation.class);
        verify(leaveRelaxationRepository).save(captor.capture());
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
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInWindow());
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndActivityId("E1", "P1", ACTIVITY))
                .thenReturn(Optional.empty());
        when(leaveRelaxationRepository.save(any())).thenAnswer(returnsFirstArg());

        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest("E1", "P1", ACTIVITY, List.of(APR9, APR10), null);
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
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInWindow());

        // Apr 8 is a paid leave date, not an unpaid one — should be rejected before touching the DB.
        QuarterlyRelaxationRequest request = new QuarterlyRelaxationRequest(
                "E1", "P1", ACTIVITY, List.of(LocalDate.of(2026, 4, 8)), null);

        assertThatThrownBy(() -> service.applyQuarterlyRelaxation(request, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void applyQuarterlyRelaxationAccumulatesAcrossTwoCalls() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInWindow());

        // First call already saved: Apr 9 approved.
        LeaveRelaxation firstRecord = new LeaveRelaxation(
                masterResourceRepository.findByResId("E1").orElseThrow(), "P1", ACTIVITY);
        firstRecord.setRelaxationDays(1);
        firstRecord.setRelaxationDates(List.of(APR9));
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndActivityId("E1", "P1", ACTIVITY))
                .thenReturn(Optional.of(firstRecord));
        when(leaveRelaxationRepository.save(any())).thenAnswer(returnsFirstArg());

        // Second call: approve Apr 10 — should accumulate to 2 total.
        QuarterlyRelaxationRequest request =
                new QuarterlyRelaxationRequest("E1", "P1", ACTIVITY, List.of(APR10), null);
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
                new QuarterlyRelaxationRequest("E1", "P1", ACTIVITY, List.of(), null);

        assertThatThrownBy(() -> service.applyQuarterlyRelaxation(request, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void employeeDetailAppliesAnAlreadyRecordedRelaxation() {
        MasterResource resource = stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInWindow());
        LeaveRelaxation existing = new LeaveRelaxation(resource, "P1", ACTIVITY);
        existing.setRelaxationDays(2);
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndActivityId("E1", "P1", ACTIVITY))
                .thenReturn(Optional.of(existing));

        EmployeeLeaveDetail result = service.employeeDetail("E1", ACTIVITY, null);

        assertThat(result.paidLeave()).isEqualTo(6);
        assertThat(result.relaxationLeave()).isEqualTo(2);
        assertThat(result.unpaidLeave()).isEqualTo(0); // 2 original - 2 relaxation
    }

    @Test
    void employeeDetailWithoutRecordedRelaxationLeavesUnpaidLeaveUntouched() {
        stubResourceWithAbsences("E1", 1L, "P1", eightWeekdaysInWindow());
        when(leaveRelaxationRepository.findByResource_ResIdAndProjectIdAndActivityId("E1", "P1", ACTIVITY))
                .thenReturn(Optional.empty());

        EmployeeLeaveDetail result = service.employeeDetail("E1", ACTIVITY, null);

        assertThat(result.relaxationLeave()).isEqualTo(0);
        assertThat(result.unpaidLeave()).isEqualTo(2);
    }
}
