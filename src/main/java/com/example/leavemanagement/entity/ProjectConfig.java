package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_config")
public class ProjectConfig {

    @Id
    @Column(name = "project_id", length = 50)
    private String projectId;

    @Column(name = "project_name", nullable = false, length = 200)
    private String projectName;

    @Column(name = "full_day_minutes", nullable = false)
    private int fullDayMinutes;

    @Column(name = "half_day_minutes", nullable = false)
    private int halfDayMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_frequency", nullable = false, length = 20)
    private LeaveFrequency leaveFrequency;

    @Column(name = "max_leaves_per_period", nullable = false)
    private int maxLeavesPerPeriod;

    public ProjectConfig() {}

    public ProjectConfig(
            String projectId,
            String projectName,
            int fullDayMinutes,
            int halfDayMinutes,
            LeaveFrequency leaveFrequency,
            int maxLeavesPerPeriod) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.fullDayMinutes = fullDayMinutes;
        this.halfDayMinutes = halfDayMinutes;
        this.leaveFrequency = leaveFrequency;
        this.maxLeavesPerPeriod = maxLeavesPerPeriod;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public int getFullDayMinutes() {
        return fullDayMinutes;
    }

    public void setFullDayMinutes(int fullDayMinutes) {
        this.fullDayMinutes = fullDayMinutes;
    }

    public int getHalfDayMinutes() {
        return halfDayMinutes;
    }

    public void setHalfDayMinutes(int halfDayMinutes) {
        this.halfDayMinutes = halfDayMinutes;
    }

    public LeaveFrequency getLeaveFrequency() {
        return leaveFrequency;
    }

    public void setLeaveFrequency(LeaveFrequency leaveFrequency) {
        this.leaveFrequency = leaveFrequency;
    }

    public int getMaxLeavesPerPeriod() {
        return maxLeavesPerPeriod;
    }

    public void setMaxLeavesPerPeriod(int maxLeavesPerPeriod) {
        this.maxLeavesPerPeriod = maxLeavesPerPeriod;
    }
}
