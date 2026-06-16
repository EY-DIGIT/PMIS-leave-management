package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Attendance summary scoped by month. For a single month {@code month} is set and
 * {@code months} holds one entry; for {@code month=all}, {@code month} is null and
 * {@code months} holds one summary per stored month of the year (sorted).
 *
 * @param year the year
 * @param month the requested month (1-12), or null when all months were requested
 * @param months per-month summaries (weekends/holidays + per-employee leaves and short-hour days)
 */
public record AttendanceSummaryReport(int year, Integer month, List<MonthlyAttendanceSummary> months) {}
