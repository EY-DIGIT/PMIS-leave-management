package com.example.leavemanagement.dto;

public record AttendanceReportTotals(
        int resourceCount,
        int calendarDays,
        int workingDays,
        double presentDays,
        int halfDays,
        int leaveDays,
        int absentDays,
        int wfhDays,
        double leaveTaken,
        double paidLeaveDays,
        double unpaidLeaveDays,
        int sandwichDays,
        double avgAttendancePercentage) {}
