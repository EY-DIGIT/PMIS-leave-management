package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

public record LeaveReportSummary(
        int year,
        int quarter,
        LocalDate quarterStart,
        LocalDate quarterEnd,
        int employeeCount,
        List<LeaveReportEntry> employees) {}
