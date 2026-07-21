package com.example.leavemanagement.dto;

import java.time.LocalDate;

/**
 * One row parsed from the resource master Excel upload.
 *
 * <p>Rate card (Year-1..Year-7 monthly rates) is no longer carried in the Excel — it is resolved
 * from the designation rate master at upload time using the resource's role.
 *
 * @param resId            Attendance ID — the primary key of the master resource table
 * @param name             Employee Name
 * @param role             Role as per Contract (must exist in the designation rate master)
 * @param location         work location
 * @param dateOfJoining    Date of Joining
 * @param lastDayOfWorking Last Day of Working, null if still active
 * @param category         RFP / CCN / ASG
 * @param categoryDetails  CCN / ASG details (e.g. "CCN001", "ASG1", "NA")
 * @param active           derived: true when lastDayOfWorking is blank
 */
public record ResourceRow(
        String resId,
        String name,
        String role,
        String location,
        LocalDate dateOfJoining,
        LocalDate lastDayOfWorking,
        String category,
        String categoryDetails,
        boolean active) {}
