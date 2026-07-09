package com.example.leavemanagement.dto;

import java.time.LocalDate;

/**
 * One row parsed from the resource master Excel upload.
 *
 * @param resId id from column A (res_id) — the primary key of the master resource table
 * @param name resource name (column B)
 * @param emailId email address (column C)
 * @param rateCard rate card amount (column D)
 * @param dateOfJoining joining date (column E)
 * @param lastDate relieving/last-working date (column F), null if still active
 * @param designationType designation (column G)
 * @param active parsed from "isactive" Yes/No (column H)
 */
public record ResourceRow(
        String resId,
        String name,
        String emailId,
        Double rateCard,
        LocalDate dateOfJoining,
        LocalDate lastDate,
        String designationType,
        boolean active) {}
