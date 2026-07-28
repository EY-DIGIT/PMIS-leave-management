package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps each project year (Year-1..Year-7) to its calendar date range for a given project and
 * organisation. Created (or refreshed) when the designation rate Excel is uploaded with
 * projectStartDate and projectEndDate.
 *
 * <p>Example: project starts 2026-08-08, Year-1 runs 2026-08-08 → 2027-08-07.
 * The date ranges are used during cost calculation to auto-select the correct rate year for
 * any attendance date, removing the need for manual rateYear selection.
 */
@Entity
@Table(
        name = "project_year_mapping",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_project_year_mapping_pid_oid_year",
                columnNames = {"project_id", "organisation_id", "rate_year"}))
@Getter
@Setter
public class ProjectYearMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "organisation_id", nullable = false, length = 100)
    private String organisationId;

    /** Rate year label, e.g. "Year-1", "Year-3". */
    @Column(name = "rate_year", nullable = false, length = 50)
    private String rateYear;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to", nullable = false)
    private LocalDate effectiveTo;

    protected ProjectYearMapping() {}

    public ProjectYearMapping(
            String projectId, String organisationId,
            String rateYear, LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.projectId = projectId;
        this.organisationId = organisationId;
        this.rateYear = rateYear;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }
}
