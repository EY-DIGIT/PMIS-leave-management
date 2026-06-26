package com.example.leavemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.leavemanagement.dto.CalendarSummary;
import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.dto.HolidayUploadRequest;
import com.example.leavemanagement.dto.YearCalendarSummary;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HolidayServiceTest {

    @Mock
    private PublicHolidayRepository repository;

    @InjectMocks
    private HolidayService service;

    @Test
    void countsWeekendsForNonLeapYear() {
        // 2026 has 365 days starting on a Thursday -> 52 Saturdays, 52 Sundays.
        when(repository.findByYearOrderByHolidayDateAsc(2026)).thenReturn(List.of());

        YearCalendarSummary summary = service.getYearSummary(2026);

        assertThat(summary.totalDaysInYear()).isEqualTo(365);
        assertThat(summary.saturdays()).isEqualTo(52);
        assertThat(summary.sundays()).isEqualTo(52);
        assertThat(summary.totalWeekendDays()).isEqualTo(104);
        assertThat(summary.publicHolidayCount()).isZero();
    }

    @Test
    void countsWeekendsForYearWithExtraSunday() {
        // 2023 has 365 days starting on a Sunday -> 53 Sundays, 52 Saturdays.
        when(repository.findByYearOrderByHolidayDateAsc(2023)).thenReturn(List.of());

        YearCalendarSummary summary = service.getYearSummary(2023);

        assertThat(summary.saturdays()).isEqualTo(52);
        assertThat(summary.sundays()).isEqualTo(53);
    }

    @Test
    void countsWeekendsForLeapYear() {
        // 2024 is a leap year with 366 days.
        when(repository.findByYearOrderByHolidayDateAsc(2024)).thenReturn(List.of());

        YearCalendarSummary summary = service.getYearSummary(2024);

        assertThat(summary.totalDaysInYear()).isEqualTo(366);
        assertThat(summary.saturdays()).isEqualTo(52);
        assertThat(summary.sundays()).isEqualTo(52);
    }

    @Test
    void allowsMultipleHolidaysOnTheSameDate() {
        LocalDate date = LocalDate.of(2026, 1, 26);
        HolidayUploadRequest request = new HolidayUploadRequest(
                2026,
                List.of(new HolidayItem(date, "Republic Day"), new HolidayItem(date, "Town Festival")));
        // Neither (date, name) pair exists yet, so both are inserted.
        when(repository.findByHolidayDateAndName(any(LocalDate.class), any(String.class)))
                .thenReturn(Optional.empty());
        when(repository.findByYearOrderByHolidayDateAsc(2026))
                .thenReturn(List.of(
                        new PublicHoliday(date, "Republic Day"), new PublicHoliday(date, "Town Festival")));

        List<HolidayItem> result = service.uploadHolidays(request);

        verify(repository, times(2)).save(any(PublicHoliday.class));
        assertThat(result).extracting("name").containsExactly("Republic Day", "Town Festival");
    }

    @Test
    void skipsHolidayThatAlreadyExistsForThatDateAndName() {
        LocalDate date = LocalDate.of(2026, 1, 26);
        HolidayUploadRequest request =
                new HolidayUploadRequest(2026, List.of(new HolidayItem(date, "Republic Day")));
        when(repository.findByHolidayDateAndName(date, "Republic Day"))
                .thenReturn(Optional.of(new PublicHoliday(date, "Republic Day")));
        when(repository.findByYearOrderByHolidayDateAsc(2026))
                .thenReturn(List.of(new PublicHoliday(date, "Republic Day")));

        service.uploadHolidays(request);

        verify(repository, times(0)).save(any(PublicHoliday.class));
    }

    @Test
    void calendarForSingleMonthCountsThatMonthsWeekendsAndHolidays() {
        // June 2026: 30 days, Saturdays 6,13,20,27 (4), Sundays 7,14,21,28 (4).
        when(repository.findByYearOrderByHolidayDateAsc(2026))
                .thenReturn(List.of(
                        new PublicHoliday(LocalDate.of(2026, 1, 26), "Republic Day"),
                        new PublicHoliday(LocalDate.of(2026, 6, 12), "June Holiday")));

        CalendarSummary summary = service.getCalendar(2026, "6");

        assertThat(summary.month()).isEqualTo(6);
        assertThat(summary.totalDays()).isEqualTo(30);
        assertThat(summary.saturdays()).isEqualTo(4);
        assertThat(summary.sundays()).isEqualTo(4);
        assertThat(summary.weekends().get(0).saturdayDates())
                .containsExactly(
                        LocalDate.of(2026, 6, 6),
                        LocalDate.of(2026, 6, 13),
                        LocalDate.of(2026, 6, 20),
                        LocalDate.of(2026, 6, 27));
        assertThat(summary.publicHolidayCount()).isEqualTo(1);
        assertThat(summary.publicHolidays()).extracting("name").containsExactly("June Holiday");
        assertThat(summary.months()).isEmpty();
    }

    @Test
    void calendarForAllMonthsReturnsYearTotalsAndTwelveMonths() {
        when(repository.findByYearOrderByHolidayDateAsc(2026)).thenReturn(List.of());

        CalendarSummary summary = service.getCalendar(2026, "all");

        assertThat(summary.month()).isNull();
        assertThat(summary.totalDays()).isEqualTo(365);
        assertThat(summary.saturdays()).isEqualTo(52);
        assertThat(summary.sundays()).isEqualTo(52);
        assertThat(summary.weekends().get(0).saturdayDates())
                .hasSize(52)
                .allMatch(d -> d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY);
        assertThat(summary.weekends().get(0).sundayDates()).hasSize(52);
        assertThat(summary.months()).hasSize(12);
        assertThat(summary.months().get(5).month()).isEqualTo(6);
        assertThat(summary.months().get(5).monthName()).isEqualTo("June");
    }

    @Test
    void calendarRejectsInvalidMonth() {
        assertThatThrownBy(() -> service.getCalendar(2026, "13"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("month");
        assertThatThrownBy(() -> service.getCalendar(2026, "abc"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("month");
    }

    @Test
    void listHolidaysFiltersByMonth() {
        when(repository.findByYearOrderByHolidayDateAsc(2026))
                .thenReturn(List.of(
                        new PublicHoliday(LocalDate.of(2026, 1, 26), "Republic Day"),
                        new PublicHoliday(LocalDate.of(2026, 6, 12), "June Holiday")));

        assertThat(service.listHolidays(2026, "6")).extracting("name").containsExactly("June Holiday");
        assertThat(service.listHolidays(2026, "all")).hasSize(2);
    }

    @Test
    void rejectsHolidayOutsideRequestedYear() {
        HolidayUploadRequest request = new HolidayUploadRequest(
                2026, List.of(new HolidayItem(LocalDate.of(2025, 12, 31), "New Year's Eve")));

        assertThatThrownBy(() -> service.uploadHolidays(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not within year 2026");
    }
}
