package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Per-employee totals for a month, derived from the attendance sheet.
 *
 * @param attendanceId id from the sheet
 * @param employeeName resolved name (from the external directory, else the sheet)
 * @param designation designation as printed on the sheet
 * @param leaveDays number of absent <em>working</em> days (In=0 and Out=0, excluding
 *     weekends and public holidays) — i.e. leaves taken in the month
 * @param shortHourDays number of worked days under 8 hours (Out-Time minus In-Time)
 * @param shortHourDayNumbers the day-of-month numbers of those short days, sorted
 */
public record EmployeeAttendanceSummary(
        String attendanceId,
        String employeeName,
        String designation,
        int leaveDays,
        int shortHourDays,
        List<Integer> shortHourDayNumbers) {}
