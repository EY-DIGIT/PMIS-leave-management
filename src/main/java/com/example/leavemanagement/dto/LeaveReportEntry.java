package com.example.leavemanagement.dto;

public record LeaveReportEntry(
        String attendanceId,
        String employeeName,
        String projectId,
        int permissibleLeave,
        double leaveTaken,
        double paidLeave,
        double unpaidLeave,
        int sandwichDays,
        double totalUnpaidDays,
        double lapsedLeave) {}
