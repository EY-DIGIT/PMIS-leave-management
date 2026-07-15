package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.Attendance;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByResourceIdAndAttendanceDate(Long resourceId, LocalDate attendanceDate);

    List<Attendance> findByResourceIdAndAttendanceDateBetween(Long resourceId, LocalDate start, LocalDate end);

    List<Attendance> findByProjectIdAndAttendanceDateBetween(String projectId, LocalDate start, LocalDate end);

    List<Attendance> findByAttendanceDateBetween(LocalDate start, LocalDate end);

    /** Clears a resource's rows in a date range before a re-upload replaces them. */
    void deleteByResourceIdAndAttendanceDateBetween(Long resourceId, LocalDate start, LocalDate end);
}
