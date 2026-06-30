package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

public record EmployeeLeaveDetail(
        String attendanceId,
        String employeeName,
        String projectId,
        String projectName,
        LocalDate joiningDate,
        int year,
        int quarter,
        LocalDate quarterStart,
        LocalDate quarterEnd,
        int permissibleLeave,
        int leaveTaken,
        int paidLeave,
        int unpaidLeave,
        int sandwichDays,
        int totalUnpaidDays,
        int lapsedLeave,
        List<LocalDate> paidLeaveDates,
        List<LocalDate> unpaidLeaveDates,
        List<LocalDate> sandwichDates) {}
