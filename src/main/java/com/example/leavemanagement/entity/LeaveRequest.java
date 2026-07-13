package com.example.leavemanagement.entity;

/*
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "leave_request")
@Getter
@Setter
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "employee_name", nullable = false, length = 200)
    private String employeeName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeaveStatus status = LeaveStatus.PENDING;

    // Chargeable leave days (weekends and public holidays excluded).
    @Column(name = "working_days", nullable = false)
    private int workingDays;

    @CreationTimestamp
    @Column(name = "applied_on", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant appliedOn;

    protected LeaveRequest() {
        // for JPA
    }

    public LeaveRequest(String employeeName, String email, LocalDate startDate, LocalDate endDate, String reason, int workingDays) {
        this.employeeName = employeeName;
        this.email = email;
        this.startDate = startDate;
        this.endDate = endDate;
        this.reason = reason;
        this.workingDays = workingDays;
        this.status = LeaveStatus.PENDING;
    }
}
*/
