package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "project_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_project_config_pid_oid",
                columnNames = {"project_id", "organisation_id"}))
@Getter
@Setter
public class ProjectConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "organisation_id", nullable = false, length = 100)
    private String organisationId;

    /** Day of month (1–28) on which each quarter's billing/attendance cycle starts. */
    @Column(name = "quarter_cycle_day", nullable = false)
    private int quarterCycleDay = 1;

    protected ProjectConfig() {}

    public ProjectConfig(String projectId, String organisationId, int quarterCycleDay) {
        this.projectId = projectId;
        this.organisationId = organisationId;
        this.quarterCycleDay = quarterCycleDay;
    }
}
