package com.example.leavemanagement.client;

import java.util.Optional;

/**
 * Looks up employee details by the attendance id read off the uploaded sheet.
 *
 * <p>This is the seam for resolving employees. Today {@link StubEmployeeDirectoryClient}
 * supplies the data locally (no external API). To source it elsewhere later, add
 * another implementation annotated {@code @Primary}; the rest of the pipeline is
 * unchanged.
 */
public interface EmployeeDirectoryClient {

    Optional<EmployeeInfo> findByAttendanceId(String attendanceId);
}
