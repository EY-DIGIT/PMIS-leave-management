package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Stores the organisation/project rate card per role — the standard monthly rates for each
 * "Role as per Contract" across Year-1 through Year-7.
 *
 * <p>Upload flow: designation_master_rate Excel must be uploaded <em>before</em> uploading the
 * resource master for the same project/organisation. The resource upload validates every row's
 * "Role as per Contract" against this table — unknown roles are rejected.
 */
@Entity
@Table(
        name = "designation_rate_master",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_desig_rate_role_project_org",
                columnNames = {"role", "project_id", "organisation_id"}))
@Getter
@Setter
public class DesignationRateMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "role", nullable = false, length = 200)
    private String role;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "organisation_id", nullable = false, length = 100)
    private String organisationId;

    /** Year-1..Year-7 rate card, e.g. {"Year-1": 100874.0, "Year-2": 107594.0, ...}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rate_card_by_year")
    private Map<String, Double> rateCardByYear = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DesignationRateMaster() {}

    public DesignationRateMaster(String role, String projectId, String organisationId) {
        this.role = role;
        this.projectId = projectId;
        this.organisationId = organisationId;
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
