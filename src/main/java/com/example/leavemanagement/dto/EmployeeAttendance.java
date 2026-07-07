package com.example.leavemanagement.dto;

import java.util.Map;
import java.util.Set;

/**
 * One employee's attendance parsed from the uploaded sheet.
 *
 * @param attendanceId id from column A (used to fetch the employee from the external directory)
 * @param employeeName name as printed on the sheet (fallback if the directory has none)
 * @param designation designation as printed on the sheet
 * @param milestoneId milestone id from column D as printed on the sheet
 * @param absentDays day-of-month numbers (1..31) where both In-Time and Out-Time were 0
 * @param workedMinutesByDay day-of-month -> minutes worked (Out-Time minus In-Time), only for
 *     days where both an In-Time and an Out-Time were present
 */
public record EmployeeAttendance(
        String attendanceId,
        String employeeName,
        String designation,
        String milestoneId,
        Set<Integer> absentDays,
        Map<Integer, Integer> workedMinutesByDay) {}
