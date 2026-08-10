package com.example.leavemanagement.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * All non-working days within an activity's window — public holidays plus weekend days (Sat/Sun) —
 * combined into a single chronological list, with public holidays and weekends also broken out.
 */
public record ActivityHolidayReport(
        String activityId,
        String activityName,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityStartDate,
        @JsonFormat(pattern = "dd-MM-yyyy") LocalDate activityEndDate,
        int totalNonWorkingDays,
        int publicHolidayCount,
        int weekendCount,
        List<NonWorkingDay> nonWorkingDays) {

    public record NonWorkingDay(
            @JsonFormat(pattern = "dd-MM-yyyy") LocalDate date,
            String day,
            String type,
            String name) {}
}
