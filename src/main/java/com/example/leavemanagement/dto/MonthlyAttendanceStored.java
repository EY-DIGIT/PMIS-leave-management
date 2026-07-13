package com.example.leavemanagement.dto;

import java.util.Map;

/**
 * Result of persisting a monthly attendance sheet.
 *
 * @param year the year stored
 * @param month the month stored (1-12)
 * @param resourcesStored number of resources upserted from the sheet
 * @param leavePoliciesByProject each valid resource's project's leave policy, fetched from the
 *     external leave-policy API, keyed by projectId
 */
public record MonthlyAttendanceStored(
        int year, int month, int resourcesStored, Map<String, LeavePolicyResponse> leavePoliciesByProject) {}
