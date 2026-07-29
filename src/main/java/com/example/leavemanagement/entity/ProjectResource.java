package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One assignment of a {@link MasterResource} to a project — the resource's role and rate card are
 * project-scoped facts, not permanent employee attributes, so they live here rather than on
 * {@link MasterResource}.
 *
 * <p>A resource can have several rows over time (one per project/role period), preserving full
 * assignment history. At most one row per resource should have {@code active = true} — that is the
 * resource's current project and role. Moving projects or changing role closes the current
 * assignment ({@code active = false}, {@code assignmentEndDate} set) and opens a new one, rather
 * than overwriting it.
 */
@Entity
@Table(name = "project_resource")
@Getter
@Setter
public class ProjectResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private MasterResource resource;

    @Column(name = "project_id", nullable = false, length = 50)
    @Setter(AccessLevel.NONE)
    private String projectId;

    @Column(name = "organisation_id", length = 100)
    private String organisationId;

    /** resId of the resource replacing this one at designation handover; null while still active. */
    @Column(name = "replaced_by_res_id", length = 50)
    private String replacedByResId;

    @Column(name = "role", length = 200)
    private String role;

    /** Rate_Year column from the upload, stored exactly as given — not parsed or computed. */
    @Column(name = "rate_year", length = 50)
    private String rateYear;

    /** Year-1..Year-7 rate card, e.g. {"Year-1": 100874.0, "Year-2": 107594.0, ...}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rate_card_by_year")
    private Map<String, Double> rateCardByYear = new LinkedHashMap<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "assignment_start_date", nullable = false)
    @Setter(AccessLevel.NONE)
    private LocalDate assignmentStartDate;

    @Column(name = "assignment_end_date")
    private LocalDate assignmentEndDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProjectResource() {
        // for JPA
    }

    public ProjectResource(MasterResource resource, String projectId, String role, LocalDate assignmentStartDate) {
        this.resource = resource;
        this.projectId = projectId;
        this.role = role;
        this.assignmentStartDate = assignmentStartDate;
        this.active = true;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
