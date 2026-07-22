package com.example.leavemanagement.dto;

import java.util.List;

/**
 * Wrapper returned by monthly/quarterly/yearly attendance report endpoints.
 * Contains the per-resource rows plus an aggregate {@link AttendanceReportTotals} summary.
 */
public record AttendanceReportResult(
        String period,
        int resourceCount,
        AttendanceReportTotals totals,
        List<AttendanceReportSummary> resources) {}
