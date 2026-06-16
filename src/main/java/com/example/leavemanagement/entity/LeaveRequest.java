package com.example.leavemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A leave application by an employee. {@code workingDays} is the number of days
 * actually consumed: calendar days minus weekends and public holidays.
 */
@Entity
@Table(name = "leave_request")
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_name", nullable = false, length = 200)
    private String employeeName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeaveStatus status = LeaveStatus.PENDING;

    /** Chargeable leave days (weekends and public holidays excluded). */
    @Column(name = "working_days", nullable = false)
    private int workingDays;

    @CreationTimestamp
    @Column(name = "applied_on", nullable = false, updatable = false)
    private Instant appliedOn;

    protected LeaveRequest() {
        // for JPA
    }

    public LeaveRequest(String employeeName, LocalDate startDate, LocalDate endDate, String reason, int workingDays) {
        this.employeeName = employeeName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.reason = reason;
        this.workingDays = workingDays;
        this.status = LeaveStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getReason() {
        return reason;
    }

    public LeaveStatus getStatus() {
        return status;
    }

    public void setStatus(LeaveStatus status) {
        this.status = status;
    }

    public int getWorkingDays() {
        return workingDays;
    }

    public Instant getAppliedOn() {
        return appliedOn;
    }
}
