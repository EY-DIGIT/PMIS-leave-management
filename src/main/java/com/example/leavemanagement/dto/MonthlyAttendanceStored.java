package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Result of persisting an attendance upload. A period spanning more than one calendar month
 * (e.g. 25-Jul-2026 to 24-Aug-2026) is split and stored as one row-set per covered month.
 *
 * @param startDate the upload's Attendance Start Date
 * @param endDate the upload's Attendance End Date
 * @param months per covered calendar month, how many resource rows were stored
 * @param leavePoliciesByProject each valid resource's project's leave policy, fetched from the
 *     external leave-policy API, keyed by projectId
 */
public record MonthlyAttendanceStored(
        LocalDate startDate, LocalDate endDate, List<MonthCount> months, Map<String, LeavePolicyResponse>
                leavePoliciesByProject) {

    /** Resource rows stored for one calendar month covered by the upload period. */
    public record MonthCount(int year, int month, int resourcesStored) {}
}
