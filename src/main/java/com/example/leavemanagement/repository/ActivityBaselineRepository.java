package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.ActivityBaseline;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityBaselineRepository extends JpaRepository<ActivityBaseline, Long> {

    List<ActivityBaseline> findByActivityId(String activityId);

    boolean existsByActivityId(String activityId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ActivityBaseline b WHERE b.activityId = :activityId")
    void deleteByActivityId(@Param("activityId") String activityId);
}
