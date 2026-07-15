package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * Result of uploading an attendance sheet for a period.
 *
 * @param startDate the upload's Attendance Start Date
 * @param endDate the upload's Attendance End Date
 * @param totalRows resources read from the sheet
 * @param resourcesStored resources whose attendance was persisted
 * @param leavePoliciesByProject each valid resource's project's leave policy, fetched from the
 *     external leave-policy API, keyed by projectId
 */
public record AttendanceUploadResult(
        LocalDate startDate,
        LocalDate endDate,
        int totalRows,
        int resourcesStored,
        Map<String, LeavePolicyResponse> leavePoliciesByProject) {}
