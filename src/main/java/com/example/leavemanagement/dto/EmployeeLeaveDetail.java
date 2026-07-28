package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * @param permissibleLeave total paid allowance this quarter — base allowance plus {@code
 *     carriedForwardLeave}
 * @param carriedForwardLeave unused permissible leave brought in from the previous quarter (0
 *     unless the project's leave policy allows carry-forward)
 * @param leaveTaken effective leave consumed: full absent days count 1.0, half-days count 0.5
 * @param paidLeave leave days covered by the permissible allowance
 * @param unpaidLeave unpaid leave <b>after</b> any recorded {@code relaxationLeave} is subtracted
 * @param relaxationLeave days converted from unpaid leave into relaxation leave for this quarter
 * @param totalUnpaidDays unpaidLeave + sandwichDays (the payable deduction), after relaxation
 * @param lapsedLeaveDays permissible days left unused this quarter
 * @param halfDayDates calendar dates on which the resource was marked HD (half-day)
 * @param paidLeaveDates absent/half-day dates covered by the permissible leave quota
 * @param unpaidLeaveDates absent/half-day dates that exceeded the quota (partially-covered date
 *     appears in both lists)
 * @param sandwichDates non-working days (weekends/holidays) sandwiched between unpaid absences
 */
public record EmployeeLeaveDetail(
        String attendanceId,
        String employeeName,
        String projectId,
        String projectName,
        String milestoneId,
        String activityId,
        String designation,
        LocalDate joiningDate,
        int year,
        int quarter,
        LocalDate quarterStart,
        LocalDate quarterEnd,
        int permissibleLeave,
        int carriedForwardLeave,
        double leaveTaken,
        double paidLeave,
        double unpaidLeave,
        double relaxationLeave,
        int sandwichDays,
        double totalUnpaidDays,
        double lapsedLeave,
        List<LocalDate> halfDayDates,
        List<LocalDate> paidLeaveDates,
        List<LocalDate> unpaidLeaveDates,
        List<LocalDate> sandwichDates) {}
