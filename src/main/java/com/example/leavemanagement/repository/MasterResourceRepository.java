package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.MasterResource;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@link JpaSpecificationExecutor} backs the multi-parameter search on GET /api/resources. */
public interface MasterResourceRepository
        extends JpaRepository<MasterResource, Long>, JpaSpecificationExecutor<MasterResource> {

    /** The current stint for a resource — at most one row per resId should have active=true. */
    Optional<MasterResource> findByResIdAndActiveTrue(String resId);

    /** Whether any stint (active or historical) exists for this resource. */
    boolean existsByResId(String resId);

    /** Every stint for a resource, oldest first — the full designation/employment history. */
    List<MasterResource> findByResIdOrderByDateOfJoiningAsc(String resId);

    /** Most recent stint for a resource regardless of active status (fallback when none is active). */
    Optional<MasterResource> findFirstByResIdOrderByDateOfJoiningDesc(String resId);

    /** Every currently active resource under a project — used for the per-project rate card list. */
    List<MasterResource> findByProjectIdAndActiveTrue(String projectId);

    /** The stint effective on the given date: dateOfJoining <= date <= lastDate (or lastDate is open-ended). */
    @Query("SELECT m FROM MasterResource m WHERE m.resId = :resId AND m.dateOfJoining <= :date "
            + "AND (m.lastDate IS NULL OR m.lastDate >= :date)")
    Optional<MasterResource> findEffectiveOn(@Param("resId") String resId, @Param("date") LocalDate date);
}
