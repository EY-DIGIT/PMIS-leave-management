package com.example.leavemanagement.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * One resource's parsed attendance for a single month, persisted so a whole
 * quarter can be assembled on demand for the leave-policy calculation.
 *
 * <p>Only absent <b>weekday</b> day-numbers are stored (weekend 0/0 cells are
 * noise). Whether a stored absence actually counts as leave is decided at
 * compute time against the public-holiday calendar.
 *
 * <p>There is intentionally no unique constraint on {@code (attendanceId, year,
 * month)}: some exports mask the attendance id (the same value for every
 * resource), so a month may contain repeats. A re-upload replaces the whole
 * month's rows rather than upserting per id.
 */
@Entity
@Table(name = "resource_monthly_attendance")
@Getter
@Setter
public class ResourceMonthlyAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "attendance_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private String attendanceId;

    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "designation")
    private String designation;

    @Column(name = "milestone_id")
    private String milestoneId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "att_year", nullable = false)
    @Setter(AccessLevel.NONE)
    private int year;

    @Column(name = "att_month", nullable = false)
    @Setter(AccessLevel.NONE)
    private int month;

    @ElementCollection
    @CollectionTable(
            name = "resource_monthly_absent_day",
            joinColumns = @JoinColumn(name = "attendance_row_id"))
    @Column(name = "day_of_month")
    private Set<Integer> absentDays = new HashSet<>();

    /** Day-of-month -> minutes worked (Out-Time minus In-Time), for days with both punches. */
    @ElementCollection
    @CollectionTable(
            name = "resource_monthly_worked_minutes",
            joinColumns = @JoinColumn(name = "attendance_row_id"))
    @MapKeyColumn(name = "day_of_month")
    @Column(name = "worked_minutes")
    private Map<Integer, Integer> workedMinutesByDay = new HashMap<>();

    protected ResourceMonthlyAttendance() {
        // for JPA
    }

    public ResourceMonthlyAttendance(
            String attendanceId, String employeeName, String designation, int year, int month) {
        this(attendanceId, employeeName, designation, null, year, month);
    }

    public ResourceMonthlyAttendance(
            String attendanceId,
            String employeeName,
            String designation,
            String milestoneId,
            int year,
            int month) {
        this(attendanceId, employeeName, designation, milestoneId, null, year, month);
    }

    public ResourceMonthlyAttendance(
            String attendanceId,
            String employeeName,
            String designation,
            String milestoneId,
            String projectId,
            int year,
            int month) {
        this.attendanceId = attendanceId;
        this.employeeName = employeeName;
        this.designation = designation;
        this.milestoneId = milestoneId;
        this.projectId = projectId;
        this.year = year;
        this.month = month;
    }
}
