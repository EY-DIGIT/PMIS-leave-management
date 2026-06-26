package com.example.leavemanagement.dto;

import java.util.List;
import java.util.Map;

/**
 * Per-employee totals for a month, derived from the attendance sheet.
 *
 * @param attendanceId id from the sheet
 * @param employeeName resolved name (from the external directory, else the sheet)
 * @param designation designation as printed on the sheet
 * @param leaveDays number of absent <em>working</em> days (In=0 and Out=0, excluding
 *     weekends and public holidays) — i.e. leaves taken in the month
 * @param shortHourDays number of worked days over 4 but under 8 hours
 * @param shortHours map of each short day's date ({@code dd-MM-yyyy}) to the hours
 *     <em>not</em> worked vs a full 8-hour day (e.g. {@code "20-05-2026" -> "2 hrs"}
 *     means 6 hours worked) — days worked &gt;4 and &lt;8 hours
 * @param halfDays the dates ({@code dd-MM-yyyy}) on which the employee worked 4 hours
 *     or less (these are excluded from {@code shortHours})
 */
public record EmployeeAttendanceSummary(
        String attendanceId,
        String employeeName,
        String designation,
        int leaveDays,
        int shortHourDays,
        Map<String, String> shortHours,
        List<String> halfDays) {}
