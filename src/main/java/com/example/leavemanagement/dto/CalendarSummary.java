package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Response for the "get calendar data" API. The top-level counts cover the
 * requested scope:
 *
 * <ul>
 *   <li>a single month when {@code month} is 1-12 ({@code totalDays} = days in the
 *       month, {@code months} is empty), or
 *   <li>the whole year when {@code month} is null/"all" ({@code totalDays} = days in
 *       the year and {@code months} holds the 12 per-month breakdowns).
 * </ul>
 *
 * @param month the requested month (1-12), or {@code null} when all months were requested
 */
public record CalendarSummary(
        int year,
        Integer month,
        int totalDays,
        int saturdays,
        int sundays,
        int totalWeekendDays,
        int publicHolidayCount,
        List<HolidayItem> publicHolidays,
        List<MonthCalendarSummary> months) {}
