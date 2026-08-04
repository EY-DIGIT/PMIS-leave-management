package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.Attendance;
import com.example.leavemanagement.entity.MasterResource;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // ------------------------------------------------------------------
    // Existence checks — used by AttendancePeriodValidator to verify that
    // attendance data has been uploaded before any report is generated.
    // ------------------------------------------------------------------

    boolean existsByAttendanceDateBetween(LocalDate start, LocalDate end);

    boolean existsByProjectIdAndAttendanceDateBetween(String projectId, LocalDate start, LocalDate end);

    @Query("SELECT COUNT(a) > 0 FROM Attendance a WHERE a.resource.resId = :resId AND a.attendanceDate BETWEEN :start AND :end")
    boolean existsByResIdAndDateBetween(
            @Param("resId") String resId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MIN(a.attendanceDate) FROM Attendance a WHERE a.projectId = :projectId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMinDateByProjectIdAndDateBetween(
            @Param("projectId") String projectId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MAX(a.attendanceDate) FROM Attendance a WHERE a.projectId = :projectId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMaxDateByProjectIdAndDateBetween(
            @Param("projectId") String projectId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MIN(a.attendanceDate) FROM Attendance a WHERE a.resource.resId = :resId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMinDateByResIdAndDateBetween(
            @Param("resId") String resId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MAX(a.attendanceDate) FROM Attendance a WHERE a.resource.resId = :resId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMaxDateByResIdAndDateBetween(
            @Param("resId") String resId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MIN(a.attendanceDate) FROM Attendance a WHERE a.activityId = :activityId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMinDateByActivityIdAndDateBetween(
            @Param("activityId") String activityId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MAX(a.attendanceDate) FROM Attendance a WHERE a.activityId = :activityId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMaxDateByActivityIdAndDateBetween(
            @Param("activityId") String activityId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT DISTINCT a.resource FROM Attendance a WHERE a.activityId = :activityId AND a.attendanceDate BETWEEN :start AND :end")
    List<MasterResource> findDistinctResourcesByActivityIdAndDateBetween(
            @Param("activityId") String activityId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MIN(a.attendanceDate) FROM Attendance a WHERE a.projectId = :projectId AND a.milestoneId = :milestoneId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMinDateByProjectIdAndMilestoneIdAndDateBetween(
            @Param("projectId") String projectId,
            @Param("milestoneId") String milestoneId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT MAX(a.attendanceDate) FROM Attendance a WHERE a.projectId = :projectId AND a.milestoneId = :milestoneId AND a.attendanceDate BETWEEN :start AND :end")
    Optional<LocalDate> findMaxDateByProjectIdAndMilestoneIdAndDateBetween(
            @Param("projectId") String projectId,
            @Param("milestoneId") String milestoneId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT DISTINCT a.resource FROM Attendance a WHERE a.projectId = :projectId AND a.milestoneId = :milestoneId AND a.attendanceDate BETWEEN :start AND :end")
    List<MasterResource> findDistinctResourcesByProjectIdAndMilestoneIdAndDateBetween(
            @Param("projectId") String projectId,
            @Param("milestoneId") String milestoneId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);
}
