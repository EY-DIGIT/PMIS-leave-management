package com.example.leavemanagement.dto;

import com.example.leavemanagement.entity.LeaveRequest;
import com.example.leavemanagement.entity.LeaveStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response describing a leave application and how its days break down:
 * total calendar days = workingDays + weekendDays + holidayDays.
 */
public record LeaveResponse(
        Long id,
        String employeeName,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        LeaveStatus status,
        int totalCalendarDays,
        int workingDays,
        int weekendDays,
        int holidayDays,
        List<HolidayItem> holidaysInRange,
        Instant appliedOn) {

    /**
     * Builds a response from a persisted request plus the day-breakdown that was
     * computed when it was applied for.
     */
    public static LeaveResponse from(LeaveRequest req, LeaveDayBreakdown breakdown) {
        return new LeaveResponse(
                req.getId(),
                req.getEmployeeName(),
                req.getStartDate(),
                req.getEndDate(),
                req.getReason(),
                req.getStatus(),
                breakdown.totalCalendarDays(),
                breakdown.workingDays(),
                breakdown.weekendDays(),
                breakdown.holidayDays(),
                breakdown.holidaysInRange(),
                req.getAppliedOn());
    }
}
