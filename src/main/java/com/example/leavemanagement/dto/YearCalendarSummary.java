package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Response for the "get calendar data" API: weekend counts for the year plus
 * the list of public holidays with their dates.
 */
public record YearCalendarSummary(
        int year,
        int totalDaysInYear,
        int saturdays,
        int sundays,
        int totalWeekendDays,
        int publicHolidayCount,
        List<HolidayItem> publicHolidays) {}
