package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * One row parsed from the resource master Excel upload.
 *
 * @param resId Attendance ID — the primary key of the master resource table
 * @param name Employee Name
 * @param role Role as per Contract
 * @param location work location
 * @param dateOfJoining Date of Joining
 * @param lastDayOfWorking Last Day of Working, null if still active
 * @param rateCardByYear Year-1..Year-7 rate card, keyed by "Year-1".."Year-7"
 * @param category RFP / CCN / ASG
 * @param categoryDetails CCN / ASG details (e.g. "CCN001", "ASG1", "NA")
 * @param active derived: true when lastDayOfWorking is blank
 */
public record ResourceRow(
        String resId,
        String name,
        String role,
        String location,
        LocalDate dateOfJoining,
        LocalDate lastDayOfWorking,
        Map<String, Double> rateCardByYear,
        String category,
        String categoryDetails,
        boolean active) {}
