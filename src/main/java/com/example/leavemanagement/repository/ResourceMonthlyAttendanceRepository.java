package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.ResourceMonthlyAttendance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceMonthlyAttendanceRepository extends JpaRepository<ResourceMonthlyAttendance, Long> {

    /** All stored rows for one month (used for the monthly summary and to replace on re-upload). */
    List<ResourceMonthlyAttendance> findByYearAndMonth(int year, int month);

    /** All stored rows for the given months of a year (used to assemble a quarter). */
    List<ResourceMonthlyAttendance> findByYearAndMonthIn(int year, List<Integer> months);

    /** All stored rows for a year (used for the all-months summary). */
    List<ResourceMonthlyAttendance> findByYear(int year);
}
