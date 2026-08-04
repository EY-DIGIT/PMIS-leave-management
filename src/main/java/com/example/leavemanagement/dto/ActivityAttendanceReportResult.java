package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

public record ActivityAttendanceReportResult(
        String activityId,
        String activityName,
        String projectId,
        String milestoneId,
        String period,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate reportStartDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate reportEndDate,
        int calendarDays,
        int configuredResourceCount,
        int uploadedResourceCount,
        AttendanceReportTotals totals,
        List<AttendanceReportSummary> resources) {}
