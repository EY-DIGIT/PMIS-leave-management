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
 * <p>Attendance is scoped to its full context: {@code (project_id, milestone_id, activity_id,
 * resource_id, attendance_date)} is unique. The same resource can therefore have attendance on the
 * same day under different milestones/activities (e.g. M1/A1 and M2/A2) as separate rows. Re-uploading
 * the same context is incremental: each uploaded (context, resource, date) row is upserted (updated if
 * it exists, else inserted); rows for other resources/dates/months/activities are untouched.
 */
@Entity
@Table(
        name = "attendance",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_attendance_context_date",
                        columnNames = {"project_id", "milestone_id", "activity_id", "resource_id", "attendance_date"}))
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

    @Column(name = "organisation_id", length = 50)
    private String organisationId;

    @Column(name = "milestone_id", nullable = false, length = 50)
    private String milestoneId;

    @Column(name = "activity_id", length = 100)
    private String activityId;

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
            String organisationId,
            String milestoneId,
            String activityId,
            LocalDate attendanceDate,
            AttendanceStatus status) {
        this.resource = resource;
        this.projectId = projectId;
        this.organisationId = organisationId;
        this.milestoneId = milestoneId;
        this.activityId = activityId;
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
