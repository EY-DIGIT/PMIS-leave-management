package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Calendar data for a single month: weekend counts and the public holidays that
 * fall within it.
 */
public record MonthCalendarSummary(
        int year,
        int month,
        String monthName,
        int totalDaysInMonth,
        int saturdays,
        int sundays,
        int totalWeekendDays,
        int publicHolidayCount,
        List<HolidayItem> publicHolidays) {}
