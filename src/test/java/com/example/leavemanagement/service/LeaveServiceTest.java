package com.example.leavemanagement.service;

/*
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.dto.LeaveApplyRequest;
import com.example.leavemanagement.dto.LeaveResponse;
import com.example.leavemanagement.entity.LeaveRequest;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock
    private LeaveRequestRepository leaveRepository;

    @Mock
    private PublicHolidayRepository holidayRepository;

    @InjectMocks
    private LeaveService service;

    @Test
    void excludesWeekendsAndHolidaysFromWorkingDays() {
        // Thu 22 Jan -> Wed 28 Jan 2026: Sat 24 + Sun 25 are weekend,
        // Mon 26 (Republic Day) is a holiday -> 4 working days (Thu, Fri, Tue, Wed).
        LocalDate start = LocalDate.of(2026, 1, 22);
        LocalDate end = LocalDate.of(2026, 1, 28);
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end))
                .thenReturn(List.of(new PublicHoliday(LocalDate.of(2026, 1, 26), "Republic Day")));
        when(leaveRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveResponse res = service.applyForLeave(new LeaveApplyRequest("Asha", null, start, end, "Vacation"));

        assertThat(res.totalCalendarDays()).isEqualTo(7);
        assertThat(res.weekendDays()).isEqualTo(2);
        assertThat(res.holidayDays()).isEqualTo(1);
        assertThat(res.workingDays()).isEqualTo(4);
        assertThat(res.holidaysInRange()).extracting("name").containsExactly("Republic Day");
    }

    @Test
    void holidayOnWeekendIsNotDoubleCounted() {
        // Same range, but with an extra holiday on Sat 24 Jan. It must count as a
        // weekend day only, leaving working days unchanged at 4.
        LocalDate start = LocalDate.of(2026, 1, 22);
        LocalDate end = LocalDate.of(2026, 1, 28);
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end))
                .thenReturn(List.of(
                        new PublicHoliday(LocalDate.of(2026, 1, 24), "Special Saturday Holiday"),
                        new PublicHoliday(LocalDate.of(2026, 1, 26), "Republic Day")));
        when(leaveRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveResponse res = service.applyForLeave(new LeaveApplyRequest("Asha", null, start, end, null));

        assertThat(res.weekendDays()).isEqualTo(2);
        assertThat(res.holidayDays()).isEqualTo(1);
        assertThat(res.workingDays()).isEqualTo(4);
        assertThat(res.totalCalendarDays())
                .isEqualTo(res.workingDays() + res.weekendDays() + res.holidayDays());
    }

    @Test
    void singleWorkingDayLeave() {
        LocalDate day = LocalDate.of(2026, 1, 22); // Thursday
        when(holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(day, day))
                .thenReturn(List.of());
        when(leaveRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveResponse res = service.applyForLeave(new LeaveApplyRequest("Bo", null, day, day, null));

        assertThat(res.totalCalendarDays()).isEqualTo(1);
        assertThat(res.workingDays()).isEqualTo(1);
    }

    @Test
    void rejectsEndBeforeStart() {
        LeaveApplyRequest bad =
                new LeaveApplyRequest("Bo", null, LocalDate.of(2026, 1, 28), LocalDate.of(2026, 1, 22), null);

        assertThatThrownBy(() -> service.applyForLeave(bad))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("endDate must not be before startDate");
    }
}
*/
