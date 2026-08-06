package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

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
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate attendanceStartDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate attendanceEndDate,
        int calendarDays,
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
        double unpaidLeaveDays,
        int sandwichDays) {}
