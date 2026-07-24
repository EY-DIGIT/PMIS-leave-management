package com.example.leavemanagement.service;

import com.example.leavemanagement.exception.NotFoundException;
import com.example.leavemanagement.repository.AttendanceRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Verifies that attendance data has been uploaded for a requested period before any
 * report or cost calculation is served. Throws {@link NotFoundException} (HTTP 404)
 * with a human-readable message when no rows exist.
 *
 * <p>Scope priority: resource (resId) → project → all. Pass {@code null} for unused scopes.
 */
@Component
public class AttendancePeriodValidator {

    private final AttendanceRepository attendanceRepository;

    public AttendancePeriodValidator(AttendanceRepository attendanceRepository) {
        this.attendanceRepository = attendanceRepository;
    }

    /**
     * Checks that at least one attendance row exists for the given date range and scope.
     *
     * @param start       first day of the period (inclusive)
     * @param end         last day of the period (inclusive)
     * @param projectId   restrict scope to this project (ignored when {@code resId} is set)
     * @param resId       restrict scope to this resource's res_id (takes priority over projectId)
     * @param periodLabel human-readable label used in the error message, e.g. "July 2026"
     * @throws NotFoundException if no attendance data is found for the given scope and period
     */
    public void validate(LocalDate start, LocalDate end, String projectId, String resId, String periodLabel) {
        boolean exists;
        String scope;

        if (resId != null && !resId.isBlank()) {
            exists = attendanceRepository.existsByResIdAndDateBetween(resId, start, end);
            scope = "resource " + resId;
        } else if (projectId != null && !projectId.isBlank()) {
            exists = attendanceRepository.existsByProjectIdAndAttendanceDateBetween(projectId, start, end);
            scope = "project " + projectId;
        } else {
            exists = attendanceRepository.existsByAttendanceDateBetween(start, end);
            scope = null;
        }

        if (!exists) {
            String msg = scope != null
                    ? "No attendance data found for " + scope + " in " + periodLabel
                            + ". Please upload the attendance sheet first."
                    : "No attendance data found for " + periodLabel
                            + ". Please upload the attendance sheet first.";
            throw new NotFoundException(msg);
        }
    }
}
