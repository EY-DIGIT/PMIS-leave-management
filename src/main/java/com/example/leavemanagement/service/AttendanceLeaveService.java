package com.example.leavemanagement.service;

import com.example.leavemanagement.client.EmployeeDirectoryClient;
import com.example.leavemanagement.client.EmployeeInfo;
import com.example.leavemanagement.dto.EmployeeAttendance;
import com.example.leavemanagement.dto.EmployeeAttendanceSummary;
import com.example.leavemanagement.dto.HolidayItem;
import com.example.leavemanagement.dto.LeaveApplyRequest;
import com.example.leavemanagement.dto.LeaveResponse;
import com.example.leavemanagement.dto.MonthlyAttendanceSummary;
import com.example.leavemanagement.entity.PublicHoliday;
import com.example.leavemanagement.exception.BadRequestException;
import com.example.leavemanagement.repository.PublicHolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Turns an uploaded monthly attendance sheet into leave records.
 *
 * <p>For each employee, a day where both In-Time and Out-Time are 0 is an
 * absence. Absences on weekends or stored public holidays are skipped (the
 * person wasn't expected in). Remaining absent working days are grouped into
 * consecutive ranges — a run is bridged across intervening weekend/holiday days
 * but ends at the last absent working day — and each range is saved as a single
 * PENDING leave. The employee name is resolved from the external directory by
 * attendance id.
 */
@Service
public class AttendanceLeaveService {

    private static final int FULL_DAY_MINUTES = 8 * 60; // a day under this counts as a "short" day

    private final AttendanceExcelParser parser;
    private final EmployeeDirectoryClient directory;
    private final PublicHolidayRepository holidayRepository;
    private final LeaveService leaveService;

    public AttendanceLeaveService(
            AttendanceExcelParser parser,
            EmployeeDirectoryClient directory,
            PublicHolidayRepository holidayRepository,
            LeaveService leaveService) {
        this.parser = parser;
        this.directory = directory;
        this.holidayRepository = holidayRepository;
        this.leaveService = leaveService;
    }

    @Transactional
    public List<LeaveResponse> importLeaves(int year, int month, MultipartFile file) {
        validateMonthAndYear(year, month);

        LocalDate monthStart = LocalDate.of(year, month, 1);
        int lengthOfMonth = monthStart.lengthOfMonth();
        Set<LocalDate> holidays =
                holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(monthStart, monthStart.withDayOfMonth(lengthOfMonth)).stream()
                        .map(PublicHoliday::getHolidayDate)
                        .collect(Collectors.toSet());

        List<EmployeeAttendance> employees = parser.parse(file);
        List<LeaveResponse> created = new ArrayList<>();

        for (EmployeeAttendance employee : employees) {
            String employeeName = resolveName(employee);
            for (int[] range : groupConsecutive(employee.absentDays(), year, month, lengthOfMonth, holidays)) {
                LocalDate start = LocalDate.of(year, month, range[0]);
                LocalDate end = LocalDate.of(year, month, range[1]);
                String reason = "Marked absent from attendance import (%04d-%02d)".formatted(year, month);
                created.add(leaveService.applyForLeave(new LeaveApplyRequest(employeeName, start, end, reason)));
            }
        }
        return created;
    }

    /**
     * Summarises one month from an attendance sheet without persisting anything:
     * the count of Saturdays, Sundays and public holidays, plus, per employee, how
     * many leaves were taken and how many worked days were under 8 hours.
     */
    @Transactional(readOnly = true)
    public MonthlyAttendanceSummary summarize(int year, int month, MultipartFile file) {
        validateMonthAndYear(year, month);
        return buildSummary(year, month, parser.parse(file));
    }

    /**
     * Builds the monthly summary from already-parsed (or stored) per-employee
     * attendance. Shared by the file upload and the stored-data GET endpoint.
     */
    @Transactional(readOnly = true)
    public MonthlyAttendanceSummary buildSummary(int year, int month, List<EmployeeAttendance> attendance) {
        validateMonthAndYear(year, month);

        LocalDate monthStart = LocalDate.of(year, month, 1);
        int lengthOfMonth = monthStart.lengthOfMonth();
        LocalDate monthEnd = monthStart.withDayOfMonth(lengthOfMonth);

        List<PublicHoliday> holidayEntities =
                holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(monthStart, monthEnd);
        Set<LocalDate> holidayDates =
                holidayEntities.stream().map(PublicHoliday::getHolidayDate).collect(Collectors.toSet());
        List<HolidayItem> publicHolidays = holidayEntities.stream()
                .map(h -> new HolidayItem(h.getHolidayDate(), h.getName()))
                .toList();

        int saturdays = 0;
        int sundays = 0;
        for (int day = 1; day <= lengthOfMonth; day++) {
            DayOfWeek dow = LocalDate.of(year, month, day).getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) {
                saturdays++;
            } else if (dow == DayOfWeek.SUNDAY) {
                sundays++;
            }
        }

        List<EmployeeAttendanceSummary> employees = new ArrayList<>();
        for (EmployeeAttendance employee : attendance) {
            int leaveDays = (int) employee.absentDays().stream()
                    .filter(day -> day >= 1 && day <= lengthOfMonth)
                    .map(day -> LocalDate.of(year, month, day))
                    .filter(date -> isWorkingDay(date, holidayDates))
                    .count();

            List<Integer> shortHourDays = employee.workedMinutesByDay().entrySet().stream()
                    .filter(e -> e.getValue() < FULL_DAY_MINUTES)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();

            employees.add(new EmployeeAttendanceSummary(
                    employee.attendanceId(),
                    resolveName(employee),
                    employee.designation(),
                    leaveDays,
                    shortHourDays.size(),
                    shortHourDays));
        }

        return new MonthlyAttendanceSummary(
                year,
                month,
                lengthOfMonth,
                saturdays,
                sundays,
                saturdays + sundays,
                publicHolidays.size(),
                publicHolidays,
                employees.size(),
                employees);
    }

    private void validateMonthAndYear(int year, int month) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("month must be between 1 and 12");
        }
        if (year < 1970 || year > 9999) {
            throw new BadRequestException("year must be between 1970 and 9999");
        }
    }

    private String resolveName(EmployeeAttendance employee) {
        return directory
                .findByAttendanceId(employee.attendanceId())
                .map(EmployeeInfo::employeeName)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> employee.employeeName().isBlank()
                        ? "Attendance " + employee.attendanceId()
                        : employee.employeeName());
    }

    /**
     * Groups absent working days into consecutive {@code [startDay, endDay]} ranges.
     * A present working day ends the current run; absent weekend/holiday days are
     * bridged (kept inside the span) but never start or end a run.
     */
    private List<int[]> groupConsecutive(
            Set<Integer> absentDays, int year, int month, int lengthOfMonth, Set<LocalDate> holidays) {
        List<int[]> ranges = new ArrayList<>();
        Integer runStart = null;
        Integer runEnd = null;

        for (int day = 1; day <= lengthOfMonth; day++) {
            LocalDate date = LocalDate.of(year, month, day);
            boolean absent = absentDays.contains(day);

            if (!absent) {
                // Present (worked) — close any open run.
                if (runStart != null) {
                    ranges.add(new int[] {runStart, runEnd});
                    runStart = null;
                    runEnd = null;
                }
                continue;
            }

            if (isWorkingDay(date, holidays)) {
                // Absent working day — extend (or start) the run.
                if (runStart == null) {
                    runStart = day;
                }
                runEnd = day;
            }
            // else: absent weekend/holiday — bridge it; leaves the run open without extending its end.
        }
        if (runStart != null) {
            ranges.add(new int[] {runStart, runEnd});
        }
        return ranges;
    }

    private boolean isWorkingDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
    }
}
