package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

/**
 * One resource's attendance totals for a period (month/quarter/year).
 *
 * <p>{@code workingDays} = calendar days from {@code max(periodStart, joiningDate)} to
 * {@code min(periodEnd, lastWorkingDate)} minus weekends and public holidays.
 * {@code leaveTaken} = {@code absentDays + halfDays×0.5}.
 * {@code paidLeaveDays} = {@code min(leaveTaken, leaveLimit)}; the remainder is
 * {@code unpaidLeaveDays}. {@code active} is {@code false} for resources whose assignment
 * ended before the report is generated; {@code lastWorkingDate} is their assignment end date.
 */
public record AttendanceReportSummary(
        String attendanceId,
        String employeeName,
        String designation,
        String projectId,
        String milestoneId,
        String activityId,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate joiningDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate lastWorkingDate,
        boolean active,
        String period,
        int workingDays,
        double presentDays,
        int halfDays,
        int leaveDays,
        int absentDays,
        int weekOffDays,
        int holidayDays,
        int wfhDays,
        double attendancePercentage,
        double leaveTaken,
        double paidLeaveDays,
        double unpaidLeaveDays) {}
