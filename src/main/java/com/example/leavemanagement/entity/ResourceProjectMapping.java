package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "resource_project")
public class ResourceProjectMapping {

    @Id
    @Column(name = "attendance_id", length = 50)
    private String attendanceId;

    @Column(name = "project_id", nullable = false, length = 50)
    private String projectId;

    @Column(name = "employee_name", length = 200)
    private String employeeName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    public ResourceProjectMapping() {}

    public ResourceProjectMapping(
            String attendanceId, String projectId, String employeeName, String email, LocalDate joiningDate) {
        this.attendanceId = attendanceId;
        this.projectId = projectId;
        this.employeeName = employeeName;
        this.email = email;
        this.joiningDate = joiningDate;
    }

    public String getAttendanceId() {
        return attendanceId;
    }

    public void setAttendanceId(String attendanceId) {
        this.attendanceId = attendanceId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getJoiningDate() {
        return joiningDate;
    }

    public void setJoiningDate(LocalDate joiningDate) {
        this.joiningDate = joiningDate;
    }
}
