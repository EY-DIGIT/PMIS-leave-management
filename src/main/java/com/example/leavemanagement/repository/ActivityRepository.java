package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.Activity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    Optional<Activity> findByActivityId(String activityId);
}
