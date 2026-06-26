package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.client.EmployeeDirectoryClient;
import com.example.leavemanagement.client.EmployeeInfo;
import com.example.leavemanagement.dto.EmployeeAttendance;
import com.example.leavemanagement.dto.LeaveApplyRequest;
import com.example.leavemanagement.dto.LeaveResponse;
import com.example.leavemanagement.dto.MonthlyAttendanceSummary;
import com.example.leavemanagement.entity.LeaveStatus;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class AttendanceLeaveServiceTest {

    @Mock
    private AttendanceExcelParser parser;

    @Mock
    private EmployeeDirectoryClient directory;

    @Mock
    private com.example.leavemanagement.repository.PublicHolidayRepository holidayRepository;

    @Mock
    private LeaveService leaveService;

    @InjectMocks
    private AttendanceLeaveService service;

    private final MultipartFile anyFile = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});

    @Test
    void groupsConsecutiveAbsencesBridgingWeekendsAndSkippingHolidays() {
        // June 2026 starts on a Monday. Weekends: 6,7,13,14,20,21,27,28.
        // Absent: Fri 5, weekend 6-7, Mon 8 -> one leave 5..8 (weekend bridged).
        //         Thu 11, Fri 12 (a public holiday), weekend 13-14 -> one leave 11..11.
        //         (20,21 are an absent weekend with no working day -> no leave.)
        when(parser.parse(any())).thenReturn(List.of(new EmployeeAttendance(
                "E1", "Asha", "Dev", Set.of(5, 6, 7, 8, 11, 12, 13, 14, 20, 21), Map.of())));
        when(directory.findByAttendanceId("E1"))
                .thenReturn(Optional.of(new EmployeeInfo("E1", "Asha Kumar", "Dev", null)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of(new PublicHoliday(LocalDate.of(2026, 6, 12), "Test Holiday")));
        when(leaveService.applyForLeave(any())).thenReturn(dummyResponse());

        service.importLeaves(2026, 6, anyFile);

        ArgumentCaptor<LeaveApplyRequest> captor = ArgumentCaptor.forClass(LeaveApplyRequest.class);
        verify(leaveService, times(2)).applyForLeave(captor.capture());
        List<LeaveApplyRequest> requests = captor.getAllValues();

        assertThat(requests).allSatisfy(r -> assertThat(r.employeeName()).isEqualTo("Asha Kumar"));
        assertThat(requests)
                .extracting(LeaveApplyRequest::startDate, LeaveApplyRequest::endDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 8)),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 11)));
    }

    @Test
    void noLeavesWhenAllAbsencesFallOnWeekends() {
        when(parser.parse(any()))
                .thenReturn(List.of(new EmployeeAttendance("E1", "Bo", "QA", Set.of(6, 7, 13, 14), Map.of())));
        when(directory.findByAttendanceId("E1"))
                .thenReturn(Optional.of(new EmployeeInfo("E1", "Bo Lee", "QA", null)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of());

        List<LeaveResponse> created = service.importLeaves(2026, 6, anyFile);

        assertThat(created).isEmpty();
        verify(leaveService, times(0)).applyForLeave(any());
    }

    @Test
    void summaryCountsWeekendsHolidaysLeavesAndShortDays() {
        // June 2026: Saturdays 6,13,20,27; Sundays 7,14,21,28 -> 4 + 4.
        // Absent Mon 8 (working) -> 1 leave day. Worked minutes: day2=7.5h and day4=5h
        // day2=7.5h and day4=5h are short (>4,<8); day5=3h is a half day (<=4h);
        // day1=8h and day3=9h are full days.
        EmployeeAttendance e = new EmployeeAttendance(
                "E1", "Asha", "Dev", Set.of(8), Map.of(1, 480, 2, 450, 3, 540, 4, 300, 5, 180));
        when(parser.parse(any())).thenReturn(List.of(e));
        when(directory.findByAttendanceId("E1"))
                .thenReturn(Optional.of(new EmployeeInfo("E1", "Asha Kumar", "Dev", null)));
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(any(), any()))
                .thenReturn(List.of(new PublicHoliday(LocalDate.of(2026, 6, 12), "Test Holiday")));

        MonthlyAttendanceSummary summary = service.summarize(2026, 6, anyFile);

        assertThat(summary.saturdays()).isEqualTo(4);
        assertThat(summary.sundays()).isEqualTo(4);
        assertThat(summary.totalWeekendDays()).isEqualTo(8);
        assertThat(summary.publicHolidayCount()).isEqualTo(1);
        assertThat(summary.employees()).hasSize(1);

        var emp = summary.employees().get(0);
        assertThat(emp.employeeName()).isEqualTo("Asha Kumar");
        assertThat(emp.leaveDays()).isEqualTo(1);
        assertThat(emp.shortHourDays()).isEqualTo(2);
        // >4 and <8 hours -> shortHours showing hours NOT worked (8h - worked)
        assertThat(emp.shortHours())
                .containsEntry("02-06-2026", "0.5 hrs") // worked 7.5h -> 0.5h short
                .containsEntry("04-06-2026", "3 hrs") // worked 5h -> 3h short
                .hasSize(2);
        // <=4 hours -> halfDays (date only)
        assertThat(emp.halfDays()).containsExactly("05-06-2026");
    }

    @Test
    void rejectsInvalidMonth() {
        assertThatThrownBy(() -> service.importLeaves(2026, 13, anyFile))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("month");
    }

    private LeaveResponse dummyResponse() {
        return new LeaveResponse(
                1L,
                "Asha Kumar",
                LocalDate.of(2026, 6, 5),
                LocalDate.of(2026, 6, 8),
                "reason",
                LeaveStatus.PENDING,
                0,
                0,
                0,
                0,
                List.of(),
                null);
    }
}
