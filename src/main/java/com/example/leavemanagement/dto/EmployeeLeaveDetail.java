package com.example.leavemanagement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * @param permissibleLeave total paid allowance this quarter — base allowance plus {@code
 *     carriedForwardLeave}
 * @param carriedForwardLeave unused permissible leave brought in from the previous quarter (0
 *     unless the project's leave policy allows carry-forward)
 * @param unpaidLeave unpaid leave <b>after</b> any recorded {@code relaxationLeave} is subtracted
 * @param relaxationLeave days UIDAI converted from unpaid leave into a separate relaxation
 *     category for this quarter (0 if none recorded) — paid leave is never changed by a
 *     relaxation, see {@link com.example.leavemanagement.entity.LeaveRelaxation}
 * @param totalUnpaidDays unpaidLeave + sandwichDays (the payable deduction), after relaxation
 */
public record EmployeeLeaveDetail(
        String attendanceId,
        String employeeName,
        String projectId,
        String projectName,
        LocalDate joiningDate,
        int year,
        int quarter,
        LocalDate quarterStart,
        LocalDate quarterEnd,
        int permissibleLeave,
        int carriedForwardLeave,
        int leaveTaken,
        int paidLeave,
        int unpaidLeave,
        int relaxationLeave,
        int sandwichDays,
        int totalUnpaidDays,
        int lapsedLeave,
        List<LocalDate> paidLeaveDates,
        List<LocalDate> unpaidLeaveDates,
        List<LocalDate> sandwichDates) {}
