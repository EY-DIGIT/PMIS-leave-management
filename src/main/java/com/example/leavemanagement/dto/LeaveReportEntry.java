package com.example.leavemanagement.dto;

public record LeaveReportEntry(
        String attendanceId,
        String employeeName,
        String projectId,
        int permissibleLeave,
        int leaveTaken,
        int paidLeave,
        int unpaidLeave,
        int sandwichDays,
        int totalUnpaidDays,
        int lapsedLeave) {}
