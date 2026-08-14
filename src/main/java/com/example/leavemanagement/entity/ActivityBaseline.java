package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * A snapshot of an activity's <em>original approved resource requirement</em> per designation — the
 * baseline used by UIDAI SLA 008 (Additional Resource Onboarding). Any later increase in the live
 * activity configuration (a new designation, or a higher quantity for an existing designation) over
 * this baseline is an "additional resource" subject to SLA 008. Captured once from the live activity
 * config and preserved; replacements never change it.
 */
@Entity
@Table(
        name = "activity_baseline",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_activity_baseline_activity_desig",
                columnNames = {"activity_id", "designation"}))
@Getter
@Setter
public class ActivityBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "activity_id", nullable = false, length = 100)
    private String activityId;

    @Column(name = "designation", nullable = false, length = 200)
    private String designation;

    /** Original (baseline) approved quantity for this designation. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Planned deployment date for the designation — the SLA 008 start date (K). */
    @Column(name = "planned_deployment_date")
    private LocalDate plannedDeploymentDate;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    protected ActivityBaseline() {
        // for JPA
    }

    public ActivityBaseline(String activityId, String designation, int quantity, LocalDate plannedDeploymentDate) {
        this.activityId = activityId;
        this.designation = designation;
        this.quantity = quantity;
        this.plannedDeploymentDate = plannedDeploymentDate;
    }

    @PrePersist
    void onCreate() {
        capturedAt = LocalDateTime.now();
    }
}
