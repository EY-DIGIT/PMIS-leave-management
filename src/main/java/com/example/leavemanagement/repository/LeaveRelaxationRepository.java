package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.LeaveRelaxation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveRelaxationRepository extends JpaRepository<LeaveRelaxation, Long> {

    Optional<LeaveRelaxation> findByResource_ResIdAndProjectIdAndYearAndQuarter(
            String resId, String projectId, int year, int quarter);
}
