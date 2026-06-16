package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Leave-policy settlement for a whole quarter, one entry per resource that has
 * stored attendance.
 *
 * @param year the year
 * @param quarter the quarter number (1-4)
 * @param quarterStart first day of the quarter
 * @param quarterEnd last day of the quarter
 * @param monthsWithData which of the quarter's months had attendance uploaded
 * @param resourceCount number of resources in the report
 * @param resources per-resource settlements, sorted by attendance id
 */
public record QuarterLeaveReport(
        int year,
        int quarter,
        LocalDate quarterStart,
        LocalDate quarterEnd,
        List<Integer> monthsWithData,
        int resourceCount,
        List<ResourceQuarterSettlement> resources) {}
