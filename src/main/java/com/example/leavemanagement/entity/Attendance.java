package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * One resource's attendance outcome for a single calendar day. Only days that need an actual
 * fact are stored — {@code P}/{@code HD}/{@code A}/{@code L}/{@code WFH}; weekends and public
 * holidays are derived at report time from the day-of-week and {@link PublicHoliday}, rather than
 * persisted as a row per resource per day (that would be pure duplication of calendar data).
 *
 * <p>{@code (resource_id, attendance_date)} is unique, so the same resource can't have two
 * attendance facts on the same day; re-uploading a period replaces its rows instead of upserting.
 */
@Entity
@Table(
        name = "attendance",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_attendance_resource_date",
                        columnNames = {"resource_id", "attendance_date"}))
@Getter
@Setter
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private MasterResource resource;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "milestone_id", nullable = false, length = 50)
    private String milestoneId;

    @Column(name = "attendance_date", nullable = false)
    @Setter(AccessLevel.NONE)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private AttendanceStatus status;

    /** Hours worked (Out-Time minus In-Time), for days with both punches. */
    @Column(name = "working_hours")
    private Double workingHours;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Attendance() {
        // for JPA
    }

    public Attendance(
            MasterResource resource,
            String projectId,
            String milestoneId,
            LocalDate attendanceDate,
            AttendanceStatus status) {
        this.resource = resource;
        this.projectId = projectId;
        this.milestoneId = milestoneId;
        this.attendanceDate = attendanceDate;
        this.status = status;
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
