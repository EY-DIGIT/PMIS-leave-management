package com.example.leavemanagement.dto;

/**
 * Result of persisting a monthly attendance sheet.
 *
 * @param year the year stored
 * @param month the month stored (1-12)
 * @param resourcesStored number of resources upserted from the sheet
 */
public record MonthlyAttendanceStored(int year, int month, int resourcesStored) {}
