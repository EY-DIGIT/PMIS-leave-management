package com.example.leavemanagement.client;

import java.time.LocalDate;

/**
 * Employee identity returned by the external directory, looked up by attendance id.
 *
 * @param joiningDate date the resource joined; used to pro-rate the first quarter's
 *     permissible leave. May be {@code null} when the directory doesn't supply it
 *     (treated as "present from the quarter start").
 */
public record EmployeeInfo(String attendanceId, String employeeName, String designation, LocalDate joiningDate) {}
