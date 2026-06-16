package com.example.leavemanagement.client;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Local directory client: resolves an employee purely from the attendance id,
 * with no external API. Joining date is unknown ({@code null}), so the quarterly
 * leave policy treats resources as present from the quarter start (no pro-rata).
 *
 * <p>To later resolve names/joining dates from a real source, add another
 * {@link EmployeeDirectoryClient} implementation annotated {@code @Primary} so it
 * wins autowiring, or replace this stub.
 */
@Component
public class StubEmployeeDirectoryClient implements EmployeeDirectoryClient {

    @Override
    public Optional<EmployeeInfo> findByAttendanceId(String attendanceId) {
        if (attendanceId == null || attendanceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new EmployeeInfo(attendanceId, "Employee " + attendanceId, "Unknown", null));
    }
}
