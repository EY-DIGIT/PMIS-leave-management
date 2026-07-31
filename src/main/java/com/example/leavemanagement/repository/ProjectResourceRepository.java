package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.ProjectResource;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectResourceRepository extends JpaRepository<ProjectResource, Long> {

    /** The resource's current assignment — at most one row per resourceId should have active=true. */
    Optional<ProjectResource> findByResourceIdAndActiveTrue(Long resourceId);

    /** The resource's current assignment on a project, looked up by resId (e.g. from an attendance upload). */
    Optional<ProjectResource> findByResource_ResIdAndProjectIdAndActiveTrue(String resId, String projectId);

    /** Every currently active assignment under a project — used for the per-project rate card list. */
    List<ProjectResource> findByProjectIdAndActiveTrue(String projectId);

    /** Every assignment under a project, both active and inactive. */
    List<ProjectResource> findByProjectId(String projectId);

    /** Every assignment for a resource, oldest first — the full project/role history. */
    List<ProjectResource> findByResourceIdOrderByAssignmentStartDateAsc(Long resourceId);

    /** The assignment on a project effective on the given date (assignmentStartDate <= date <= assignmentEndDate, or open-ended). */
    @Query("SELECT pr FROM ProjectResource pr WHERE pr.resource.resId = :resId AND pr.projectId = :projectId "
            + "AND pr.assignmentStartDate <= :date AND (pr.assignmentEndDate IS NULL OR pr.assignmentEndDate >= :date)")
    Optional<ProjectResource> findEffectiveOn(
            @Param("resId") String resId, @Param("projectId") String projectId, @Param("date") LocalDate date);

    /**
     * All active assignments whose resource's last working date has been reached (lastDate <= today).
     * Used by the nightly deactivation scheduler to close assignments for scheduled exits.
     */
    @Query("SELECT pr FROM ProjectResource pr WHERE pr.active = true "
            + "AND pr.resource.lastDate IS NOT NULL AND pr.resource.lastDate <= :today")
    List<ProjectResource> findActiveAssignmentsDueForDeactivation(@Param("today") LocalDate today);

    /** The assignment whose outgoing resource named {@code replacedByResId} as its replacement. */
    Optional<ProjectResource> findByReplacedByResIdAndProjectId(String replacedByResId, String projectId);
}
