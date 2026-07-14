package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * One employee's attendance parsed from the uploaded sheet, keyed by actual calendar date. The
 * sheet's day columns map positionally onto the upload's Attendance Start/End Date range (column
 * N is Attendance Start Date + N - 1), so a single upload can span a partial month or cross a
 * month boundary.
 *
 * @param attendanceId id from column A
 * @param employeeName name as printed on the sheet (fallback if the directory has none)
 * @param designation designation as printed on the sheet
 * @param absentDates calendar dates where both In-Time and Out-Time were 0
 * @param workedMinutesByDate calendar date -> minutes worked (Out-Time minus In-Time), only for
 *     dates where both an In-Time and an Out-Time were present
 */
public record EmployeeAttendanceByDate(
        String attendanceId,
        String employeeName,
        String designation,
        Set<LocalDate> absentDates,
        Map<LocalDate, Integer> workedMinutesByDate) {}
