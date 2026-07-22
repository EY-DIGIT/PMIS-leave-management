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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * UIDAI's final decision to convert some of a resource's unpaid leave into a separate "relaxation
 * leave" category for one quarter, recorded <b>after</b> the employee's request/discussion has
 * already happened outside the system. There is no in-app request/approval workflow — this row
 * only stores the outcome: {@code finalUnpaidLeave = originalUnpaidLeave - relaxationDays}.
 * Paid leave is never changed by a relaxation.
 *
 * <p>One row per {@code (resource, project, year, quarter)} — re-recording a decision for the
 * same quarter updates the existing row rather than creating a new one.
 */
@Entity
@Table(
        name = "leave_relaxation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_leave_relaxation_resource_project_period",
                        columnNames = {"resource_id", "project_id", "leave_year", "quarter"}))
@Getter
@Setter
public class LeaveRelaxation {

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

    @Column(name = "leave_year", nullable = false)
    @Setter(AccessLevel.NONE)
    private int year;

    @Column(name = "quarter", nullable = false)
    @Setter(AccessLevel.NONE)
    private int quarter;

    @Column(name = "original_paid_leave", nullable = false)
    private double originalPaidLeave;

    @Column(name = "original_unpaid_leave", nullable = false)
    private double originalUnpaidLeave;

    @Column(name = "relaxation_days", nullable = false)
    private double relaxationDays;

    @Column(name = "final_paid_leave", nullable = false)
    private double finalPaidLeave;

    @Column(name = "final_unpaid_leave", nullable = false)
    private double finalUnpaidLeave;

    @Column(name = "remarks", length = 1000)
    private String remarks;

    @Column(name = "approved_by", length = 200)
    private String approvedBy;

    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LeaveRelaxation() {
        // for JPA
    }

    public LeaveRelaxation(MasterResource resource, String projectId, int year, int quarter) {
        this.resource = resource;
        this.projectId = projectId;
        this.year = year;
        this.quarter = quarter;
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
