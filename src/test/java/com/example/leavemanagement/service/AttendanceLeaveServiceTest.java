package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.client.EmployeeDirectoryClient;
import com.example.leavemanagement.client.EmployeeInfo;
import com.example.leavemanagement.dto.EmployeeAttendanceByDate;
import com.example.leavemanagement.dto.MonthlyAttendanceSummary;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.ProjectConfigRepository;
import com.example.leavemanagement.repository.ResourceProjectMappingRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private ResourceProjectMappingRepository resourceProjectMappingRepository;

    @Mock
    private ProjectConfigRepository projectConfigRepository;

    @InjectMocks
    private AttendanceLeaveService service;

    private final MultipartFile anyFile = new MockMultipartFile("file", "att.xlsx", null, new byte[] {1});

    @Test
    void summaryCountsWeekendsHolidaysLeavesAndShortDays() {
        // June 2026: Saturdays 6,13,20,27; Sundays 7,14,21,28 -> 4 + 4.
        // Absent Mon 8 (working) -> 1 leave day. Worked minutes: day2=7.5h and day4=5h
        // day2=7.5h and day4=5h are short (>4,<8); day5=3h is a half day (<=4h);
        // day1=8h and day3=9h are full days.
        EmployeeAttendanceByDate e = new EmployeeAttendanceByDate(
                "E1",
                "Asha",
                "Dev",
                Set.of(LocalDate.of(2026, 6, 8)),
                Map.of(
                        LocalDate.of(2026, 6, 1), 480,
                        LocalDate.of(2026, 6, 2), 450,
                        LocalDate.of(2026, 6, 3), 540,
                        LocalDate.of(2026, 6, 4), 300,
                        LocalDate.of(2026, 6, 5), 180));
        when(parser.parse(any(), any(), any())).thenReturn(List.of(e));
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
        assertThatThrownBy(() -> service.summarize(2026, 13, anyFile))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("month");
    }
}
