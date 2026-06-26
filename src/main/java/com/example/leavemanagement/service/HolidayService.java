package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CalendarSummary;
import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.dto.HolidayUploadRequest;
import com.example.leavemanagement.dto.MonthCalendarSummary;
import com.example.leavemanagement.dto.WeekendDates;
import com.example.leavemanagement.dto.YearCalendarSummary;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HolidayService {

    private final PublicHolidayRepository repository;

    public HolidayService(PublicHolidayRepository repository) {
        this.repository = repository;
    }

    /**
     * Upserts the given holidays for the requested year and returns the full,
     * up-to-date list of holidays for that year (sorted by date).
     *
     * <p>Every date must fall within {@code request.year()}. A single date may
     * carry more than one holiday, so entries are keyed by the
     * {@code (date, name)} pair: re-uploading an existing date+name is a no-op,
     * while a new name on an existing date adds another holiday to that day.
     * Exact duplicate {@code (date, name)} entries within one payload collapse
     * to a single row.
     */
    @Transactional
    public List<HolidayItem> uploadHolidays(HolidayUploadRequest request) {
        int year = request.year();

        for (HolidayItem item : request.holidays()) {
            if (item.date().getYear() != year) {
                throw new BadRequestException(
                        "Holiday date %s is not within year %d".formatted(item.date(), year));
            }
        }

        // Collapse exact-duplicate (date, name) pairs within the payload.
        List<HolidayItem> deduped = request.holidays().stream().distinct().toList();

        for (HolidayItem item : deduped) {
            if (repository
                    .findByHolidayDateAndName(item.date(), item.name())
                    .isEmpty()) {
                repository.save(new PublicHoliday(item.date(), item.name()));
            }
        }

        return listHolidays(year);
    }

    /**
     * Removes public holidays on the given date. When {@code name} is provided,
     * only that named holiday is removed; otherwise every holiday on the date is
     * removed. 404 if no matching holiday exists.
     */
    @Transactional
    public void deleteHoliday(LocalDate date, String name) {
        List<PublicHoliday> matches = (name == null || name.isBlank())
                ? repository.findByHolidayDateOrderByNameAsc(date)
                : repository.findByHolidayDateAndName(date, name).map(List::of).orElseGet(List::of);

        if (matches.isEmpty()) {
            String where = (name == null || name.isBlank()) ? date.toString() : "%s named '%s'".formatted(date, name);
            throw new NotFoundException("No public holiday on " + where);
        }
        repository.deleteAll(matches);
    }

    @Transactional(readOnly = true)
    public List<HolidayItem> listHolidays(int year) {
        return repository.findByYearOrderByHolidayDateAsc(year).stream()
                .map(h -> new HolidayItem(h.getHolidayDate(), h.getName()))
                .toList();
    }

    /**
     * Lists public holidays for a year, optionally filtered to a month. {@code month}
     * may be {@code 1-12} or {@code null}/blank/"all" for the whole year.
     */
    @Transactional(readOnly = true)
    public List<HolidayItem> listHolidays(int year, String month) {
        Integer monthValue = parseMonth(month);
        List<HolidayItem> all = listHolidays(year);
        if (monthValue == null) {
            return all;
        }
        return all.stream().filter(h -> h.date().getMonthValue() == monthValue).toList();
    }

    /**
     * Builds the calendar summary for a year: counts of Saturdays and Sundays
     * across 1-Jan to 31-Dec, plus the stored public holidays.
     */
    @Transactional(readOnly = true)
    public YearCalendarSummary getYearSummary(int year) {
        validateYear(year);

        Map<DayOfWeek, Long> weekdayCounts = countWeekdays(year);
        int saturdays = weekdayCounts.getOrDefault(DayOfWeek.SATURDAY, 0L).intValue();
        int sundays = weekdayCounts.getOrDefault(DayOfWeek.SUNDAY, 0L).intValue();

        List<HolidayItem> holidays = listHolidays(year);
        int totalDays = LocalDate.of(year, 1, 1).lengthOfYear();

        return new YearCalendarSummary(
                year, totalDays, saturdays, sundays, saturdays + sundays, holidays.size(), holidays);
    }

    /**
     * Calendar data for a year, scoped by {@code month}: a single month (1-12), or
     * the whole year with a per-month breakdown when {@code month} is null/blank/"all".
     */
    @Transactional(readOnly = true)
    public CalendarSummary getCalendar(int year, String month) {
        validateYear(year);
        Integer monthValue = parseMonth(month);

        if (monthValue == null) {
            YearCalendarSummary year_ = getYearSummary(year);
            List<MonthCalendarSummary> months = new ArrayList<>();
            for (int m = 1; m <= 12; m++) {
                months.add(monthSummary(year, m));
            }
            List<LocalDate> saturdayDates = months.stream()
                    .flatMap(ms -> ms.weekends().get(0).saturdayDates().stream())
                    .toList();
            List<LocalDate> sundayDates = months.stream()
                    .flatMap(ms -> ms.weekends().get(0).sundayDates().stream())
                    .toList();
            return new CalendarSummary(
                    year,
                    null,
                    year_.totalDaysInYear(),
                    year_.saturdays(),
                    year_.sundays(),
                    year_.totalWeekendDays(),
                    List.of(new WeekendDates(saturdayDates, sundayDates)),
                    year_.publicHolidayCount(),
                    year_.publicHolidays(),
                    months);
        }

        MonthCalendarSummary m = monthSummary(year, monthValue);
        return new CalendarSummary(
                year,
                monthValue,
                m.totalDaysInMonth(),
                m.saturdays(),
                m.sundays(),
                m.totalWeekendDays(),
                m.weekends(),
                m.publicHolidayCount(),
                m.publicHolidays(),
                List.of());
    }

    private MonthCalendarSummary monthSummary(int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        int lengthOfMonth = start.lengthOfMonth();

        List<LocalDate> saturdayDates = new ArrayList<>();
        List<LocalDate> sundayDates = new ArrayList<>();
        for (int day = 1; day <= lengthOfMonth; day++) {
            LocalDate date = LocalDate.of(year, month, day);
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) {
                saturdayDates.add(date);
            } else if (dow == DayOfWeek.SUNDAY) {
                sundayDates.add(date);
            }
        }

        List<HolidayItem> holidays = listHolidays(year).stream()
                .filter(h -> h.date().getMonthValue() == month)
                .toList();
        String monthName = start.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        return new MonthCalendarSummary(
                year, month, monthName, lengthOfMonth,
                saturdayDates.size(), sundayDates.size(), saturdayDates.size() + sundayDates.size(),
                List.of(new WeekendDates(List.copyOf(saturdayDates), List.copyOf(sundayDates))),
                holidays.size(), holidays);
    }

    private void validateYear(int year) {
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
    }

    /** Parses the month parameter: {@code null}/blank/"all" -> null (whole year), else 1-12. */
    private Integer parseMonth(String month) {
        if (month == null || month.isBlank() || month.equalsIgnoreCase("all")) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(month.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("month must be 1-12 or 'all'");
        }
        if (value < 1 || value > 12) {
            throw new BadRequestException("month must be 1-12 or 'all'");
        }
        return value;
    }

    private Map<DayOfWeek, Long> countWeekdays(int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate endExclusive = LocalDate.of(year + 1, 1, 1);
        return start.datesUntil(endExclusive)
                .collect(Collectors.groupingBy(LocalDate::getDayOfWeek, Collectors.counting()));
    }
}
