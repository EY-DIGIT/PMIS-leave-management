package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "resource_project")
@Getter
@Setter
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
}
