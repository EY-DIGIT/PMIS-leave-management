package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Summary for one month, computed from an uploaded attendance sheet.
 *
 * @param year the year covered
 * @param month the month covered (1-12)
 * @param totalDaysInMonth number of calendar days in the month
 * @param saturdays count of Saturdays in the month
 * @param sundays count of Sundays in the month
 * @param totalWeekendDays saturdays + sundays
 * @param publicHolidayCount number of stored public holidays that fall in the month
 * @param publicHolidays those public holidays (date + name)
 * @param employeeCount number of employees in the sheet
 * @param employees per-employee leave and short-hour totals
 */
public record MonthlyAttendanceSummary(
        int year,
        int month,
        int totalDaysInMonth,
        int saturdays,
        int sundays,
        int totalWeekendDays,
        int publicHolidayCount,
        List<HolidayItem> publicHolidays,
        int employeeCount,
        List<EmployeeAttendanceSummary> employees) {}
