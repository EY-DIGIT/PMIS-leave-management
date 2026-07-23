package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.Attendance;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByResourceIdAndAttendanceDate(Long resourceId, LocalDate attendanceDate);

    List<Attendance> findByResourceIdAndAttendanceDateBetween(Long resourceId, LocalDate start, LocalDate end);

    List<Attendance> findByProjectIdAndAttendanceDateBetween(String projectId, LocalDate start, LocalDate end);

    List<Attendance> findByAttendanceDateBetween(LocalDate start, LocalDate end);

    /** Clears a resource's rows in a date range before a re-upload replaces them. */
    void deleteByResourceIdAndAttendanceDateBetween(Long resourceId, LocalDate start, LocalDate end);

    /**
     * Bulk-deletes all rows for a project in a date range in a single SQL statement.
     * Must be used instead of a derived delete so the DELETE is flushed to the DB
     * immediately — before subsequent inserts — avoiding uk_attendance_resource_date violations.
     */
    @Modifying
    @Query("DELETE FROM Attendance a WHERE a.projectId = :projectId AND a.attendanceDate BETWEEN :start AND :end")
    void bulkDeleteByProjectIdAndDateBetween(String projectId, LocalDate start, LocalDate end);
}
