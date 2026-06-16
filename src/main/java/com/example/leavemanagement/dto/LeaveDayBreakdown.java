package com.example.leavemanagement.dto;

import java.util.List;

/**
 * How a date range splits into chargeable working days vs. weekend days vs.
 * public-holiday days. Used both to persist {@code workingDays} and to enrich
 * the API response.
 */
public record LeaveDayBreakdown(
        int totalCalendarDays,
        int workingDays,
        int weekendDays,
        int holidayDays,
        List<HolidayItem> holidaysInRange) {}
