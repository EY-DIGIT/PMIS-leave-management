package com.example.leavemanagement.repository;

import com.example.leavemanagement.entity.ProjectYearMapping;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectYearMappingRepository extends JpaRepository<ProjectYearMapping, Long> {

    Optional<ProjectYearMapping> findByProjectIdAndOrganisationIdAndRateYear(
            String projectId, String organisationId, String rateYear);

    List<ProjectYearMapping> findByProjectIdAndOrganisationIdOrderByEffectiveFromAsc(
            String projectId, String organisationId);

    /**
     * Rate-year lookup for a given date within a project/organisation. Returns the unique mapping
     * row that covers {@code date} (year ranges within a project never overlap).
     */
    @Query("""
            SELECT m FROM ProjectYearMapping m
            WHERE m.projectId = :projectId
              AND m.organisationId = :organisationId
              AND m.effectiveFrom <= :date
              AND m.effectiveTo >= :date
            """)
    Optional<ProjectYearMapping> findEffectiveOn(
            @Param("projectId") String projectId,
            @Param("organisationId") String organisationId,
            @Param("date") LocalDate date);

    /** All mappings whose range overlaps [startDate, endDate], ordered by effectiveFrom. */
    @Query("""
            SELECT m FROM ProjectYearMapping m
            WHERE m.projectId = :projectId
              AND m.organisationId = :organisationId
              AND m.effectiveFrom <= :endDate
              AND m.effectiveTo >= :startDate
            ORDER BY m.effectiveFrom ASC
            """)
    List<ProjectYearMapping> findOverlapping(
            @Param("projectId") String projectId,
            @Param("organisationId") String organisationId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
