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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Permanent identity/employee data for one workforce resource. {@code resId} (the Attendance ID
 * assigned by the client) is the unique business key — never name or role, both of which can
 * change or repeat across different people.
 *
 * <p>Project/role/rate-card are <b>not</b> stored here — they are time-varying facts about a
 * resource's relationship to a project, tracked as history in {@link ProjectResource}.
 */
@Entity
@Table(
        name = "master_resource",
        uniqueConstraints = @UniqueConstraint(name = "uk_master_resource_res_id", columnNames = "res_id"))
@Getter
@Setter
public class MasterResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "res_id", nullable = false, length = 50)
    @Setter(AccessLevel.NONE)
    private String resId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "email_id", length = 200)
    private String emailId;

    @Column(name = "location", length = 100)
    private String location;

    @Column(name = "category", length = 20)
    private String category;

    @Column(name = "category_details", length = 50)
    private String categoryDetails;

    @Column(name = "date_of_joining")
    private LocalDate dateOfJoining;

    @Column(name = "last_date")
    private LocalDate lastDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MasterResource() {
        // for JPA
    }

    public MasterResource(String resId) {
        this.resId = resId;
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
