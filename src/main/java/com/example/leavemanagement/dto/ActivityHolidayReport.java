package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * Public holidays within an activity's window. Plain weekends (Sat/Sun) are excluded; a holiday that
 * falls on a weekend is included and tagged {@code WEEKEND_HOLIDAY}, a holiday on a weekday {@code HOLIDAY}.
 */
public record ActivityHolidayReport(
        String activityId,
        String activityName,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityStartDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityEndDate,
        int totalHolidays,
        int weekdayHolidayCount,
        int weekendHolidayCount,
        List<Holiday> holidays) {

    public record Holiday(
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate date,
            String day,
            String type,
            String name) {}
}
